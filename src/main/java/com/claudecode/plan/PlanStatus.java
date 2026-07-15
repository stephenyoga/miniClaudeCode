package com.claudecode.plan;

/**
 * 执行计划状态枚举 —— 描述整个 ExecutionPlan 的生命周期。
 */
public enum PlanStatus {
    CREATED,    // 刚创建，还没开始执行
    RUNNING,    // 正在执行中
    COMPLETED,  // 所有任务都完成
    FAILED,     // 有任务执行失败
    CANCELLED   // 被取消
}
