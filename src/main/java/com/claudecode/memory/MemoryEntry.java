package com.claudecode.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆的基本单元 —— 一条记忆包含内容、类型、时间戳和元数据。
 *
 * 短期记忆和长期记忆都用 MemoryEntry 表示，区别在于存储位置不同。
 *
 * tokenCount 在创建时自动估算：
 * - 中文字符 ≈ 1.5 字符/token
 * - 其他字符（英文、数字、符号）≈ 4 字符/token
 */
public class MemoryEntry {
    private final String id;
    private final String content;          // 记忆内容文本
    private final MemoryType type;          // 类型：CONVERSATION / FACT / SUMMARY / TOOL_RESULT
    private final Instant timestamp;        // 创建时间戳（用于时间衰减排序）
    private final Map<String, String> metadata;  // 元数据键值对（如 role=user, key=项目路径）
    private final int tokenCount;           // 估算的 token 数（用于预算控制）

    /** 创建一条记忆（自动生成 id 和时间戳） */
    public MemoryEntry(String content, MemoryType type) {
        this(UUID.randomUUID().toString().substring(0, 8), content, type, Instant.now(), Map.of());
    }

    /** 创建一条带元数据的记忆 */
    public MemoryEntry(String content, MemoryType type, Map<String, String> metadata) {
        this(UUID.randomUUID().toString().substring(0, 8), content, type, Instant.now(), metadata);
    }

    /** 完整构造函数（主要用于从 JSON 反序列化时恢复完整状态） */
    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp, Map<String, String> metadata) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
        this.metadata = new HashMap<>(metadata);
        this.tokenCount = estimateTokens(content);
    }

    /**
     * 估算文本的 token 数（用于 Token 预算控制）。
     * 中文按 1.5 字符/token，其他按 4 字符/token 估算。
     * 实际 token 数由模型的分词器决定，此方法只做粗略估计。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    // ── Getters ──
    public String id() { return id; }
    public String content() { return content; }
    public MemoryType type() { return type; }
    public Instant timestamp() { return timestamp; }
    public Map<String, String> metadata() { return Map.copyOf(metadata); }
    public int tokenCount() { return tokenCount; }
}
