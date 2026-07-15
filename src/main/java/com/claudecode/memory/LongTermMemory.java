package com.claudecode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 —— 跨会话持久化到磁盘 JSON 文件，重启后仍在。
 *
 * 存储路径优先级：
 * 1. 环境变量 MEMORY_DIR
 * 2. JVM 参数 -Dmemory.dir
 * 3. 默认项目根目录下的 memory_db/ 文件夹
 *
 * 特点：
 * - 自动去重（同 key 覆盖、内容完全相同跳过）
 * - 启动时从 JSON 文件加载全部记忆到内存
 * - 每次 store/clear 都会立即写盘
 * - 不设 Token 预算上限（getUsageRatio 返回 0）
 */
public class LongTermMemory implements Memory {

    /** LinkedHashMap 保证插入顺序，用于 key 覆盖时的顺序维护 */
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

    /** 按优先级解析存储目录 */
    private String resolveStorageDir() {
        String env = System.getenv("MEMORY_DIR");
        if (env != null && !env.isEmpty()) return env;
        String jvm = System.getProperty("memory.dir");
        if (jvm != null && !jvm.isEmpty()) return jvm;
        return DEFAULT_DIR;
    }

    /**
     * 存储一条记忆。
     * 先检查 key 覆盖：如果 metadata 中有 key 且已有同 key 条目，删除旧条目并修正 token 计数。
     * 再检查内容去重：完全相同的内容跳过。
     * 最后写入并持久化到磁盘。
     */
    @Override
    public synchronized void store(MemoryEntry entry) {
        // 1. 同 key 覆盖（如"JDK 版本偏好"第二次存时会覆盖第一次的）
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
        // 2. 内容完全相同跳过
        boolean exists = entries.values().stream()
                .anyMatch(e -> e.content().equals(entry.content()));
        if (exists) return;

        // 3. 存入
        entries.put(entry.id(), entry);
        tokenCounter.addAndGet(entry.tokenCount());
        saveToDisk();
    }

    /** 批量存储多条记忆 */
    public void storeAll(List<MemoryEntry> batch) {
        for (MemoryEntry e : batch) store(e);
    }

    /** 记忆检索：内容分词匹配 + metadata 值匹配 */
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
    public synchronized void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    /** 长期记忆不设预算上限 */
    @Override
    public double getUsageRatio() { return 0; }

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

    /** 按内容精确查找 */
    public Optional<MemoryEntry> findByContent(String content) {
        return entries.values().stream()
                .filter(e -> e.content().equals(content))
                .findFirst();
    }

    // ════════════════════════════════════════
    //  持久化：JSON 文件的读写
    // ════════════════════════════════════════

    /** 从 JSON 文件加载记忆到内存，启动时调用 */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;
        try {
            List<Map<String, Object>> raw = mapper.readValue(storageFile, List.class);
            // 后入覆盖：同 key 的记忆只保留最新时间戳的那条
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

    /** 将当前所有记忆写入 JSON 文件 */
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

    /** 从 JSON map 反序列化为 MemoryEntry */
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
