package com.claudecode.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 记忆的基本单元 */
public class MemoryEntry {
    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public MemoryEntry(String content, MemoryType type) {
        this(UUID.randomUUID().toString().substring(0, 8), content, type, Instant.now(), Map.of());
    }

    public MemoryEntry(String content, MemoryType type, Map<String, String> metadata) {
        this(UUID.randomUUID().toString().substring(0, 8), content, type, Instant.now(), metadata);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp, Map<String, String> metadata) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
        this.metadata = new HashMap<>(metadata);
        this.tokenCount = estimateTokens(content);
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    public String id() { return id; }
    public String content() { return content; }
    public MemoryType type() { return type; }
    public Instant timestamp() { return timestamp; }
    public Map<String, String> metadata() { return Map.copyOf(metadata); }
    public int tokenCount() { return tokenCount; }
}
