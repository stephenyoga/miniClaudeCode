package com.claudecode.memory;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 对话历史压缩器 —— 在 LLM 调用前原地压缩 conversationHistory（List<LLMModels.Message>），
 * 防止长对话撑爆模型上下文窗口。
 *
 * 与 ContextCompressor 的区别：
 * - ContextCompressor 压缩 ConversationMemory（MemoryEntry，用于检索排序）
 * - 本类压缩 List<LLMModels.Message>（实际发送给 LLM API 的消息列表）
 *
 * 算法：
 * 1. 估算 conversationHistory 当前 token，未达阈值直接跳过
 * 2. 找出所有 user message 的索引，保留最近 retainRecentRounds 个 user 起算的尾部
 * 3. 把 system 之后、splitIdx 之前的旧消息喂给 LLM 摘要
 * 4. 原地重建：[system] + [user("已压缩摘要")] + [assistant("已了解上下文")] + [尾部]
 *
 * 分割点必须落在 user message 边界，避免切断 tool_call / tool_result 成对协议。
 */
public class ConversationHistoryCompactor {

    private static final Logger log = Logger.getLogger(ConversationHistoryCompactor.class.getName());
    private final DeepSeekClient llmClient;
    private final int retainRecentRounds;
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
     * @param history         Agent 主循环的对话历史，调用后可能被原地替换为更短列表
     * @param availableTokens 对话历史可用的 token 预算（通常由 TokenBudget.getAvailableForConversation() 提供）
     * @return 是否执行了压缩
     */
    public boolean compactIfNeeded(List<LLMModels.Message> history, int availableTokens) {
        if (history == null || history.isEmpty()) return false;

        int currentTokens = estimateTokens(history);
        int triggerThreshold = (int) (availableTokens * 0.8);
        if (currentTokens < triggerThreshold) return false;

        // 找 system 之后 user 消息的索引
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }

        if (userIndices.size() <= retainRecentRounds) {
            log.fine("compactIfNeeded skip: only " + userIndices.size() + " user turns, <= retain " + retainRecentRounds);
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;

        List<LLMModels.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (Exception e) {
            log.warning("对话摘要生成失败: " + e.getMessage());
            return false;
        }
        if (summary == null || summary.isBlank()) return false;

        // 原地重建
        List<LLMModels.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LLMModels.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LLMModels.Message.assistant("好的，我已了解之前的上下文，请继续。"));

        // 保留尾部近期消息
        rebuilt.addAll(new ArrayList<>(history.subList(splitIdx, history.size())));

        int afterTokens = estimateTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format("对话压缩完成: tokens %d -> %d, messages %d -> %d",
                currentTokens, afterTokens, userIndices.size() + systemEnd, rebuilt.size()));
        return true;
    }

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

    /** 估算消息列表的 token 数 */
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
        total += messages.size() * 4; // role/separator 固定开销
        return total;
    }

    private static String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
