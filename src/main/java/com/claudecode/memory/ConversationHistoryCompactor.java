package com.claudecode.memory;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 对话历史压缩器 —— 在 LLM 调用前原地压缩 conversationHistory。
 *
 * 和 ContextCompressor 的区别：
 * - ContextCompressor 压缩的是 MemoryEntry（用于检索排序的短期记忆）
 * - 本类压缩的是 List<LLMModels.Message>（实际发送给 LLM API 的消息列表）
 *
 * 简单说：ContextCompressor 影响检索质量，本类影响实际 Token 消耗。
 * 两者缺一不可 —— 压缩了短期记忆不代表 API 消息也省了。
 *
 * 触发时机：每次调 LLM 之前（Agent.java 第 67 行和第 164 行）。
 * 算法：
 * 1. 估算当前消息列表的 token 数
 * 2. 超过可用预算 × 80% 则触发
 * 3. 找到所有 user 消息的索引，保留最近 3 轮
 * 4. 把更早的消息喂给 LLM 做摘要
 * 5. 原地替换为：[system] + [user(摘要)] + [assistant(ok)] + [保留的尾部]
 *
 * 关键约束：分割点必须落在 user message 边界，避免切断 tool_call / tool_result 的配对。
 */
public class ConversationHistoryCompactor {

    private static final Logger log = Logger.getLogger(ConversationHistoryCompactor.class.getName());
    private final DeepSeekClient llmClient;
    /** 保留最近几轮 user 消息不压缩（默认 3 轮） */
    private final int retainRecentRounds;
    /** 摘要输入的最大字符数，避免输入过长导致 LLM 调用费用过高 */
    private static final int MAX_SUMMARY_INPUT_CHARS = 40_000;

    private static final String SUMMARY_PROMPT = """
            请将以下对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经执行的关键操作和结果
            3. 已达成的重要决策或结论
            4. 尚未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 结束 ===
            """;

    public ConversationHistoryCompactor(DeepSeekClient llmClient) {
        this(llmClient, 3);
    }

    public ConversationHistoryCompactor(DeepSeekClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    /**
     * 评估并按需原地压缩 conversationHistory。
     *
     * @param history         Agent 主循环的对话历史列表，压缩后会原地替换
     * @param availableTokens 对话历史可用的 Token 预算
     * @return 是否执行了压缩
     */
    public boolean compactIfNeeded(List<LLMModels.Message> history, int availableTokens) {
        if (history == null || history.isEmpty()) return false;

        // 估算当前 token 数，未到阈值直接跳过
        int currentTokens = estimateTokens(history);
        int triggerThreshold = (int) (availableTokens * 0.8);
        if (currentTokens < triggerThreshold) return false;

        // 找到 system 之后所有 user 消息的索引
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }

        // 如果 user 消息太少，不值得压缩
        if (userIndices.size() <= retainRecentRounds) {
            log.fine("compactIfNeeded skip: only " + userIndices.size() + " user turns");
            return false;
        }

        // 分割点 = 倒数第 retainRecentRounds 个 user 消息的位置
        int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;

        // 取出 system 之后、分割点之前的旧消息（不包含 system）
        List<LLMModels.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        // 调 LLM 生成旧消息的摘要
        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (Exception e) {
            log.warning("对话摘要生成失败: " + e.getMessage());
            return false;
        }
        if (summary == null || summary.isBlank()) return false;

        // 原地重建消息列表：
        // [system] + [user(摘要)] + [assistant(已了解)] + [尾部保留的近期消息]
        List<LLMModels.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LLMModels.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LLMModels.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(new ArrayList<>(history.subList(splitIdx, history.size())));

        int afterTokens = estimateTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format("对话压缩完成: tokens %d -> %d, messages %d -> %d",
                currentTokens, afterTokens, userIndices.size() + systemEnd, rebuilt.size()));
        return true;
    }

    /**
     * 把旧消息列表转换为文本，调 LLM 生成摘要。
     * 每条消息格式："角色: 内容"，工具调用会额外标注 TOOL_CALL。
     */
    private String summarize(List<LLMModels.Message> messages) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (LLMModels.Message m : messages) {
            sb.append(m.role().toUpperCase()).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            List<LLMModels.ToolCall> toolCalls = m.toolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (LLMModels.ToolCall tc : toolCalls) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append("(").append(truncate(tc.function().arguments(), 150)).append(")");
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长已截断)\n");
                break;
            }
        }

        List<LLMModels.Message> req = List.of(
                LLMModels.Message.system("你是一个对话摘要助手，只输出摘要本身，不输出任何元描述。"),
                LLMModels.Message.user(String.format(SUMMARY_PROMPT, sb.toString()))
        );
        LLMModels.ChatResponse resp = llmClient.chat(req, null);
        return resp != null ? resp.getContent() : null;
    }

    /**
     * 估算消息列表的 Token 数。
     * 使用 MemoryEntry.estimateTokens 估算每条消息的内容，
     * 再加每条消息 4 token 的 role/separator 固定开销。
     */
    public static int estimateTokens(List<LLMModels.Message> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (LLMModels.Message msg : messages) {
            String content = msg.content();
            if (content != null) {
                total += MemoryEntry.estimateTokens(content);
            }
            List<LLMModels.ToolCall> toolCalls = msg.toolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (LLMModels.ToolCall tc : toolCalls) {
                    total += MemoryEntry.estimateTokens(tc.function().arguments());
                }
            }
        }
        total += messages.size() * 4;
        return total;
    }

    private static String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
