package com.claudecode.memory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 短期记忆 —— 存储当前会话中的对话内容和工具结果。
 *
 * 特点：
 * 1. FIFO 淘汰：超出 Token 预算时，自动删除最旧的条目
 * 2. 被淘汰的条目暂存到 compressedSummaries，等待 ContextCompressor 做 Map-Reduce 压缩
 * 3. 如果预算足够，保留全部记忆
 *
 * 预算默认 100,000 token。实际由 TokenBudget 计算可用空间，
 * ConversationMemory 只是自己的 maxTokens。
 *
 * 生命周期：
 * 1. Agent 调用 addUserMessage / addAssistantMessage → store()
 * 2. 超出预算 → evictOldest() → 旧条目移入 compressedSummaries
 * 3. MemoryManager.check() → ContextCompressor.compress(compressedSummaries)
 * 4. 压缩后的摘要 → store() 回去
 */
public class ConversationMemory implements Memory {

    /** LinkedHashMap 保证插入顺序（最早插入 = 最旧） */
    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private final int maxTokens;                    // Token 预算上限
    private final AtomicInteger currentTokens;       // 当前已使用的 Token 数
    /** 被淘汰的旧条目，等待 ContextCompressor 压缩 */
    private final List<MemoryEntry> compressedSummaries;

    public ConversationMemory() {
        this(100_000);
    }

    public ConversationMemory(int maxTokens) {
        this.maxTokens = maxTokens;
        this.currentTokens = new AtomicInteger(0);
        this.compressedSummaries = new ArrayList<>();
    }

    /**
     * 存储一条记忆。
     * 新条目加到队尾，如果超出预算则从队头淘汰最旧的。
     * 注意：淘汰的是最早的，不是"最不重要的"——FIFO 简单但有效。
     */
    @Override
    public synchronized void store(MemoryEntry entry) {
        entries.put(entry.id(), entry);
        currentTokens.addAndGet(entry.tokenCount());

        // 超出预算时淘汰最旧（LinkedHashMap 的第一个）
        while (currentTokens.get() > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    /** 淘汰最早的一条记忆，移入 compressedSummaries 等待压缩 */
    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            MemoryEntry oldest = it.next().getValue();
            it.remove();
            currentTokens.addAndGet(-oldest.tokenCount());
            compressedSummaries.add(oldest);
        }
    }

    /** 检索记忆（中文分词匹配） */
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
    public synchronized void clear() {
        entries.clear();
        currentTokens.set(0);
    }

    /** 当前使用率（0.0 ~ 1.0），用于判断是否需要触发压缩 */
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
