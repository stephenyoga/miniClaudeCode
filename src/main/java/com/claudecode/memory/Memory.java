package com.claudecode.memory;

import java.util.List;

/** 记忆接口——短期记忆和长期记忆共用 */
public interface Memory {
    void store(MemoryEntry entry);
    List<MemoryEntry> search(String query, int limit);
    List<MemoryEntry> getAll();
    void clear();
    double getUsageRatio();
}
