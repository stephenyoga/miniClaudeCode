package com.claudecode.memory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 短期记忆 —— Token 预算驱动 FIFO 淘汰，被淘汰的条目暂存待压缩。
 */
public class ConversationMemory implements Memory {

    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private final int maxTokens;
    private final AtomicInteger currentTokens = new AtomicInteger(0);
    private final List<MemoryEntry> compressedSummaries = new ArrayList<>();

    public ConversationMemory() { this(100_000); }
    public ConversationMemory(int maxTokens) { this.maxTokens = maxTokens; }

    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.id(), entry);
        currentTokens.addAndGet(entry.tokenCount());

        // 超出预算时自动淘汰最旧的条目
        while (currentTokens.get() > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            MemoryEntry oldest = it.next().getValue();
            it.remove();
            currentTokens.addAndGet(-oldest.tokenCount());
            compressedSummaries.add(oldest);
        }
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(e -> MemoryQueryTokenizer.matches(e.content(), tokens))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() { return List.copyOf(entries.values()); }

    @Override
    public void clear() {
        entries.clear();
        currentTokens.set(0);
    }

    @Override
    public double getUsageRatio() {
        return (double) currentTokens.get() / maxTokens;
    }

    public int tokenCount() { return currentTokens.get(); }
    public int maxTokens() { return maxTokens; }
    public List<MemoryEntry> getCompressedSummaries() { return List.copyOf(compressedSummaries); }
    public int entryCount() { return entries.size(); }

    public String getStatusSummary() {
        return String.format("短期记忆: %d条 / %d tokens (预算: %d, 使用率: %.0f%%, 已压缩: %d条)",
                entryCount(), tokenCount(), maxTokens(), getUsageRatio() * 100, compressedSummaries.size());
    }
}
