package com.claudecode.agent;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
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

    /** 构造函数（共享 DeepSeekClient 实例） */
    public PlanAndExecuteAgent(DeepSeekClient llmClient, ConversationManager cm) {
        this.llmClient = llmClient;
        this.planner = new Planner(llmClient);
        this.toolRegistry = new ToolRegistry();
        this.mapper = new ObjectMapper();
        this.conversationManager = cm;
    }

    public PlanAndExecuteAgent(DeepSeekClient llmClient) {
        this(llmClient, null);
    }

    public PlanAndExecuteAgent(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    private final ConversationManager conversationManager;

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
    //  Phase 2: Execute（并行执行，依赖无冲突则同时跑）
    // ══════════════════════════════════════════════════

    public void execute(ExecutionPlan plan) {
        System.out.println("⚡ 执行中...\n");
        plan.markStarted();

        int total = plan.getExecutionOrder().size();
        System.out.println(buildTaskSummary(plan));
        System.out.println();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!plan.allCompleted() && !plan.hasFailed()) {
                List<Task> ready = plan.getNextExecutable();
                if (ready.isEmpty()) break;

                // 并行执行当前批次所有可执行任务
                List<CompletableFuture<Void>> futures = ready.stream()
                        .map(task -> CompletableFuture.runAsync(() -> executeOneTask(task, plan), executor))
                        .toList();

                // 等待本批全部完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 检查是否有失败，有则跳过其下游
                for (Task task : ready) {
                    if (task.getStatus() == TaskStatus.FAILED) {
                        plan.skipDependentsOf(task.getId());
                    }
                }

                printProgress(countCompleted(plan), total);
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

    /** 在独立线程中执行单个任务 */
    private void executeOneTask(Task task, ExecutionPlan plan) {
        String doneMsg;
        task.markStarted();

        try {
            String result = switch (task.getType()) {
                case FILE_READ, FILE_WRITE, COMMAND -> executeToolTask(task, plan);
                case ANALYSIS, VERIFICATION, PLANNING -> executeCognitiveTask(task, plan);
            };
            task.markCompleted(result);
            doneMsg = "✅";
        } catch (Exception e) {
            task.markFailed(e.getMessage());
            doneMsg = "❌  " + e.getMessage();
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
                - COMMAND 的 command 必须写完整可执行命令（如 "mkdir \"D:/path/to/dir\""、javac、java）
                - 如果上下文中没有路径信息，根据任务描述合理推断
                - 当前操作系统是 Windows，mkdir 不要用 -p 参数，直接用 "mkdir \"路径\"" 或 "if not exist \"路径\" mkdir \"路径\""
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
            taskResults.append("[" + t.getId() + "] ")
                    .append(t.getDescription())
                    .append(" → ").append(t.getStatus());
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
                ctx.append("  [" + t.getId() + "] " + t.getDescription() + "\n");
                ctx.append("  结果: " + truncate(t.getResult()) + "\n\n");
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
        if (conversationManager == null) return;
        String summary = plan.getSummary() != null ? plan.getSummary() : plan.getGoal();
        conversationManager.addPlanSummary(plan.getGoal(), summary);
    }

    public boolean isThinkingEnabled() { return llmClient.isThinkingEnabled(); }
    public boolean toggleThinking() {
        llmClient.setThinkingEnabled(!llmClient.isThinkingEnabled());
        return llmClient.isThinkingEnabled();
    }
}
