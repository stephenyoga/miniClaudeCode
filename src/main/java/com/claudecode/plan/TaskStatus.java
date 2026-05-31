package com.claudecode.plan;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    /** 等待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 已完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 被跳过（依赖失败） */
    SKIPPED
}
