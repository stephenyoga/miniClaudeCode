package com.claudecode.plan;

/**
 * 任务状态枚举 —— 描述单个 Task 的生命周期。
 *
 * PENDING → RUNNING → COMPLETED  （正常流转）
 * PENDING → RUNNING → FAILED      （执行失败）
 * PENDING → SKIPPED                （依赖的任务失败，被级联跳过）
 */
public enum TaskStatus {
    PENDING,    // 等待执行
    RUNNING,    // 正在执行
    COMPLETED,  // 执行完成
    FAILED,     // 执行失败
    SKIPPED     // 被跳过（依赖的前置任务失败）
}
