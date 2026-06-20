package com.claudecode.agent;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.claudecode.memory.MemoryManager;
import com.claudecode.plan.*;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plan-and-Execute Agent —— 将复杂任务先规划为可执行计划，再按拓扑序逐项执行。
 *
 * 架构：Planner（规划）→ Executor（执行）→ Summarizer（总结）
 */
public class PlanAndExecuteAgent {

    private final DeepSeekClient llmClient;
    private final Planner planner;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    /** 构造函数（共享 DeepSeekClient 和 MemoryManager） */
    public PlanAndExecuteAgent(DeepSeekClient llmClient, MemoryManager mm) {
        this.llmClient = llmClient;
        this.mm = mm;
        this.planner = new Planner(llmClient);
        this.toolRegistry = new ToolRegistry();
        this.mapper = new ObjectMapper();
    }

    public PlanAndExecuteAgent(DeepSeekClient llmClient) {
        this(llmClient, new MemoryManager(llmClient));
    }

    public PlanAndExecuteAgent(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    private final MemoryManager mm;

    // ══════════════════════════════════════════════════
    //  Phase 1: Planning（委托给 Planner）
    // ══════════════════════════════════════════════════

    /** 分层规划开关——复杂任务建议打开 */
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
        System.out.println("⚡ 执行中...\n");
        initialPlan.markStarted();

        ExecutionPlan plan = initialPlan;
        int total = plan.getExecutionOrder().size();
        System.out.println(buildTaskSummary(plan));
        System.out.println();

        int successCount = 0, failCount = 0;
        int showCount = 0; // 控制 replan 提示频率

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!plan.allCompleted() && !plan.hasFailed()) {
                List<Task> ready = plan.getNextExecutable();
                if (ready.isEmpty()) break;

                // 并行执行当前批次（lambda 用 final 副本 p）
                ExecutionPlan p = plan;
                List<CompletableFuture<Void>> futures = ready.stream()
                        .map(task -> CompletableFuture.runAsync(() -> executeOneTask(task, p), executor))
                        .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 统计本轮
                for (Task task : ready) {
                    if (task.getStatus() == TaskStatus.COMPLETED) successCount++;
                    else if (task.getStatus() == TaskStatus.FAILED) failCount++;
                }

                // 失败级联跳过依赖链
                for (Task task : ready) {
                    if (task.getStatus() == TaskStatus.FAILED) {
                        plan.skipDependentsOf(task.getId());
                    }
                }

                printProgress(countCompleted(plan), total);

                // ── 自我修正：失败率过高且至少 2 次失败，触发重新规划 ──
                int done = successCount + failCount;
                double rate = done > 0 ? (double) failCount / done : 0;
                boolean shouldReplan = failCount >= 2 && rate > 0.5 && showCount < 1;
                if (shouldReplan) {
                    showCount++;
                    System.out.println();
                    System.out.println("  ⚠️ 任务失败率 " + (int)(rate*100) + "%(" + failCount + "/" + done + ")，正在重新规划...");
                    String reason = buildFailureReason(plan);
                    try {
                        ExecutionPlan newPlan = planner.replan(plan, reason);
                        plan = newPlan;
                        plan.markStarted();
                        total = plan.getExecutionOrder().size();
                        successCount = 0;
                        failCount = 0;
                        System.out.println("  ✅ 已生成新计划，继续执行...\n");
                        System.out.println(buildTaskSummary(plan));
                        System.out.println();
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

    /** 构建失败原因摘要（用于 replan 上下文） */
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

    /** 在独立线程中执行单个任务 */
    private void executeOneTask(Task task, ExecutionPlan plan) {
        task.markStarted();
        try {
            String result = switch (task.getType()) {
                case FILE_READ, FILE_WRITE, COMMAND -> executeToolTask(task, plan);
                case ANALYSIS, VERIFICATION, PLANNING -> executeCognitiveTask(task, plan);
            };
            task.markCompleted(result);
        } catch (Exception e) {
            task.markFailed(e.getMessage());
        }

        synchronized (System.out) {
            System.out.println("  " + iconOfStatus(task.getStatus()) + "  " + task.getId() + ": " + task.getDescription());
            if (task.getStatus() == TaskStatus.FAILED) {
                System.out.println("     错误: " + task.getError());
            }
        }
    }

    private String iconOfStatus(TaskStatus s) {
        return switch (s) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    /**
     * 执行工具类任务：先用 LLM 把 task.description（自然语言）翻译为具体的工具参数，
     * 再调用对应的工具执行。
     */
    private String executeToolTask(Task task, ExecutionPlan plan) throws IOException {
        String toolName = switch (task.getType()) {
            case FILE_READ -> "read_file";
            case FILE_WRITE -> "write_file";
            case COMMAND -> "execute_command";
            default -> throw new IllegalStateException("不支持的工具类型: " + task.getType());
        };

        String paramFields = switch (task.getType()) {
            case FILE_READ -> "\"path\": \"文件的绝对路径\"";
            case FILE_WRITE -> "\"path\": \"文件的绝对路径\", \"content\": \"完整的文件内容\"";
            case COMMAND -> "\"command\": \"准确完整的可执行命令\"";
            default -> "";
        };

        String context = buildContext(task, plan);

        List<LLMModels.Message> messages = new ArrayList<>();
        messages.add(LLMModels.Message.system("""
                你是工具参数翻译专家。根据执行上下文和当前任务描述，生成调用工具所需的精确参数。

                请全程使用中文进行思考和输出。

                严格输出以下 JSON 格式（不要包含其他任何内容）：
                {
                  """ + paramFields + """
                }

                注意：
                - 路径必须使用绝对路径，参考上下文中已完成任务的结果
                - FILE_WRITE 的 content 必须写完整代码，不能省略、不能用注释代替
                - COMMAND 的 command 必须写完整可执行命令（如 mkdir、javac、java）
                - 如果上下文中没有路径信息，根据任务描述合理推断
                - 当前操作系统是 Windows，mkdir 不要用 -p 参数，直接写完整 mkdir 命令
                - 只输出 JSON，不要包含解释文字
                """));
        messages.add(LLMModels.Message.user(context + "\n\n需要转化为工具参数的当前任务: " + task.getDescription()));

        LLMModels.ChatResponse response = llmClient.chat(messages, List.of());
        String jsonStr = extractJson(response.getContent());
        Map<String, String> params = parseJsonToMap(jsonStr);

        return toolRegistry.executeTool(toolName, params);
    }

    /** 认知类任务：调用 LLM 进行分析/验证 */
    private String executeCognitiveTask(Task task, ExecutionPlan plan) throws IOException {
        String context = buildContext(task, plan);

        List<LLMModels.Message> messages = new ArrayList<>();
        messages.add(LLMModels.Message.system("""
                你是任务执行专家。根据已完成任务的结果，完成当前任务。
                请全程使用中文进行思考和输出。
                请直接给出分析结论或验证结果，不要调用工具，不要废话。
                """));
        messages.add(LLMModels.Message.user(context));

        LLMModels.ChatResponse response = llmClient.chat(messages, List.of());
        return response.getContent();
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
                taskResults.append("\n   结果: ").append(truncate(t.getResult()));
            }
            if (t.getError() != null) {
                taskResults.append("\n   错误: ").append(t.getError());
            }
            taskResults.append("\n\n");
        }

        List<LLMModels.Message> messages = new ArrayList<>();
        messages.add(LLMModels.Message.system("""
                你是结果汇总专家。根据任务执行结果，生成简洁的中文总结报告。
                请全程使用中文进行思考和输出。
                包含：完成了什么、关键结果、是否有问题。
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
    //  Helpers
    // ══════════════════════════════════════════════════

    private String buildContext(Task task, ExecutionPlan plan) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("执行目标: ").append(plan.getGoal()).append("\n\n");
        ctx.append("已完成任务:\n");
        for (Task t : plan.getTasks().values()) {
            if (t.getStatus() == TaskStatus.COMPLETED) {
                ctx.append("  [").append(t.getId()).append("] ").append(t.getDescription()).append("\n");
                ctx.append("  结果: ").append(truncate(t.getResult())).append("\n\n");
            }
        }
        ctx.append("当前任务: ").append(task.getDescription());
        return ctx.toString();
    }

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
        System.out.print("\r  [" + bar + "] " + pct + "% (" + done + "/" + total + ")     \n");
    }

    private int countCompleted(ExecutionPlan plan) {
        return (int) plan.getTasks().values().stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED
                        || t.getStatus() == TaskStatus.SKIPPED)
                .count();
    }

    private String extractJson(String content) {
        if (content == null) return "{}";
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return "{}";
    }

    private Map<String, String> parseJsonToMap(String jsonStr) throws IOException {
        JsonNode root = mapper.readTree(jsonStr);
        Map<String, String> params = new LinkedHashMap<>();
        root.fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
        return params;
    }

    private String truncate(String s) {
        if (s == null) return "(null)";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ══════════════════════════════════════════════════
    //  Delegate methods
    // ══════════════════════════════════════════════════

    /**
     * 将 Plan 执行摘要写入共享上下文，让后续 ReAct 对话知道之前做了什么。
     */
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
