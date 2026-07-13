package com.claudecode.agent;

/**
 * 多 Agent 角色定义 —— 规划-执行-审查 分工协作
 */
public enum MultiAgentRole {
    PLANNER("规划者", "分析任务并制定执行计划"),
    WORKER("执行者", "调用工具完成具体步骤"),
    REVIEWER("审查者", "检查执行结果的质量和正确性");

    private final String displayName;
    private final String description;

    MultiAgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
