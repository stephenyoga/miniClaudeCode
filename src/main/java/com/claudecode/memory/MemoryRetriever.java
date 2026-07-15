package com.claudecode.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 —— 从短期和长期记忆中找出与用户问题最相关的记忆。
 *
 * 评分公式：关键词匹配分 × 时间衰减 × 来源权重
 *
 * 关键词匹配：查询 token 在记忆内容中出现的次数，token 越长权重越大
 * 时间衰减：24 小时内线性衰减到 0.5，之后缓慢降到最低 0.1
 * 来源加权：长期记忆 ×1.2（因为它经过了事实提取，质量更高）
 *
 * 结果用于注入到 system prompt 中，让 LLM 看到之前保存的事实和对话。
 */
public class MemoryRetriever {

    private final Memory shortTerm;  // 短期记忆（当前会话的对话）
    private final Memory longTerm;   // 长期记忆（跨会话的事实）

    public MemoryRetriever(Memory shortTerm, Memory longTerm) {
        this.shortTerm = shortTerm;
        this.longTerm = longTerm;
    }

    /**
     * 检索并返回按相关度从高到低排序的记忆列表。
     *
     * @param query 用户的当前输入
     * @param limit 最多返回多少条
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        List<ScoredEntry> scored = new ArrayList<>();

        // 从短期记忆中检索（来源权重 1.0）
        for (MemoryEntry entry : shortTerm.getAll()) {
            double score = computeRelevanceScore(entry, tokens, 1.0);
            if (score > 0) scored.add(new ScoredEntry(entry, score));
        }
        // 从长期记忆中检索（来源权重 1.2，质量更高）
        for (MemoryEntry entry : longTerm.getAll()) {
            double score = computeRelevanceScore(entry, tokens, 1.2);
            if (score > 0) scored.add(new ScoredEntry(entry, score));
        }

        // 按分数降序，取 Top limit
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(limit).map(e -> e.entry).collect(Collectors.toList());
    }

    /**
     * 将检索结果格式化为文本，注入到 system prompt 中。
     * LLM 收到 system prompt 时就能看到相关记忆。
     *
     * @param query     用户输入，用于检索
     * @param maxTokens 记忆文本的最大 Token 预算
     * @return 格式化的记忆文本，如 "\n【参考记忆】\n- [FACT] JDK 25\n"
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
     * 计算一条记忆的相关度分数。
     *
     * @param entry        待评分的记忆条目
     * @param queryTokens  用户查询的分词结果
     * @param sourceWeight 来源权重（短期 1.0，长期 1.2）
     * @return 综合分数，<= 0 表示不相关
     */
    public double computeRelevanceScore(MemoryEntry entry, Set<String> queryTokens, double sourceWeight) {
        if (queryTokens == null || queryTokens.isEmpty()) return 0;
        String content = entry.content().toLowerCase(Locale.ROOT);
        double score = 0;

        // 关键词匹配分：每个匹配的 token 贡献 token长度/内容长度 × 10
        for (String token : queryTokens) {
            if (content.contains(token)) {
                score += (double) token.length() / Math.max(content.length(), 1) * 10;
            }
        }

        // 时间衰减：越近的分数越高
        // 0 小时 → 1.0, 24 小时 → 0.5, 48 小时 → 0.26, 最低 0.1
        long hours = Duration.between(entry.timestamp(), Instant.now()).toHours();
        double timeDecay = hours <= 24
                ? 1.0 - hours * 0.5 / 24
                : Math.max(0.1, 0.5 - (hours - 24) * 0.01);

        return score * timeDecay * sourceWeight;
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
