package com.claudecode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 —— 跨会话持久化到磁盘，自动去重。
 * 存储路径优先级：环境变量 PAICLI_MEMORY_DIR > JVM 参数 -Dpaicli.memory.dir > 默认路径
 */
public class LongTermMemory implements Memory {

    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);
    private final File storageFile;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String DEFAULT_DIR = "memory_db";
    private static final String FILE_NAME = "long_term_memory.json";

    public LongTermMemory() {
        String dir = resolveStorageDir();
        this.storageFile = new File(dir, FILE_NAME);
        loadFromDisk();
    }

    private String resolveStorageDir() {
        String env = System.getenv("PAICLI_MEMORY_DIR");
        if (env != null && !env.isEmpty()) return env;
        String jvm = System.getProperty("paicli.memory.dir");
        if (jvm != null && !jvm.isEmpty()) return jvm;
        return DEFAULT_DIR;
    }

    @Override
    public void store(MemoryEntry entry) {
        // 相同 key 覆盖：如果 metadata 中有 key 且已有同 key 条目，删除旧条目
        String key = entry.metadata().get("key");
        if (key != null && !key.isEmpty()) {
            entries.values().removeIf(e -> {
                if (key.equals(e.metadata().get("key"))) {
                    tokenCounter.addAndGet(-e.tokenCount());
                    return true;
                }
                return false;
            });
        }
        // 内容完全相同跳过
        boolean exists = entries.values().stream()
                .anyMatch(e -> e.content().equals(entry.content()));
        if (exists) return;

        entries.put(entry.id(), entry);
        tokenCounter.addAndGet(entry.tokenCount());
        saveToDisk();
    }

    public void storeAll(List<MemoryEntry> batch) {
        for (MemoryEntry e : batch) store(e);
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(e -> {
                    if (MemoryQueryTokenizer.matches(e.content(), tokens)) return true;
                    return e.metadata().values().stream()
                            .anyMatch(v -> MemoryQueryTokenizer.matches(v, tokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() { return List.copyOf(entries.values()); }

    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public double getUsageRatio() {
        return 0; // 长期记忆不设预算上限
    }

    public int size() { return entries.size(); }
    public int tokenCount() { return tokenCounter.get(); }

    public String getStatusSummary() {
        Map<MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::type, Collectors.counting()));
        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d)",
                size(), tokenCount(),
                typeCounts.getOrDefault(MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryType.SUMMARY, 0L));
    }

    /** 按内容精确匹配查找 */
    public Optional<MemoryEntry> findByContent(String content) {
        return entries.values().stream()
                .filter(e -> e.content().equals(content))
                .findFirst();
    }

    // ── 持久化 ──

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;
        try {
            List<Map<String, Object>> raw = mapper.readValue(storageFile, List.class);
            // 后入覆盖：同 key 保留最新时间戳，清理历史积攒的脏数据
            Map<String, MemoryEntry> dedup = new LinkedHashMap<>();
            for (Map<String, Object> map : raw) {
                MemoryEntry e = deserialize(map);
                String key = e.metadata().get("key");
                if (key != null && !key.isEmpty()) {
                    MemoryEntry existing = dedup.values().stream()
                            .filter(v -> key.equals(v.metadata().get("key")))
                            .findFirst().orElse(null);
                    if (existing != null) {
                        if (e.timestamp().isAfter(existing.timestamp())) {
                            dedup.remove(existing.id());
                            dedup.put(e.id(), e);
                        }
                        continue;
                    }
                }
                dedup.put(e.id(), e);
            }
            entries.putAll(dedup);
            dedup.values().forEach(e -> tokenCounter.addAndGet(e.tokenCount()));
        } catch (Exception ignored) {}
    }

    private synchronized void saveToDisk() {
        try {
            storageFile.getParentFile().mkdirs();
            ArrayNode arr = mapper.createArrayNode();
            for (MemoryEntry e : entries.values()) {
                ObjectNode obj = arr.addObject();
                obj.put("id", e.id());
                obj.put("content", e.content());
                obj.put("type", e.type().name());
                obj.put("timestamp", e.timestamp().toString());
                obj.put("tokenCount", e.tokenCount());
                ObjectNode meta = obj.putObject("metadata");
                e.metadata().forEach(meta::put);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, arr);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry deserialize(Map<String, Object> map) {
        String id = (String) map.get("id");
        String content = (String) map.get("content");
        MemoryType type = MemoryType.valueOf((String) map.get("type"));
        Instant ts = Instant.parse((String) map.get("timestamp"));
        Map<String, String> meta = map.containsKey("metadata")
                ? (Map<String, String>) map.get("metadata") : Map.of();
        return new MemoryEntry(id, content, type, ts, meta);
    }
}
