package com.claudecode.memory;

/**
 * 记忆类型枚举。
 *
 * CONVERSATION: 普通对话内容（用户输入 / 助手回复），用于短期记忆
 * FACT:         提取出的关键事实，用于长期记忆跨会话持久化
 * SUMMARY:      Map-Reduce 压缩产生的摘要，替代被淘汰的原始对话
 * TOOL_RESULT:  工具执行结果，用于检索时知道之前调过哪些工具
 */
public enum MemoryType {
    CONVERSATION,
    FACT,
    SUMMARY,
    TOOL_RESULT
}
