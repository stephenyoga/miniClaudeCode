package com.claudecode.plan;

import java.util.*;

/**
 * 执行计划 —— 包含一组任务及其 DAG 依赖关系。
 * 通过拓扑排序计算出线性执行顺序。
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;
    private final Map<String, Task> tasks;
    private final List<String> executionOrder;
    private PlanStatus status;
    private String summary;

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

    // ── 拓扑排序（DFS 检测环） ──

    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false; // 检测到环
                }
            }
        }

        // DFS 后序已是正确执行顺序（依赖先入列），无需反转
        return true;
    }

    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        if (visiting.contains(id)) return false; // 有环
        if (visited.contains(id)) return true;

        visiting.add(id);

        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) return false;
            }
        }

        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);
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

    /** 所有任务都已完成 */
    public boolean allCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == TaskStatus.COMPLETED
                        || t.getStatus() == TaskStatus.SKIPPED);
    }

    /** 获取当前待执行的任务列表 */
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

    /** 跳过依赖失败任务的所有下游任务 */
    public void skipDependentsOf(String failedTaskId) {
        for (Task task : tasks.values()) {
            if (task.getStatus() == TaskStatus.PENDING
                    && task.getDependencies().contains(failedTaskId)) {
                task.markSkipped();
                skipDependentsOf(task.getId()); // 递归
            }
        }
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public Map<String, Task> getTasks() { return tasks; }
    public List<String> getExecutionOrder() { return executionOrder; }

    /** 生成计划可视化文本 */
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
