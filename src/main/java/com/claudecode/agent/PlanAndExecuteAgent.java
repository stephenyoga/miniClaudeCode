package com.claudecode.agent;

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
 * Plan-and-Execute Agent —— 将复杂任务先规划为可执行计划，再按拓扑序逐项执行。
 *
 * 架构：Planner（规划）→ Review（用户审查）→ Executor（mini ReAct 多轮工具调用）→ Summarizer（总结）
 */
public class PlanAndExecuteAgent {

    private final DeepSeekClient llmClient;
    private final Planner planner;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;
    private final MemoryManager mm;

    private static final int MAX_TASK_ITERATIONS = 5;

    // ── 计划审查接口 ──

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction { EXECUTE, SUPPLEMENT, CANCEL }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() { return new PlanReviewDecision(PlanReviewAction.EXECUTE, null); }
        public static PlanReviewDecision supplement(String feedback) { return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback); }
        public static PlanReviewDecision cancel() { return new PlanReviewDecision(PlanReviewAction.CANCEL, null); }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {}

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
    //  Phase 1: Planning（委托给 Planner）
    // ══════════════════════════════════════════════════

    private boolean hierarchicalPlanning = false;
    public void setHierarchicalPlanning(boolean v) { this.hierarchicalPlanning = v; }
    public boolean isHierarchicalPlanning() { return hierarchicalPlanning; }

    public ExecutionPlan plan(String userRequest) throws IOException {
        System.out.println("📋 规划阶段 —— 分析需求并生成执行计划...\n");
        ExecutionPlan plan = hierarchicalPlanning
                ? planner.createPlanHierarchical(userRequest)
                : planner.createPlan(userRequest);
        System.out.println(plan.visualize() + "\n");
        return plan;
    }

    // ══════════════════════════════════════════════════
    //  Phase 2: Execute（并行执行 + 自我修正）
    // ══════════════════════════════════════════════════

    public void execute(ExecutionPlan initialPlan) {
        ExecutionPlan plan = initialPlan;

        // 用户审查循环
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

        int total = plan.getExecutionOrder().size();
        int successCount = 0, failCount = 0;
        int replanCount = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            while (!plan.allCompleted() && !plan.hasFailed()) {
                List<Task> ready = plan.getNextExecutable();
                if (ready.isEmpty()) break;

                // 并行执行当前批次，输出缓冲防交错
                Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
                ExecutionPlan currentPlan = plan;
                List<CompletableFuture<TaskExecutionResult>> futures = new ArrayList<>();
                for (Task task : ready) {
                    System.out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
                    task.markStarted();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    buffers.put(task.getId(), baos);
                    PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            TaskRunResult r = executeTask(currentPlan, task, taskOut);
                            return new TaskExecutionResult(task, r.result(), null);
                        } catch (Exception e) {
                            return new TaskExecutionResult(task, null, e);
                        }
                    }, executor));
                }

                // 等待所有任务完成，再按顺序 flush 缓冲区
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

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

                // 失败级联跳过依赖链
                for (Task task : ready) {
                    if (task.getStatus() == TaskStatus.FAILED) {
                        plan.skipDependentsOf(task.getId());
                    }
                }

                printProgress(countCompleted(plan), total);

                // ── 自我修正 ──
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
     * 执行单个任务 —— mini ReAct 循环，LLM 自主多轮工具调用后结束。
     * 每个 task 内可多轮工具调用，LLM 自主控制何时结束。
     */
    private TaskRunResult executeTask(ExecutionPlan plan, Task task, PrintStream out) throws IOException {
        String sysPrompt = buildTaskSystemPrompt(task);
        String taskInput = buildTaskContext(plan.getGoal(), plan, task);

        // 注入长期记忆
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

            LLMModels.ChatResponse response = llmClient.chat(messages, toolDefinitions());
            mm.recordTokenUsage(
                    response.usage() != null ? response.usage().promptTokens() : 0,
                    response.usage() != null ? response.usage().completionTokens() : 0);

            if (!response.hasToolCalls()) {
                String content = response.getContent();
                if (!allResults.isEmpty() && (content == null || content.isBlank())) {
                    return new TaskRunResult(allResults.toString().trim(), false);
                }
                return new TaskRunResult(content, false);
            }

            // 有工具调用：执行并回灌
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

        String fallback = allResults.toString().trim();
        return new TaskRunResult(fallback, false);
    }

    private void printToolCalls(PrintStream out, List<LLMModels.ToolCall> toolCalls) {
        for (LLMModels.ToolCall tc : toolCalls) {
            out.println("  🔧 " + tc.function().name()
                    + "(" + truncate(tc.function().arguments(), 80) + ")");
        }
    }

    /** 构建任务执行的 system prompt */
    private String buildTaskSystemPrompt(Task task) {
        return switch (task.getType()) {
            case FILE_READ, FILE_WRITE, COMMAND -> """
                    你是任务执行专家。根据上下文和当前任务，决定需要调用哪些工具。
                    全程使用中文。如果任务只需分析/总结，直接输出结果，不要调用工具。
                    如果需要操作文件或执行命令，调用对应工具后基于结果输出完成说明。
                    """;
            case ANALYSIS, VERIFICATION, PLANNING -> """
                    你是任务执行专家。根据上下文和当前任务，直接给出分析结论。
                    全程使用中文。不要调用工具，直接输出结果即可。
                    """;
        };
    }

    private List<LLMModels.Tool> toolDefinitions() {
        List<LLMModels.Tool> tools = new ArrayList<>();
        for (com.claudecode.tool.Tool t : toolRegistry.getAllTools()) {
            tools.add(new LLMModels.Tool(t.name(), t.description(), t.parameters()));
        }
        return tools;
    }

    // ══════════════════════════════════════════════════
    //  失败原因 & 任务上下文
    // ══════════════════════════════════════════════════

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
    //  Phase 3: Summary（总结）
    // ══════════════════════════════════════════════════

    public String summarize(ExecutionPlan plan) throws IOException {
        System.out.println("📊 总结阶段 —— 汇总执行结果...\n");

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

        List<LLMModels.Message> messages = new ArrayList<>();
        messages.add(LLMModels.Message.system("""
                你是结果汇总专家。根据任务执行结果，生成简洁的中文总结报告。
                全程使用中文。包含：完成了什么、关键结果、是否有问题。
                """));
        messages.add(LLMModels.Message.user("目标: " + plan.getGoal() + "\n\n" + taskResults));

        LLMModels.ChatResponse response = llmClient.chat(messages, List.of());
        plan.setSummary(response.getContent());
        return response.getContent();
    }

    // ══════════════════════════════════════════════════
    //  Public: 一键 Plan → Execute → Summary
    // ══════════════════════════════════════════════════

    public String run(String userRequest) {
        try {
            ExecutionPlan plan = plan(userRequest);
            execute(plan);
            return summarize(plan);
        } catch (Exception e) {
            return "Plan-and-Execute 执行失败: " + e.getMessage();
        }
    }

    public ExecutionPlan replan(ExecutionPlan original, String feedback) throws IOException {
        return planner.replan(original, feedback);
    }

    // ══════════════════════════════════════════════════
    //  PlanReviewHandler 默认实现 —— 控制台审查
    // ══════════════════════════════════════════════════

    private static class ConsoleReviewHandler implements PlanReviewHandler {
        private final Scanner scanner = new Scanner(System.in);

        @Override
        public PlanReviewDecision review(String goal, ExecutionPlan plan) {
            System.out.print("是否执行此计划？[y=执行 / n=取消 / 输入补充要求]: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty() || input.equalsIgnoreCase("y")) {
                return PlanReviewDecision.execute();
            }
            if (input.equalsIgnoreCase("n")) {
                return PlanReviewDecision.cancel();
            }
            return PlanReviewDecision.supplement(input);
        }
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private String buildTaskSummary(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (String taskId : plan.getExecutionOrder()) {
            Task t = plan.getTasks().get(taskId);
            sb.append("  ⏳ ").append(taskId).append(" [").append(t.getType()).append("] ")
                    .append(t.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private void printProgress(int done, int total) {
        int barWidth = 30;
        int filled = (int) ((done * (long) barWidth) / total);
        String bar = "▓".repeat(filled) + "░".repeat(barWidth - filled);
        int pct = (done * 100) / total;
        System.out.print("  [" + bar + "] " + pct + "% (" + done + "/" + total + ")     \n");
    }

    private int countCompleted(ExecutionPlan plan) {
        return (int) plan.getTasks().values().stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED
                        || t.getStatus() == TaskStatus.SKIPPED)
                .count();
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return mapper.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of("arguments", argumentsJson);
        }
    }

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

    public boolean isThinkingEnabled() { return llmClient.isThinkingEnabled(); }
    public boolean toggleThinking() {
        llmClient.setThinkingEnabled(!llmClient.isThinkingEnabled());
        return llmClient.isThinkingEnabled();
    }
}
