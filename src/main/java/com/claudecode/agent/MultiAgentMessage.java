package com.claudecode.agent;

/**
 * Agent 间通信消息 —— 多 Agent 协作的基本通信单元。
 *
 * 消息类型说明：
 * - TASK:      编排器分配给子 Agent 的任务
 * - RESULT:    子 Agent 返回的执行结果
 * - FEEDBACK:  审查者对结果的反馈（改进建议）
 * - APPROVAL:  审查者认可结果
 * - REJECTION: 审查者拒绝结果，需要重新执行
 * - ERROR:     子 Agent 执行过程中遇到的系统级错误
 */
public record MultiAgentMessage(
        String fromAgent,      // 发送方 Agent 名称（如 "planner", "worker-1"）
        MultiAgentRole fromRole, // 发送方角色
        String content,         // 消息内容
        Type type               // 消息类型
) {
    public enum Type {
        TASK, RESULT, FEEDBACK, APPROVAL, REJECTION, ERROR
    }

    /** 创建任务消息（编排器 → 子 Agent） */
    public static MultiAgentMessage task(String fromAgent, String content) {
        return new MultiAgentMessage(fromAgent, null, content, Type.TASK);
    }

    /** 创建结果消息（子 Agent → 编排器） */
    public static MultiAgentMessage result(String fromAgent, MultiAgentRole role, String content) {
        return new MultiAgentMessage(fromAgent, role, content, Type.RESULT);
    }

    /** 创建错误消息（子 Agent 遇到系统级错误） */
    public static MultiAgentMessage error(String fromAgent, MultiAgentRole role, String content) {
        return new MultiAgentMessage(fromAgent, role, content, Type.ERROR);
    }
}
