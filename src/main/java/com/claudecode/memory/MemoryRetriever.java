package com.claudecode.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器——从短期和长期记忆中捞出相关条目，拼成上下文文本。
 *
 * 相关度评分：关键词匹配 + 时间衰减 + 来源加权（长期记忆 ×1.2）。
 */
public class MemoryRetriever {

    private final Memory shortTerm;
    private final Memory longTerm;

    public MemoryRetriever(Memory shortTerm, Memory longTerm) {
        this.shortTerm = shortTerm;
        this.longTerm = longTerm;
    }

    /** 检索并按相关度排序 */
    public List<MemoryEntry> retrieve(String query, int limit) {
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        List<ScoredEntry> scored = new ArrayList<>();

        for (MemoryEntry entry : shortTerm.getAll()) {
            double score = computeRelevanceScore(entry, tokens, 1.0);
            if (score > 0) scored.add(new ScoredEntry(entry, score));
        }
        for (MemoryEntry entry : longTerm.getAll()) {
            double score = computeRelevanceScore(entry, tokens, 1.2);
            if (score > 0) scored.add(new ScoredEntry(entry, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(limit).map(e -> e.entry).collect(Collectors.toList());
    }

    /**
     * 将检索结果格式化为上下文文本，注入到 system prompt。
     * 如果无相关记忆返回空字符串。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        List<MemoryEntry> results = retrieve(query, 10);
        if (results.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder("\n【参考记忆】\n");
        int used = 0;
        for (MemoryEntry e : results) {
            if (used + e.tokenCount() > maxTokens) break;
            ctx.append("- [").append(e.type()).append("] ").append(e.content()).append("\n");
            used += e.tokenCount();
        }
        return ctx.toString();
    }

    /**
     * 计算相关性分数：关键词匹配 + 时间衰减 + 来源加权
     */
    public double computeRelevanceScore(MemoryEntry entry, Set<String> queryTokens, double sourceWeight) {
        if (queryTokens == null || queryTokens.isEmpty()) return 0;
        String content = entry.content().toLowerCase(Locale.ROOT);
        double score = 0;

        for (String token : queryTokens) {
            if (content.contains(token)) {
                // token 越长权重越高
                score += (double) token.length() / Math.max(content.length(), 1) * 10;
            }
        }

        // 时间衰减：24 小时内从 1.0 线性衰减到 0.5，之后进一步降低
        long hours = Duration.between(entry.timestamp(), Instant.now()).toHours();
        double timeDecay = hours <= 24 ? 1.0 - hours * 0.5 / 24
                : Math.max(0.1, 0.5 - (hours - 24) * 0.01);

        return score * timeDecay * sourceWeight;
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
