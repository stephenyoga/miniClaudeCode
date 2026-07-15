package com.claudecode.plan;

import java.util.*;

/**
 * 执行计划 —— 包含一组任务及其 DAG 依赖关系。
 *
 * 通过拓扑排序（DFS + 环检测）计算出线性执行顺序。
 *
 * 场景示例：
 *   task_1(创建目录) → task_2(写pom.xml) → task_3(写代码)
 *                                            ↘ task_4(写测试) ← task_2
 *
 * 拓扑排序后：task_1 → task_2 → task_3 → task_4
 * 并行执行时 task_3 和 task_4 可以同时开工（都依赖 task_2）。
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;                    // 用户输入的原始目标
    private final Map<String, Task> tasks;        // all tasks by ID
    private final List<String> executionOrder;    // 拓扑排序后的执行顺序
    private PlanStatus status;
    private String summary;                       // LLM 生成的计划摘要

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    public void addTask(Task task) {
        tasks.put(task.getId(), task);
    }

    // ════════════════════════════════════════
    //  拓扑排序（DFS + 三色标记法检测环）
    // ════════════════════════════════════════

    /**
     * 计算拓扑排序执行顺序。
     *
     * 使用 DFS 后序遍历：递归访问依赖在前，自己在后。
     * 所以 executionOrder 中依赖先入列，最后一步在最前面——不对，
     * 实际是依赖先入列（先 add 到 executionOrder），
     * 但 PlanAndExecuteAgent 按 executionOrder 顺序扫描时，
     * 依赖在前意味着它们先被检查，先进入 executable 列表。
     *
     * 环检测：visiting 集合标记"正在访问中"的节点。
     * 如果 DFS 遇到一个正在访问中的节点，说明有环（A → B → C → A）。
     *
     * @return true=成功, false=检测到环
     */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();   // 已访问完成
        Set<String> visiting = new HashSet<>();   // 访问中（用于环检测）

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false; // 检测到环
                }
            }
        }

        return true;
    }

    /**
     * DFS 递归：先处理所有依赖（递归），再把自己加入 executionOrder。
     * 如果遇到 visiting 中的节点，说明有环。
     */
    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        if (visiting.contains(id)) return false; // 检测到环
        if (visited.contains(id)) return true;    // 已处理过

        visiting.add(id);

        // 递归处理所有依赖
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) return false;
            }
        }

        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);  // 依赖处理完后再把自己加入顺序
        return true;
    }

    // ── 计划生命周期 ──

    public void markStarted() { this.status = PlanStatus.RUNNING; }
    public void markCompleted() { this.status = PlanStatus.COMPLETED; }
    public void markFailed() { this.status = PlanStatus.FAILED; }

    /** 是否有任务失败 */
    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == TaskStatus.FAILED);
    }

    /** 所有任务都已完成或被跳过 */
    public boolean allCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.COMPLETED
                        || t.getStatus() == TaskStatus.SKIPPED);
    }

    /**
     * 获取当前可以执行的任务列表。
     * 按拓扑顺序扫描，状态为 PENDING 且所有依赖已完成的任务入列。
     */
    public List<Task> getNextExecutable() {
        List<Task> ready = new ArrayList<>();
        for (String taskId : executionOrder) {
            Task task = tasks.get(taskId);
            if (task.isExecutable(tasks)) {
                ready.add(task);
            }
        }
        return ready;
    }

    /**
     * 递归跳过某个失败任务的所有下游任务。
     * 例如 task_1 失败 → task_3（依赖 task_1）→ task_4（依赖 task_3）全部被标记 SKIPPED。
     */
    public void skipDependentsOf(String failedTaskId) {
        for (Task task : tasks.values()) {
            if (task.getStatus() == TaskStatus.PENDING
                    && task.getDependencies().contains(failedTaskId)) {
                task.markSkipped();
                skipDependentsOf(task.getId());
            }
        }
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public Map<String, Task> getTasks() { return tasks; }
    public List<String> getExecutionOrder() { return executionOrder; }

    /** 生成控制台可视化的计划文本 */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("══════════════════════════════════════\n");
        sb.append("📋 执行计划\n");
        sb.append("目标: ").append(goal).append("\n");
        if (summary != null) sb.append("摘要: ").append(summary).append("\n");
        sb.append("任务数: ").append(tasks.size()).append("\n");
        sb.append("执行顺序: ").append(executionOrder).append("\n");
        sb.append("──────────────────────────────────────\n");
        for (String taskId : executionOrder) {
            Task t = tasks.get(taskId);
            sb.append("  ").append(taskId).append(" [").append(t.getType()).append("]");
            if (!t.getDependencies().isEmpty()) {
                sb.append(" ← ").append(t.getDependencies());
            }
            sb.append("\n    ").append(t.getDescription()).append("\n");
        }
        sb.append("══════════════════════════════════════");
        return sb.toString();
    }

    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
