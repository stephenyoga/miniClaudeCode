package com.claudecode.plan;

import com.claudecode.config.PromptAssembler;
import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

/**
 * 规划器 —— 将用户需求拆解为可执行的 DAG 计划。
 *
 * 支持两种规划模式：
 *
 * 1. 单层规划（createPlan）
 *    一次 LLM 调用直接输出完整计划 JSON，适合 3-8 步的任务。
 *
 * 2. 分层规划（createPlanHierarchical）
 *    两次 LLM 调用：先定宏观阶段 → 再逐阶段细化子任务。
 *    适合超过 10 步的大型任务，避免单次 token 溢出。
 *
 * LLM 输出 JSON 后，parsePlan 负责：
 * - 清理 markdown 包装
 * - 提取 JSON 中的 tasks 数组
 * - 重编号 task ID
 * - 建立 dependencies/dependents 双向依赖
 * - 拓扑排序检测环
 */
public class Planner {

    private final DeepSeekClient llmClient;
    private final ObjectMapper mapper;

    // 提示词从外部 .md 文件加载（改提示词不需要重新编译）
    private static final String PHASE_PROMPT = PromptAssembler.load("modes/planner-phase.md");
    private static final String PHASE_DETAIL_PROMPT = PromptAssembler.load("modes/planner-phase-detail.md");
    private static final String PLANNING_PROMPT = PromptAssembler.load("modes/planner.md");

    public Planner(DeepSeekClient llmClient) {
        this.llmClient = llmClient;
        this.mapper = new ObjectMapper();
    }

    public Planner(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    // ══════════════════════════════════════════════════
    //  单层规划
    // ══════════════════════════════════════════════════

    /**
     * 单层规划：一次 LLM 调用生成完整计划。
     * @param goal 用户需求
     * @return 解析后的 ExecutionPlan
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        List<LLMModels.Message> messages = Arrays.asList(
                LLMModels.Message.system(PLANNING_PROMPT),
                LLMModels.Message.user("请为以下任务制定执行计划：\n" + goal)
        );

        LLMModels.ChatResponse response = llmClient.chat(messages, null);
        return parsePlan(goal, response.getContent());
    }

    // ══════════════════════════════════════════════════
    //  分层规划
    // ══════════════════════════════════════════════════

    /**
     * 分层规划：先定宏观阶段 → 再逐阶段细化子任务。
     *
     * 流程：
     * 1. 第一层 LLM 调用 → 输出阶段列表（2-4 个 phase）
     * 2. 逐阶段调用 LLM → 每个阶段输出自己的子任务
     * 3. 阶段间门控：phase_2 的第一个 task 依赖 phase_1 的全部 task
     * 4. 拓扑排序检测环
     */
    public ExecutionPlan createPlanHierarchical(String goal) throws IOException {
        System.out.println("  📌 第一层：宏观规划 —— 确定执行阶段...");

        // 第一层：获取宏观阶段
        List<LLMModels.Message> phaseMessages = Arrays.asList(
                LLMModels.Message.system(PHASE_PROMPT),
                LLMModels.Message.user("请为以下任务制定执行阶段：\n" + goal)
        );
        LLMModels.ChatResponse phaseResponse = llmClient.chat(phaseMessages, null);
        String phaseJson = cleanJson(phaseResponse.getContent());
        JsonNode phaseRoot = mapper.readTree(phaseJson);
        String summary = phaseRoot.has("summary") ? phaseRoot.get("summary").asText() : goal;

        List<Phase> phases = new ArrayList<>();
        for (JsonNode pn : phaseRoot.get("phases")) {
            phases.add(new Phase(pn.get("id").asText(), pn.get("description").asText()));
        }
        System.out.println("  📌 阶段划分: " + phases.stream().map(Phase::description).toList());

        // 第二层：逐阶段细化子任务
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        int globalIndex = 1;
        List<String> prevPhaseTaskIds = new ArrayList<>();

        for (int pi = 0; pi < phases.size(); pi++) {
            Phase phase = phases.get(pi);
            System.out.println("  📌 第二层：细化阶段 [" + phase.description() + "]...");

            StringBuilder detailPrompt = new StringBuilder();
            detailPrompt.append("逐阶段细化——当前阶段：").append(phase.description()).append("\n\n");
            detailPrompt.append("已完成阶段：\n");
            for (int j = 0; j < pi; j++) {
                detailPrompt.append("  ✅ ").append(phases.get(j).description()).append("\n");
            }
            detailPrompt.append("\n细化当前阶段的子任务。若该阶段内存在先后依赖，请标注。");

            List<LLMModels.Message> detailMessages = Arrays.asList(
                    LLMModels.Message.system(PHASE_DETAIL_PROMPT),
                    LLMModels.Message.user(detailPrompt.toString())
            );
            LLMModels.ChatResponse detailResponse = llmClient.chat(detailMessages, null);
            String detailJson = cleanJson(detailResponse.getContent());
            JsonNode detailRoot = mapper.readTree(detailJson);

            List<String> currentPhaseTaskIds = new ArrayList<>();
            Map<String, String> phaseIdMap = new LinkedHashMap<>();

            // 第一遍：创建任务（重新编号，避免和全局 ID 冲突）
            for (JsonNode tn : detailRoot.get("tasks")) {
                String origId = tn.get("id").asText();
                String newId = "task_" + globalIndex;
                phaseIdMap.put(origId, newId);
                currentPhaseTaskIds.add(newId);
                globalIndex++;
            }

            // 第二遍：构建任务对象并设置依赖
            for (int ti = 0; ti < detailRoot.get("tasks").size(); ti++) {
                JsonNode tn = detailRoot.get("tasks").get(ti);
                String newId = currentPhaseTaskIds.get(ti);
                String desc = tn.get("description").asText();
                TaskType type = TaskType.valueOf(tn.get("type").asText().toUpperCase());
                Task task = new Task(newId, desc, type);

                // 阶段门依赖：当前阶段第一个任务依赖上一阶段全部
                if (ti == 0 && !prevPhaseTaskIds.isEmpty()) {
                    prevPhaseTaskIds.forEach(task::addDependency);
                }

                // 本阶段内部依赖
                if (tn.has("dependencies")) {
                    for (JsonNode dep : tn.get("dependencies")) {
                        String mapped = phaseIdMap.get(dep.asText());
                        if (mapped != null && currentPhaseTaskIds.contains(mapped)) {
                            task.addDependency(mapped);
                        }
                    }
                }

                plan.addTask(task);
            }

            prevPhaseTaskIds = currentPhaseTaskIds;
        }

        // 建立反向依赖
        for (Task task : plan.getTasks().values()) {
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTasks().get(depId);
                if (dep != null) dep.addDependent(task.getId());
            }
        }

        // 拓扑排序
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("任务依赖关系存在环，无法执行！");
        }

        return plan;
    }

    // ══════════════════════════════════════════════════
    //  重新规划（基于失败进度）
    // ══════════════════════════════════════════════════

    /**
     * 在任务失败后重新规划剩余部分。
     * 将已完成的进度和失败原因发给 LLM，让它生成一份补充计划。
     */
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

    /**
     * 解析 LLM 返回的 JSON 计划文本，构建 ExecutionPlan。
     *
     * 处理流程：
     * 1. cleanJson() 去掉 markdown 代码块标记和前后自然语言
     * 2. 解析 JSON → 读取 tasks 数组
     * 3. 第一遍：创建所有 Task，LLM 输出的原始 ID 映射为 task_1, task_2...
     * 4. 第二遍：用映射后的 ID 建立依赖关系
     * 5. 建立反向依赖（dependents）
     * 6. 拓扑排序（检测环）
     */
    private ExecutionPlan parsePlan(String goal, String rawJson) throws IOException {
        String cleaned = cleanJson(rawJson);
        JsonNode root = mapper.readTree(cleaned);

        String summary = root.has("summary") ? root.get("summary").asText() : goal;
        JsonNode tasksNode = root.get("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // 第一遍：创建所有 Task，建立 ID 映射
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

        // 第二遍：处理依赖关系（用映射后的 ID）
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

        // 建立反向依赖（dependents）
        for (Task task : plan.getTasks().values()) {
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTasks().get(depId);
                if (dep != null) {
                    dep.addDependent(task.getId());
                }
            }
        }

        // 拓扑排序
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("任务依赖关系存在环，无法执行！");
        }

        return plan;
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    /**
     * 清理 LLM 输出中可能包裹的 markdown 代码块和前后自然语言。
     * 先去掉 ```json 和 ```，再提取第一个 { 到最后一个 } 之间的内容。
     */
    private String cleanJson(String raw) {
        String s = raw.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
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
}
