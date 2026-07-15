package com.claudecode.memory;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;

import java.util.*;

/**
 * 上下文压缩器 —— Map-Reduce 压缩短期记忆 + 过滤式事实提取。
 *
 * ── Map-Reduce 压缩 ──
 * 当短期记忆的 Token 使用率超过 80% 时，把旧消息压缩为摘要。
 *
 * Map 阶段：旧消息每 5 条分为一组，每组独立调 LLM 生成摘要
 * Reduce 阶段：多片摘要再合并成一段完整的总结
 *
 * 保留最近 3 轮消息原样不动（retainRecentRounds=3），只压缩更早的。
 * 压缩后的结果是一个 [摘要条目] + [最近 3 轮完整消息] 的列表。
 *
 * ── 事实提取 ──
 * 在对话结束时（/clear 或 /save），扫描短期记忆中的用户消息，
 * 调 LLM 提取跨会话有价值的信息（偏好、配置、版本等），
 * 经过三层过滤后存入长期记忆。
 */
public class ContextCompressor {

    private final DeepSeekClient llmClient;
    /** 保留最近 3 轮消息不压缩，保持实时性 */
    private final int retainRecentRounds = 3;
    /** Map 阶段的分片大小：每 5 条一组 */
    private static final int CHUNK_SIZE = 5;

    private static final String SUMMARIZE_PROMPT = """
            请根据以下对话内容生成一段简洁的中文摘要，保留关键信息（如文件路径、项目名、用户偏好、技术决策）。
            不要添加原文没有的内容，摘要控制在 150 字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            从以下对话中提取关键事实，以 JSON 数组格式输出，每个元素包含 key 和 value。
            只提取跨会话仍然成立的用户偏好、技术配置、版本选择、路径设定。
            不要提取：临时对话内容、一次性任务描述、用户刚才要求做的事、项目里临时写的东西。
            如果不确定，输出 []。

            示例格式：
            [{"key": "用户偏好", "value": "喜欢用 Java 21"}, {"key": "项目路径", "value": "D:/demo"}]
            """;

    // 三层输出过滤：

    // 1. 临时性前缀：用户说"帮我创建xxx"是一次性请求，不是事实
    private static final List<String> EPHEMERAL_PREFIXES = List.of(
            "我想", "我要", "我需要", "帮我", "让我", "请帮我", "请你",
            "新建", "创建", "删除", "修改", "生成", "写一个", "做一个",
            "当前这轮", "本次任务", "接下来"
    );

    // 2. 推测词：包含推测用语的不是确定事实
    private static final List<String> SPECULATION_CUES = List.of(
            "可能", "应该", "猜测", "推测", "笔误", "提醒", "不确定"
    );

    // 3. 耐久事实特征：value 中必须包含至少一个关键词才会被保留
    // 注意：不要包含"项目"这类宽泛词，临时任务也会有"项目"二字
    private static final List<String> DURABLE_HINTS = List.of(
            "偏好", "习惯", "喜欢", "倾向",
            "路径", "版本", "技术栈", "编程语言",
            "模型", "配置", "环境变量", "默认",
            "JDK", "Java", "Python", "Go",
            "用户", "学历", "身份", "学校", "专业",
            "框架"
    );

    public ContextCompressor(DeepSeekClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 压缩短期记忆：旧消息做 Map-Reduce 摘要 + 保留最近保留轮次的消息。
     *
     * 流程：
     * 1. 按 retainRecentRounds 分出新旧两部分
     * 2. 旧部分 → mapPhase() 每 5 条调一次 LLM 摘要
     * 3. 如果只有一片摘要，直接用；否则 reducePhase() 合并
     * 4. 返回 [摘要] + [最近 N 轮原样消息]
     *
     * @param allEntries 全部短期记忆条目
     * @return 压缩后的条目列表（摘要 + 近期消息）
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

        // 构建新列表：一条摘要 + 近期消息
        List<MemoryEntry> result = new ArrayList<>();
        result.add(new MemoryEntry(
                "[历史对话摘要] " + finalSummary,
                MemoryType.SUMMARY));
        result.addAll(recentEntries);
        return result;
    }

    /**
     * 提取关键事实存入长期记忆。
     * 只提取用户说的话（role="user"），避免把 Agent 的建议当事实。
     *
     * 提取后经过三层过滤：临时性前缀、推测词、耐久特征。
     * 最终留下的才存入 LongTermMemory。
     */
    public void extractFacts(List<MemoryEntry> entries, LongTermMemory longTerm) {
        if (entries.isEmpty()) return;

        // 只从用户消息中提取事实
        StringBuilder conversation = new StringBuilder();
        for (MemoryEntry e : entries) {
            String role = e.metadata().getOrDefault("role", "user");
            if (!"user".equals(role)) continue;
            conversation.append("user: ").append(truncate(e.content(), 200)).append("\n");
        }
        if (conversation.isEmpty()) return;

        try {
            // 调一次 LLM，从用户消息中提取 JSON 格式的事实
            List<LLMModels.Message> messages = Arrays.asList(
                    LLMModels.Message.system(EXTRACT_FACTS_PROMPT),
                    LLMModels.Message.user(conversation.toString()));
            LLMModels.ChatResponse resp = llmClient.chat(messages, null);
            String json = extractJsonArray(resp.getContent());
            if (json == null || json.length() < 5) return;

            // 解析 JSON，逐条过滤后存入长期记忆
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String key = node.get("key").asText();
                String value = node.get("value").asText();
                if (!isPersistentFact(key, value)) continue;
                longTerm.store(new MemoryEntry(value, MemoryType.FACT, Map.of("key", key)));
            }
        } catch (Exception e) {
            System.err.println("⚠️ 事实提取失败: " + e.getMessage());
        }
    }

    /**
     * 过滤临时性请求和推测，只保留跨会话成立的耐久事实。
     * 三层过滤全部通过才返回 true。
     */
    private boolean isPersistentFact(String key, String value) {
        if (key == null || value == null || value.length() <= 3) return false;
        String lowerValue = value.toLowerCase(Locale.ROOT);
        String lowerKey = key.toLowerCase(Locale.ROOT);

        // 第一层：临时请求前缀
        for (String prefix : EPHEMERAL_PREFIXES) {
            if (lowerValue.startsWith(prefix.toLowerCase(Locale.ROOT))) return false;
        }
        // 第二层：推测词
        for (String cue : SPECULATION_CUES) {
            if (lowerValue.contains(cue.toLowerCase(Locale.ROOT))) return false;
        }
        // 第三层：耐久事实特征，value 中必须包含至少一个关键词
        for (String hint : DURABLE_HINTS) {
            if (lowerKey.contains(hint.toLowerCase(Locale.ROOT))
                    || lowerValue.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════
    //  Map-Reduce 实现
    // ════════════════════════════════════════

    /** Map 阶段：把旧消息按 CHUNK_SIZE 分组，每组调一次 LLM 生成摘要 */
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

    /** Reduce 阶段：合并多个分片摘要为一段连贯总结 */
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

    /** 从 LLM 返回的文本中提取 JSON 数组部分（去除前后自然语言包装） */
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
