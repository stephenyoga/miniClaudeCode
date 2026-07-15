package com.claudecode.memory;

import java.util.List;

/**
 * 记忆接口 —— 短期记忆（ConversationMemory）和长期记忆（LongTermMemory）共用。
 *
 * 两种记忆都支持：存、搜、取全部、清空。
 * 区别在于：
 * - 短期记忆有 Token 预算限制，超预算时 FIFO 淘汰
 * - 长期记忆有 JSON 文件持久化，重启后还在
 */
public interface Memory {
    /** 存储一条记忆 */
    void store(MemoryEntry entry);

    /** 搜索与查询相关的记忆 */
    List<MemoryEntry> search(String query, int limit);

    /** 获取全部记忆 */
    List<MemoryEntry> getAll();

    /** 清空所有记忆 */
    void clear();

    /** 获取当前使用率（0.0 ~ 1.0），短期记忆用于判断是否需要淘汰 */
    double getUsageRatio();
}
