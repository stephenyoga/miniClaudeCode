package com.claudecode.plan;

/**
 * 执行计划状态枚举
 */
public enum PlanStatus {
    /** 刚创建，还没开始执行 */
    CREATED,
    /** 正在执行中 */
    RUNNING,
    /** 所有任务都完成 */
    COMPLETED,
    /** 有任务失败 */
    FAILED,
    /** 被取消 */
    CANCELLED
}
