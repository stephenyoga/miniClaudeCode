package com.claudecode.plan;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

/**
 * 规划器 —— 将用户目标分解为可执行的计划。
 * 负责与 LLM 交互生成 JSON 计划，解析并构建 Task DAG。
 */
public class Planner {

    private final DeepSeekClient llmClient;
    private final ObjectMapper mapper;

    private static final String PLANNING_PROMPT = """
            你是一个资深的任务规划专家。请将用户的需求拆解为可执行的子任务计划。

            请全程使用中文进行思考和输出。

            请严格按以下 JSON 格式输出执行计划（不要包含 markdown 代码块标记）：
            {
              "summary": "一句话概括任务目标（中文）",
              "tasks": [
                {
                  "id": "task_1",
                  "description": "具体的任务描述（中文），包含文件路径、命令等关键信息",
                  "type": "FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
                  "dependencies": []
                }
              ]
            }

            可用任务类型：
            - FILE_READ: 读取文件内容，description 中应包含文件的绝对路径
            - FILE_WRITE: 写入文件内容，description 中应包含文件的绝对路径和内容概要
            - COMMAND: 执行 Shell 命令，description 中应包含完整命令（如 mkdir、javac、java）
            - ANALYSIS: 分析已有结果、做出中间决策
            - VERIFICATION: 验证最终结果是否正确

            规则：
            1. 每个任务 id 按 task_1, task_2, ... 编号（按执行顺序）
            2. 有依赖的任务在 dependencies 中列出前置任务 id
            3. 最后一个任务必须是 VERIFICATION 类型
            4. 将复杂任务拆分为 3-8 个子任务
            5. description 要具体，包含实际要操作的路径或命令
            6. 只输出 JSON，不要包含任何解释文字
            """;

    public Planner(DeepSeekClient llmClient) {
        this.llmClient = llmClient;
        this.mapper = new ObjectMapper();
    }

    public Planner(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    // ══════════════════════════════════════════════════
    //  创建计划
    // ══════════════════════════════════════════════════

    public ExecutionPlan createPlan(String goal) throws IOException {
        List<LLMModels.Message> messages = Arrays.asList(
                LLMModels.Message.system(PLANNING_PROMPT),
                LLMModels.Message.user("请为以下任务制定执行计划：\n" + goal)
        );

        LLMModels.ChatResponse response = llmClient.chat(messages, null);
        return parsePlan(goal, response.getContent());
    }

    // ══════════════════════════════════════════════════
    //  重新规划（基于失败进度）
    // ══════════════════════════════════════════════════

    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        StringBuilder context = new StringBuilder();
        context.append("原始目标: ").append(failedPlan.getGoal()).append("\n\n");
        context.append("已完成的任务:\n");
        for (Task t : failedPlan.getTasks().values()) {
            if (t.getStatus() == TaskStatus.COMPLETED) {
                context.append("  ✅ ").append(t.getId()).append(": ").append(t.getDescription()).append("\n");
                if (t.getResult() != null) {
                    context.append("     结果: ").append(t.getResult()).append("\n");
                }
            } else if (t.getStatus() == TaskStatus.FAILED) {
                context.append("  ❌ ").append(t.getId()).append(": ").append(t.getDescription()).append("\n");
                context.append("     错误: ").append(t.getError()).append("\n");
            }
        }
        context.append("\n失败原因: ").append(failureReason).append("\n");
        context.append("请基于已完成的进度，制定一个新的计划来完成剩余工作。");

        List<LLMModels.Message> messages = Arrays.asList(
                LLMModels.Message.system(PLANNING_PROMPT),
                LLMModels.Message.user(context.toString())
        );

        LLMModels.ChatResponse response = llmClient.chat(messages, null);
        return parsePlan(failedPlan.getGoal(), response.getContent());
    }

    // ══════════════════════════════════════════════════
    //  JSON 解析 → ExecutionPlan
    // ══════════════════════════════════════════════════

    private ExecutionPlan parsePlan(String goal, String rawJson) throws IOException {
        String cleaned = cleanJson(rawJson);
        JsonNode root = mapper.readTree(cleaned);

        String summary = root.has("summary") ? root.get("summary").asText() : goal;
        JsonNode tasksNode = root.get("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // ── 第一遍：创建所有 Task，建立 ID 映射 ──
        Map<String, String> idMapping = new LinkedHashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.get("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.get("description").asText();
            TaskType type = TaskType.valueOf(taskNode.get("type").asText().toUpperCase());

            Task task = new Task(newId, description, type);
            plan.addTask(task);
        }

        // ── 第二遍：处理依赖关系（用映射后的 ID） ──
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTasks().get(newId);

            JsonNode depsNode = taskNode.get("dependencies");
            if (depsNode != null && depsNode.isArray()) {
                for (JsonNode dep : depsNode) {
                    String mappedId = idMapping.get(dep.asText());
                    if (mappedId != null && plan.getTasks().containsKey(mappedId)) {
                        task.addDependency(mappedId);
                    }
                }
            }
        }

        // ── 建立反向依赖（dependents） ──
        for (Task task : plan.getTasks().values()) {
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTasks().get(depId);
                if (dep != null) {
                    dep.addDependent(task.getId());
                }
            }
        }

        // ── 拓扑排序 ──
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("任务依赖关系存在环，无法执行！");
        }

        return plan;
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    /** 清理 LLM 输出中可能包裹的 markdown 代码块 */
    private String cleanJson(String raw) {
        String s = raw.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        // 提取第一个 { 到最后一个 } 之间的内容
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    public DeepSeekClient getLlmClient() {
        return llmClient;
    }
}
