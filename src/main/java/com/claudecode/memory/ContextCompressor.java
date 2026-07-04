package com.claudecode.memory;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;

import java.util.*;

/**
 * 上下文压缩器——Map-Reduce 策略：
 * Map 阶段：旧消息每 5 条一组，每组独立调 LLM 生成摘要
 * Reduce 阶段：多片摘要合并为最终摘要
 *
 * retainRecentRounds：最近 3 轮消息不参与压缩，原样保留。
 */
public class ContextCompressor {

    private final DeepSeekClient llmClient;
    private final int retainRecentRounds = 3;
    private static final int CHUNK_SIZE = 5;

    private static final String SUMMARIZE_PROMPT = """
            请根据以下对话内容生成一段简洁的中文摘要，保留关键信息（如文件路径、项目名、用户偏好、技术决策）。
            不要添加原文没有的内容，摘要控制在 150 字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            从以下对话中提取关键事实，以 JSON 数组格式输出，每个元素包含 key 和 value。
            只提取用户偏好、项目配置、技术决策、文件路径这类跨会话有价值的信息。
            如果没有任何值得记录的，输出 []。

            示例格式：
            [{"key": "用户偏好", "value": "喜欢用 Java 21"}, {"key": "项目路径", "value": "D:/demo"}]
            """;

    // 临时性前缀：遇到这些开头直接丢弃（"我想/我要/帮我/新建/创建/删除..." 是一次性请求，不是事实）
    private static final List<String> EPHEMERAL_PREFIXES = List.of(
            "我想", "我要", "我需要", "帮我", "让我", "请帮我", "请你",
            "新建", "创建", "删除", "修改", "生成", "写一个", "做一个",
            "当前这轮", "本次任务", "接下来"
    );

    // 推测词：包含这些直接丢弃
    private static final List<String> SPECULATION_CUES = List.of(
            "可能", "应该", "猜测", "推测", "笔误", "提醒", "不确定"
    );

    // 耐久事实特征：必须包含以下至少一个关键词才算真事实
    private static final List<String> DURABLE_HINTS = List.of(
            "偏好", "习惯", "喜欢", "倾向",
            "项目", "路径", "技术栈", "版本",
            "模型", "接口", "配置", "环境变量", "默认",
            "JDK", "Java", "Python", "Go", "Rust",
            "用户", "学历", "身份", "学校", "专业"
    );

    public ContextCompressor(DeepSeekClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 压缩短期记忆：旧消息分片摘要 + 保留最近回合
     * @return 新 MemoryEntry 列表（摘要 + 近期消息）
     */
    public List<MemoryEntry> compress(List<MemoryEntry> allEntries) {
        if (allEntries.size() <= retainRecentRounds) return allEntries;

        int splitPoint = allEntries.size() - retainRecentRounds;
        List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));
        List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size()));

        // Map 阶段：分片摘要
        List<String> chunkSummaries = mapPhase(oldEntries);

        // Reduce 阶段：合并摘要
        String finalSummary = chunkSummaries.size() == 1
                ? chunkSummaries.get(0)
                : reducePhase(chunkSummaries);

        // 构建新列表：摘要 + 近期消息
        List<MemoryEntry> result = new ArrayList<>();
        result.add(new MemoryEntry(
                "[历史对话摘要] " + finalSummary,
                MemoryType.SUMMARY));
        result.addAll(recentEntries);
        return result;
    }

    /** 提取关键事实存入长期记忆（只从用户消息中提取，避免把 Agent 的建议当事实） */
    public void extractFacts(List<MemoryEntry> entries, LongTermMemory longTerm) {
        if (entries.isEmpty()) return;

        StringBuilder conversation = new StringBuilder();
        for (MemoryEntry e : entries) {
            String role = e.metadata().getOrDefault("role", "user");
            if (!"user".equals(role)) continue; // 只提取用户说的事实，过滤 assistant/tool
            conversation.append("user: ").append(truncate(e.content(), 200)).append("\n");
        }
        if (conversation.isEmpty()) return;

        try {
            List<LLMModels.Message> messages = Arrays.asList(
                    LLMModels.Message.system(EXTRACT_FACTS_PROMPT),
                    LLMModels.Message.user(conversation.toString()));
            LLMModels.ChatResponse resp = llmClient.chat(messages, null);
            String json = extractJsonArray(resp.getContent());
            if (json == null || json.length() < 5) return;

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String key = node.get("key").asText();
                String value = node.get("value").asText();
                if (!isPersistentFact(key, value)) continue;
                longTerm.store(new MemoryEntry(value, MemoryType.FACT, Map.of("key", key)));
            }
        } catch (Exception ignored) {}
    }

    /** 过滤临时性请求和推测，只保留跨会话成立的耐久事实 */
    private boolean isPersistentFact(String key, String value) {
        if (key == null || value == null || value.length() <= 3) return false;
        String lowerValue = value.toLowerCase(Locale.ROOT);
        String lowerKey = key.toLowerCase(Locale.ROOT);

        // 临时请求前缀
        for (String prefix : EPHEMERAL_PREFIXES) {
            if (lowerValue.startsWith(prefix.toLowerCase(Locale.ROOT))) return false;
        }
        // 推测词
        for (String cue : SPECULATION_CUES) {
            if (lowerValue.contains(cue.toLowerCase(Locale.ROOT))) return false;
        }
        // 耐久事实特征：key 或 value 中必须包含至少一个
        for (String hint : DURABLE_HINTS) {
            if (lowerKey.contains(hint.toLowerCase(Locale.ROOT))
                    || lowerValue.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ── Map-Reduce ──

    private List<String> mapPhase(List<MemoryEntry> entries) {
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, entries.size());
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < end; j++) {
                chunk.append("- ").append(entries.get(j).content()).append("\n");
            }
            try {
                List<LLMModels.Message> messages = Arrays.asList(
                        LLMModels.Message.system(SUMMARIZE_PROMPT),
                        LLMModels.Message.user(chunk.toString()));
                LLMModels.ChatResponse resp = llmClient.chat(messages, null);
                summaries.add(resp.getContent());
            } catch (Exception e) {
                summaries.add("[摘要生成失败]");
            }
        }
        return summaries;
    }

    private String reducePhase(List<String> chunkSummaries) {
        StringBuilder all = new StringBuilder();
        for (String s : chunkSummaries) {
            all.append("- ").append(s).append("\n");
        }
        try {
            List<LLMModels.Message> messages = Arrays.asList(
                    LLMModels.Message.system("将以下多个片段合并为一个连贯的中文摘要，不超过 200 字。"),
                    LLMModels.Message.user(all.toString()));
            LLMModels.ChatResponse resp = llmClient.chat(messages, null);
            return resp.getContent();
        } catch (Exception e) {
            return chunkSummaries.get(0);
        }
    }

    private String extractJsonArray(String content) {
        if (content == null) return null;
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) return content.substring(start, end + 1);
        return null;
    }

    private String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
