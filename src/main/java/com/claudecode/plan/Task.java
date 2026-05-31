package com.claudecode.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务模型 —— 执行计划中的最小单元。
 * 支持依赖关系（DAG），记录执行时间和结果。
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private TaskStatus status;
    private String result;
    private String error;
    private final List<String> dependencies;
    private final List<String> dependents;
    private long startTime;
    private long endTime;

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    // ── 生命周期方法 ──

    /** 标记开始执行，记录开始时间 */
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /** 标记执行成功，记录结果和结束时间 */
    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    /** 标记执行失败，记录错误和结束时间 */
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    /** 标记被跳过（依赖任务失败导致） */
    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
    }

    // ── 依赖判断 ──

    /**
     * 检查所有依赖是否都已完成，是则该任务可以执行。
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /** 执行耗时（毫秒），未完成则返回 0 */
    public long getDuration() {
        if (startTime == 0) return 0;
        long end = endTime != 0 ? endTime : System.currentTimeMillis();
        return end - startTime;
    }

    // ── 依赖管理 ──

    public void addDependency(String taskId) {
        this.dependencies.add(taskId);
    }

    public void addDependent(String taskId) {
        this.dependents.add(taskId);
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return dependencies; }
    public List<String> getDependents() { return dependents; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
}
