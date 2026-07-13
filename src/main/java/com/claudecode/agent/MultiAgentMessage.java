package com.claudecode.agent;

/**
 * Agent 间通信消息 —— 多 Agent 协作的基本通信单元
 */
public record MultiAgentMessage(
        String fromAgent,
        MultiAgentRole fromRole,
        String content,
        Type type
) {
    public enum Type {
        TASK, RESULT, FEEDBACK, APPROVAL, REJECTION, ERROR
    }

    public static MultiAgentMessage task(String fromAgent, String content) {
        return new MultiAgentMessage(fromAgent, null, content, Type.TASK);
    }

    public static MultiAgentMessage result(String fromAgent, MultiAgentRole role, String content) {
        return new MultiAgentMessage(fromAgent, role, content, Type.RESULT);
    }

    public static MultiAgentMessage error(String fromAgent, MultiAgentRole role, String content) {
        return new MultiAgentMessage(fromAgent, role, content, Type.ERROR);
    }
}
