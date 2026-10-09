package com.claudecode.agent;

import com.claudecode.config.PromptAssembler;
import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.claudecode.memory.MemoryManager;
import com.claudecode.plan.*;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Plan-and-Execute Agent —— 先规划再执行，适合复杂多步骤任务。
 *
 * ════════════════════════════════════════════════════════
 *  三阶段执行流程
 * ════════════════════════════════════════════════════════
 *
 * Phase 1 - 规划（plan）
 *   调一次 LLM，把用户需求拆解为带依赖关系的 DAG 计划（JSON 格式）。
 *   支持分层规划（/hplan）：先定宏观阶段 → 逐阶段细化子任务。
 *   用户可以在执行前审查计划、补充要求或取消。
 *
 * Phase 2 - 执行（execute）
 *   拓扑排序确定执行顺序。
 *   每一批取"入度为 0"（所有前置依赖已完成）的任务，用虚拟线程并行执行。
 *   每个任务内部是 mini ReAct 循环（最多 5 轮），LLM 自主决定何时结束。
 *   累计 2+ 失败且失败率 > 50% 时触发重新规划。
 *
 * Phase 3 - 总结（summarize）
 *   所有任务完成后，调 LLM 汇总关键结果送回给用户。
 *
 * ════════════════════════════════════════════════════════
 *  和 Team 模式的区别
 * ════════════════════════════════════════════════════════
 *  Plan：任务失败累计到阈值才重新规划，不审查单步质量。
 *  Team：每步执行完有 Reviewer 审查，不通过带反馈重做。
 */
public class PlanAndExecuteAgent {

    /** LLM 客户端（与非流式的 Agent 共享同一实例） */
    private final DeepSeekClient llmClient;
    /** 规划器：负责和 LLM 交互生成 JSON 计划 */
    private final Planner planner;
    /** 工具注册表：所有可调用的工具 */
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;
    /** 记忆系统：用于注入长期记忆到任务上下文 */
    private final MemoryManager mm;

    /** 每个 task 的 mini ReAct 循环最多转 5 圈 */
    private static final int MAX_TASK_ITERATIONS = 5;

    // ══════════════════════════════════════════════════
    //  PlanReviewHandler —— 执行前的用户审查接口
    //
    //  审查流程（在 Main.java 的 handlePlanExecute 里处理）：
    //  - y / 回车 → 执行
    //  - n → 取消
    //  其他输入 → 作为补充反馈，重新规划
    //
    //  ConsoleReviewHandler 是默认实现，始终返回 EXECUTE。
    //  交互式审查在 Main.java 用共享 Scanner 处理，避免 Scanner 冲突。
    // ══════════════════════════════════════════════════

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction { EXECUTE, SUPPLEMENT, CANCEL }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() { return new PlanReviewDecision(PlanReviewAction.EXECUTE, null); }
        public static PlanReviewDecision supplement(String feedback) { return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback); }
        public static PlanReviewDecision cancel() { return new PlanReviewDecision(PlanReviewAction.CANCEL, null); }
    }

    /** 单个任务的执行结果（mini ReAct 循环的输出） */
    private record TaskRunResult(String result, boolean streamedOutput) {}

    /** 并行执行时包装的 Task + 结果/异常 */
    private record TaskExecutionResult(Task task, String result, Exception error) {
        boolean failed() { return error != null; }
    }

    private PlanReviewHandler reviewHandler;

    public PlanAndExecuteAgent(DeepSeekClient llmClient, MemoryManager mm) {
        this.llmClient = llmClient;
        this.mm = mm;
        this.planner = new Planner(llmClient);
        this.toolRegistry = new ToolRegistry();
        this.mapper = new ObjectMapper();
        this.reviewHandler = new ConsoleReviewHandler();
    }

    public PlanAndExecuteAgent(DeepSeekClient llmClient) {
        this(llmClient, new MemoryManager(llmClient));
    }

    public PlanAndExecuteAgent(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    public void setReviewHandler(PlanReviewHandler handler) { this.reviewHandler = handler; }

    // ══════════════════════════════════════════════════
    //  Phase 1: Planning —— 委托给 Planner
    // ══════════════════════════════════════════════════

    private boolean hierarchicalPlanning = false;
    public void setHierarchicalPlanning(boolean v) { this.hierarchicalPlanning = v; }
    public boolean isHierarchicalPlanning() { return hierarchicalPlanning; }

    /**
     * 调 LLM 生成执行计划。
     * 如果开启了分层规划（/hplan），分两次调 LLM：先定阶段 → 再细化。
     */
    public ExecutionPlan plan(String userRequest) throws IOException {
        System.out.println("📋 规划阶段 —— 分析需求并生成执行计划...\n");
        ExecutionPlan plan = hierarchicalPlanning
                ? planner.createPlanHierarchical(userRequest)
                : planner.createPlan(userRequest);
        System.out.println(plan.visualize() + "\n");
        return plan;
    }

    // ══════════════════════════════════════════════════
    //  Phase 2: Execute —— 核心执行引擎
    // ══════════════════════════════════════════════════

    /**
     * 执行计划。
     *
     * 核心逻辑：
     * 1. 先走 reviewHandler 让用户审查计划
     *    （当前 ConsoleReviewHandler 自动执行，交互在 Main.java 处理）
     * 2. 进入 while 循环，每次取一批入度为 0 的任务
     * 3. 每批任务用虚拟线程 + CompletableFuture 并行执行
     * 4. 每批结果用 ByteArrayOutputStream 缓冲，按顺序 flush
     * 5. 失败的任务触发级联跳过它的下游
     * 6. 累计 2+ 失败且失败率 > 50% 时触发重新规划
     */
    public void execute(ExecutionPlan initialPlan) {
        ExecutionPlan plan = initialPlan;

        // ── 用户审查 ──
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision.action() == PlanReviewAction.EXECUTE) break;
            if (decision.action() == PlanReviewAction.CANCEL) {
                System.out.println("⏹️ 已取消本次计划执行\n");
                plan.markFailed();
                return;
            }
            String feedback = decision.feedback() != null ? decision.feedback().trim() : "";
            if (feedback.isEmpty()) break;
            System.out.println("📝 已收到补充要求，正在重新规划...\n");
            try {
                plan = planner.replan(plan, feedback);
                System.out.println(plan.visualize() + "\n");
            } catch (Exception e) {
                System.out.println("❌ 重新规划失败: " + e.getMessage());
                return;
            }
        }

        System.out.println("🚀 开始执行计划...\n");
        plan.markStarted();

        int total = plan.getExecutionOrder().size();  // 总任务数
        int successCount = 0, failCount = 0;
        int replanCount = 0;  // 自我修正只触发一次

        // ── 虚拟线程池，每个 task 提交为一个虚拟线程 ──
        // 虚拟线程在 IO 等待（调 LLM 时）自动让出平台线程，
        // 适合这个场景：100 个 task 等 LLM 响应也不浪费平台线程。
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            while (!plan.allCompleted() && !plan.hasFailed()) {
                // 获取当前入度为 0 的任务（所有依赖已完成）
                List<Task> ready = plan.getNextExecutable();
                if (ready.isEmpty()) break;

                // ── 并行执行当前批次 ──
                // 每个 task 写入自己的 ByteArrayOutputStream，
                // 等全部完成后按顺序 flush 到 System.out，
                // 避免并行输出交错在一起。
                Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
                ExecutionPlan currentPlan = plan;  // lambda 用 final 副本
                List<CompletableFuture<TaskExecutionResult>> futures = new ArrayList<>();
                for (Task task : ready) {
                    System.out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
                    task.markStarted();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    buffers.put(task.getId(), baos);
                    PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                    // 提交到虚拟线程池并行执行
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            TaskRunResult r = executeTask(currentPlan, task, taskOut);
                            return new TaskExecutionResult(task, r.result(), null);
                        } catch (Exception e) {
                            return new TaskExecutionResult(task, null, e);
                        }
                    }, executor));
                }

                // 等待本批次所有任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 按任务顺序 flush 缓冲区 + 统计成功/失败
                for (int i = 0; i < ready.size(); i++) {
                    TaskExecutionResult result = futures.get(i).join();
                    ByteArrayOutputStream buf = buffers.get(result.task().getId());
                    if (buf != null && buf.size() > 0) {
                        System.out.print(buf.toString(StandardCharsets.UTF_8));
                    }
                    if (!result.failed()) {
                        result.task().markCompleted(result.result());
                        successCount++;
                        System.out.println("✅ 完成 [" + result.task().getId() + "]\n");
                    } else {
                        result.task().markFailed(result.error().getMessage());
                        failCount++;
                        System.out.println("❌ 失败 [" + result.task().getId() + "]: "
                                + result.error().getMessage() + "\n");
                    }
                }

                // ── 失败级联 ──
                // 如果 task_1 失败，所有依赖 task_1 的任务（task_3, task_4...）自动标记 SKIPPED
                for (Task task : ready) {
                    if (task.getStatus() == TaskStatus.FAILED) {
                        plan.skipDependentsOf(task.getId());
                    }
                }

                printProgress(countCompleted(plan), total);

                // ── 自我修正 ──
                // 条件：失败 ≥ 2 次 且 失败率 > 50% 且 从未触发过
                // 触发：携带失败原因调 LLM 重新规划剩余任务
                int done = successCount + failCount;
                double rate = done > 0 ? (double) failCount / done : 0;
                if (failCount >= 2 && rate > 0.5 && replanCount < 1) {
                    replanCount++;
                    System.out.println();
                    System.out.println("  ⚠️ 任务失败率 " + (int)(rate*100) + "%(" + failCount + "/" + done + ")，正在重新规划...");
                    try {
                        ExecutionPlan newPlan = planner.replan(plan, buildFailureReason(plan));
                        plan = newPlan;
                        plan.markStarted();
                        total = plan.getExecutionOrder().size();
                        successCount = 0;
                        failCount = 0;
                        System.out.println("  ✅ 已生成新计划，继续执行...\n");
                        System.out.println(buildTaskSummary(plan) + "\n");
                        printProgress(0, total);
                    } catch (Exception e) {
                        System.out.println("  ❌ 重新规划失败: " + e.getMessage());
                        break;
                    }
                }
            }
        }

        // ── 执行结果总结 ──
        System.out.println();
        if (plan.hasFailed()) {
            plan.markFailed();
            System.out.println("❌ 有任务失败，计划未完成\n");
        } else {
            plan.markCompleted();
            System.out.println("✅ 计划执行完成\n");
        }
    }

    /**
     * 单个任务的 mini ReAct 执行循环。
     *
     * 每个 task 有自己的独立对话历史（messages 列表），
     * 和 Agent 主循环的对话是隔离的。
     *
     * 循环逻辑：
     * 1. 调 LLM，传入当前 task 的上下文 + 工具定义
     * 2. LLM 返回工具调用 → 执行工具 → 结果回灌 messages → 继续循环
     * 3. LLM 直接回答 → 返回结果
     * 4. 达到 MAX_TASK_ITERATIONS → 返回累积的所有工具结果
     *
     * @param plan 当前执行计划（用于获取依赖任务的结果）
     * @param task 要执行的任务
     * @param out  输出流（并行时传入 ByteArrayOutputStream）
     */
    private TaskRunResult executeTask(ExecutionPlan plan, Task task, PrintStream out) throws IOException {
        // 构建 system prompt + 任务上下文（含依赖任务的执行结果）
        String sysPrompt = buildTaskSystemPrompt(task);
        String taskInput = buildTaskContext(plan.getGoal(), plan, task);

        // 注入相关长期记忆
        String memoryCtx = mm.buildContextForQuery(task.getDescription(), 500);
        if (!memoryCtx.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryCtx;
        }

        List<LLMModels.Message> messages = new ArrayList<>();
        messages.add(LLMModels.Message.system(sysPrompt));
        messages.add(LLMModels.Message.user(taskInput));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            iteration++;

            // 调 LLM，传入工具定义（LLM 决定是否调工具）
            LLMModels.ChatResponse response = llmClient.chat(messages, toolDefinitions());
            mm.recordTokenUsage(
                    response.usage() != null ? response.usage().promptTokens() : 0,
                    response.usage() != null ? response.usage().completionTokens() : 0);

            if (!response.hasToolCalls()) {
                // LLM 认为任务已完成，直接回答
                String content = response.getContent();
                // 如果之前累积了工具结果但现在没返回新的内容，返回累积结果
                if (!allResults.isEmpty() && (content == null || content.isBlank())) {
                    return new TaskRunResult(allResults.toString().trim(), false);
                }
                return new TaskRunResult(content, false);
            }

            // LLM 想调工具 → 执行工具，结果回灌到对话历史
            printToolCalls(out, response.getToolCalls());
            messages.add(LLMModels.Message.assistantWithToolCall(response.getToolCalls()));

            for (LLMModels.ToolCall tc : response.getToolCalls()) {
                Map<String, String> args = parseArguments(tc.function().arguments());
                String result = toolRegistry.executeTool(tc.function().name(), args);
                out.println("  工具结果: " + truncate(result, 200) + "\n");
                messages.add(LLMModels.Message.tool(result, tc.id()));
                allResults.append(result).append("\n");
            }
        }

        // 超限兜底：返回所有累积的工具执行结果
        String fallback = allResults.toString().trim();
        return new TaskRunResult(fallback, false);
    }

    /** 打印工具调用信息（工具名 + 参数预览） */
    private void printToolCalls(PrintStream out, List<LLMModels.ToolCall> toolCalls) {
        for (LLMModels.ToolCall tc : toolCalls) {
            out.println("  🔧 " + tc.function().name()
                    + "(" + truncate(tc.function().arguments(), 80) + ")");
        }
    }

    /**
     * 根据任务类型构建不同的 system prompt。
     *
     * 文件操作/命令执行类任务（FILE_READ/WRITE/COMMAND）：
     *   可以用工具，prompt 告知 LLM 可调用工具完成任务。
     *
     * 认知类任务（ANALYSIS/VERIFICATION/PLANNING）：
     *   只用分析，不调工具，prompt 告知直接输出结果。
     */
    private String buildTaskSystemPrompt(Task task) {
        return switch (task.getType()) {
            case FILE_READ, FILE_WRITE, COMMAND -> PromptAssembler.load("modes/plan-executor.md");
            case ANALYSIS, VERIFICATION, PLANNING -> PromptAssembler.load("modes/plan-cognitive.md");
        };
    }

    /** 获取工具定义列表（传给 LLM 的 Function Calling 接口） */
    private List<LLMModels.Tool> toolDefinitions() {
        List<LLMModels.Tool> tools = new ArrayList<>();
        for (com.claudecode.tool.Tool t : toolRegistry.getAllTools()) {
            tools.add(new LLMModels.Tool(t.name(), t.description(), t.parameters()));
        }
        return tools;
    }

    // ══════════════════════════════════════════════════
    //  上下文构建
    // ══════════════════════════════════════════════════

    /**
     * 构建失败原因摘要（用于 replan 的上下文）。
     * 列出所有失败的任务及其错误信息。
     */
    private String buildFailureReason(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder("计划执行过程出现问题：\n");
        for (Task t : plan.getTasks().values()) {
            if (t.getStatus() == TaskStatus.FAILED) {
                sb.append("- ❌ ").append(t.getId()).append(": ").append(t.getDescription()).append("\n");
                sb.append("  错误: ").append(t.getError()).append("\n");
            }
        }
        sb.append("请基于已完成的工作，重新规划剩余任务。");
        return sb.toString();
    }

    /**
     * 构建单个任务的执行上下文。
     *
     * 内容包括：
     * - 总目标（用户最初的需求）
     * - 当前任务的描述
     * - 所有已完成依赖任务的执行结果（截断到 300 字符）
     *
     * 这样 LLM 在执行当前任务时能参考前置任务的输出。
     */
    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("总目标：").append(goal).append("\n");
        ctx.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            ctx.append("依赖任务：无\n");
        } else {
            ctx.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTasks().get(depId);
                if (dep == null) continue;
                ctx.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus()).append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    ctx.append("  ").append(truncate(dep.getResult(), 300)).append("\n");
                }
            }
        }

        ctx.append("\n请执行此任务。");
        return ctx.toString();
    }

    // ══════════════════════════════════════════════════
    //  Phase 3: Summary —— 汇总执行结果
    // ══════════════════════════════════════════════════

    /**
     * 调 LLM 汇总所有任务的执行结果，生成 readable 报告。
     * 报告内容包括：完成了什么、关键结果、是否有问题。
     */
    public String summarize(ExecutionPlan plan) throws IOException {
        System.out.println("📊 总结阶段 —— 汇总执行结果...\n");

        // 按执行顺序整理每个任务的结果/错误
        StringBuilder taskResults = new StringBuilder();
        for (String taskId : plan.getExecutionOrder()) {
            Task t = plan.getTasks().get(taskId);
            taskResults.append("[").append(t.getId()).append("] ")
                    .append(t.getDescription()).append(" → ").append(t.getStatus());
            if (t.getResult() != null) {
                taskResults.append("\n   结果: ").append(truncate(t.getResult(), 300));
            }
            if (t.getError() != null) {
                taskResults.append("\n   错误: ").append(truncate(t.getError(), 200));
            }
            taskResults.append("\n\n");
        }

        // 调 LLM 汇总
        List<LLMModels.Message> messages = new ArrayList<>();
        messages.add(LLMModels.Message.system(PromptAssembler.load("modes/plan-summarizer.md")));
        messages.add(LLMModels.Message.user("目标: " + plan.getGoal() + "\n\n" + taskResults));

        LLMModels.ChatResponse response = llmClient.chat(messages, List.of());
        plan.setSummary(response.getContent());
        return response.getContent();
    }

    // ══════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════

    /** 一键执行：规划 → 执行 → 总结 */
    public String run(String userRequest) {
        try {
            ExecutionPlan plan = plan(userRequest);
            execute(plan);
            return summarize(plan);
        } catch (Exception e) {
            return "Plan-and-Execute 执行失败: " + e.getMessage();
        }
    }

    /** 外部反馈后重新规划（用户审阅时补充要求时用） */
    public ExecutionPlan replan(ExecutionPlan original, String feedback) throws IOException {
        return planner.replan(original, feedback);
    }

    // ══════════════════════════════════════════════════
    //  默认 PlanReviewHandler
    // ══════════════════════════════════════════════════

    /**
     * 默认审查处理器：展示计划后自动执行。
     * 交互式审查在 Main.java 中用共享 Scanner 处理（避免多 Scanner 冲突）。
     */
    private static class ConsoleReviewHandler implements PlanReviewHandler {
        @Override
        public PlanReviewDecision review(String goal, ExecutionPlan plan) {
            System.out.println(plan.visualize() + "\n");
            return PlanReviewDecision.execute();
        }
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    /** 生成所有任务的摘要列表 */
    private String buildTaskSummary(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (String taskId : plan.getExecutionOrder()) {
            Task t = plan.getTasks().get(taskId);
            sb.append("  ⏳ ").append(taskId).append(" [").append(t.getType()).append("] ")
                    .append(t.getDescription()).append("\n");
        }
        return sb.toString();
    }

    /** 打印进度条（如 [▓▓▓▓▓▓░░░░░░░] 60% (3/5)） */
    private void printProgress(int done, int total) {
        int barWidth = 30;
        int filled = (int) ((done * (long) barWidth) / total);
        String bar = "▓".repeat(filled) + "░".repeat(barWidth - filled);
        int pct = (done * 100) / total;
        System.out.print("  [" + bar + "] " + pct + "% (" + done + "/" + total + ")     \n");
    }

    /** 统计已完成和被跳过的任务数 */
    private int countCompleted(ExecutionPlan plan) {
        return (int) plan.getTasks().values().stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED
                        || t.getStatus() == TaskStatus.SKIPPED)
                .count();
    }

    /**
     * 解析 LLM 返回的工具参数 JSON。
     * 用 Map<String, Object> 接收，再转 String，支持布尔/数字参数。
     */
    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = mapper.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (var e : raw.entrySet()) {
                result.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return result;
        } catch (Exception e) {
            return Map.of("arguments", argumentsJson);
        }
    }

    /** 截断字符串到指定长度（用于日志和上下文显示） */
    private String truncate(String s, int n) {
        if (s == null) return "(null)";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    // ══════════════════════════════════════════════════
    //  Delegate methods
    // ══════════════════════════════════════════════════

    /** 将 Plan 执行摘要写入共享上下文，让后续 ReAct 对话知道之前做了什么 */
    public void writeBackToContext(ExecutionPlan plan) {
        String goal = plan.getGoal();
        String summary = plan.getSummary() != null ? plan.getSummary() : goal;
        mm.storeMessage(LLMModels.Message.user(
                "【系统提示】刚才通过 Plan-and-Execute 模式完成了以下任务：" +
                "\n目标: " + goal +
                "\n执行摘要: " + summary +
                "\n后续对话请基于以上已完成的操作为上下文。"));
        mm.storeMessage(LLMModels.Message.assistant("已了解，之前的操作已完成。我会基于此继续协助。"));
    }

    // ── 思考模式控制（与 Agent 共享同一 LLMClient） ──
    public boolean isThinkingEnabled() { return llmClient.isThinkingEnabled(); }
    public boolean toggleThinking() {
        llmClient.setThinkingEnabled(!llmClient.isThinkingEnabled());
        return llmClient.isThinkingEnabled();
    }
}
