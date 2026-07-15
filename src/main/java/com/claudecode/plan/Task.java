package com.claudecode.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务模型 —— DAG 执行计划中的最小单元。
 *
 * 每个 Task 有：
 * - 唯一 ID（如 "task_1"）
 * - 描述（LLM 生成的步骤说明）
 * - 类型（FILE_READ / FILE_WRITE / COMMAND / ANALYSIS / VERIFICATION / PLANNING）
 * - 状态（PENDING → RUNNING → COMPLETED / FAILED / SKIPPED）
 * - 依赖列表（dependencies）和反向依赖列表（dependents）
 * - 执行结果和错误信息
 * - 执行起止时间
 *
 * 依赖关系构成 DAG（有向无环图）：
 * - dependencies：此任务依赖哪些前置任务完成
 * - dependents：哪些后续任务依赖此任务
 * - 一个任务的 dependencies 全部 COMPLETED 后才能执行
 */
public class Task {
    private final String id;                // 任务 ID: "task_1", "task_2"...
    private final String description;       // LLM 生成的步骤描述
    private final TaskType type;            // 任务类型
    private TaskStatus status;              // 当前状态
    private String result;                  // 执行结果文本
    private String error;                   // 错误信息
    private final List<String> dependencies; // 依赖的前置任务 ID 列表
    private final List<String> dependents;   // 依赖此任务的后继任务 ID 列表
    private long startTime;                 // 开始执行的时间戳
    private long endTime;                   // 执行完成/失败的时间戳

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    // ── 生命周期方法 ──

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
    }

    // ── 依赖判断 ──

    /**
     * 检查此任务是否可以执行。
     * 条件：状态为 PENDING，且所有依赖的任务都已 COMPLETED。
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false; // 有依赖未完成
            }
        }
        return true;
    }

    /** 执行耗时（毫秒），未开始则返回 0 */
    public long getDuration() {
        if (startTime == 0) return 0;
        long end = endTime != 0 ? endTime : System.currentTimeMillis();
        return end - startTime;
    }

    // ── 依赖管理 ──

    public void addDependency(String taskId) { this.dependencies.add(taskId); }
    public void addDependent(String taskId) { this.dependents.add(taskId); }

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
