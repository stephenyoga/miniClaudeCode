package com.claudecode.memory;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MemoryManager —— 记忆系统门面。
 *
 * Agent 调用 addUserMessage → 自动存短期记忆 + 检索长期记忆注入 system prompt
 * Agent 调用 addAssistantMessage → 自动存
 * 使用率 > 80% → 自动 Map-Reduce 压缩
 * /clear → 先提取事实到长期记忆，再清空
 */
public class MemoryManager {

    private final List<LLMModels.Message> conversation = new ArrayList<>();
    private final ConversationMemory shortTerm = new ConversationMemory();
    private final LongTermMemory longTerm = new LongTermMemory();
    private final ContextCompressor compressor;
    private final ConversationHistoryCompactor historyCompactor;
    private final MemoryRetriever retriever = new MemoryRetriever(shortTerm, longTerm);
    private final TokenBudget budget;

    private String systemPrompt;

    public MemoryManager(DeepSeekClient llmClient) {
        this.compressor = new ContextCompressor(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.budget = new TokenBudget(llmClient.getModel());
    }

    // ══════════════════════════════════════════════════
    //  Agent 集成入口
    // ══════════════════════════════════════════════════

    /** 用户输入：存短期记忆 + 检索长期记忆注入 system prompt */
    public synchronized void addUserMessage(String userInput) {
        shortTerm.store(new MemoryEntry(userInput, MemoryType.CONVERSATION, Map.of("role", "user")));
    }

    /** 助手回复：存短期记忆 + 记录 Token 消耗 */
    public synchronized void addAssistantMessage(LLMModels.Message msg) {
        String content = msg.content() != null ? msg.content()
                : msg.toolCalls() != null ? "[工具调用]"
                : "";
        shortTerm.store(new MemoryEntry(content, MemoryType.CONVERSATION, Map.of("role", "assistant")));
        autoCompressIfNeeded();
    }

    /** 工具执行结果：存短期记忆 */
    public synchronized void addToolResult(String toolName, String args, String result) {
        String text = "工具[" + toolName + "] " + args + " → " + truncate(result, 200);
        shortTerm.store(new MemoryEntry(text, MemoryType.TOOL_RESULT, Map.of("tool", toolName)));
    }

    /** 记录一次 LLM 调用消耗 */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        budget.recordUsage(inputTokens, outputTokens);
    }

    /** 检索相关长期记忆并构建上下文文本 */
    public String buildContextForQuery(String query, int maxTokens) {
        String ctx = retriever.buildContextForQuery(query, maxTokens);
        if (!ctx.isEmpty()) {
            ctx = "\n【相关记忆】" + ctx;
        }
        return ctx;
    }

    /** 提取事实到长期记忆（自动，对话结束时调用） */
    public void extractAndSaveFacts() {
        compressor.extractFacts(shortTerm.getAll(), longTerm);
    }

    /** 手动存储一条事实到长期记忆（/save 命令直接存，不走 LLM） */
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(fact, MemoryType.FACT,
                Map.of("source", "manual", "key", inferKey(fact)));
        longTerm.store(entry);
    }

    /** 根据事实内容推断一个简短的 key */
    private String inferKey(String fact) {
        String lower = fact.toLowerCase();
        if (lower.contains("jdk") || lower.contains("java")) return "JDK 版本偏好";
        if (lower.contains("python")) return "Python 偏好";
        if (lower.contains("路径") || lower.contains("项目")) return "项目路径";
        if (lower.contains("学校") || lower.contains("大学") || lower.contains("学历")) return "用户身份";
        if (lower.contains("默认") || lower.contains("偏好")) return "用户偏好";
        return "用户偏好";
    }

    // ══════════════════════════════════════════════════
    //  对话上下文管理
    // ══════════════════════════════════════════════════

    /** 注入系统提示 */
    public synchronized void setSystemMessage(String prompt) {
        this.systemPrompt = prompt;
        if (conversation.isEmpty()) {
            conversation.add(LLMModels.Message.system(prompt));
        } else {
            conversation.set(0, LLMModels.Message.system(prompt));
        }
    }

    /** 更新 system prompt（追加记忆上下文） */
    public synchronized void updateSystemPrompt(String enrichedPrompt) {
        if (conversation.isEmpty()) {
            conversation.add(LLMModels.Message.system(enrichedPrompt));
        } else {
            conversation.set(0, LLMModels.Message.system(enrichedPrompt));
        }
    }

    /** 获取原始 system prompt（不含注入的记忆） */
    public String getSystemPrompt() { return systemPrompt; }

    /** 压缩对话历史（原地压缩内部 conversation 列表，用于 LLM 调用前削减 token） */
    public synchronized boolean compactConversationHistory() {
        return historyCompactor.compactIfNeeded(conversation, budget.getAvailableForConversation());
    }

    /** 获取对话历史可用 Token 预算 */
    public int getAvailableConversationTokens() {
        return budget.getAvailableForConversation();
    }

    /** 获取完整对话上下文 */
    public synchronized List<LLMModels.Message> getConversationContext() {
        return new ArrayList<>(conversation);
    }

    /** 添加用户消息到对话上下文（原始的） */
    public synchronized void storeMessage(LLMModels.Message msg) {
        conversation.add(msg);
    }

    /** 清空对话（先提取事实到长期记忆，再清空） */
    public synchronized void clearConversation() {
        extractAndSaveFacts();
        LLMModels.Message sys = conversation.isEmpty() ? null : conversation.get(0);
        conversation.clear();
        shortTerm.clear();
        if (sys != null) conversation.add(sys);
    }

    // ══════════════════════════════════════════════════
    //  查询 & 统计
    // ══════════════════════════════════════════════════

    public List<MemoryEntry> retrieve(String query) {
        return retriever.retrieve(query, 10);
    }

    /** 获取记忆系统的整体状态 */
    public String getSystemStatus() {
        return "  " + shortTerm.getStatusSummary() + "\n" +
               "  " + longTerm.getStatusSummary() + "\n" +
               "  " + budget.getUsageReport();
    }

    public int longTermSize() { return longTerm.size(); }
    public int conversationSize() { return conversation.size(); }
    public double usageRate() { return shortTerm.getUsageRatio(); }
    public String getUsageReport() { return budget.getUsageReport(); }

    // ══════════════════════════════════════════════════
    //  内部
    // ══════════════════════════════════════════════════

    private void autoCompressIfNeeded() {
        if (!budget.needsCompression(shortTerm.tokenCount())) return;

        List<MemoryEntry> all = shortTerm.getAll();
        List<MemoryEntry> compressed = compressor.compress(all);

        shortTerm.clear();
        for (MemoryEntry e : compressed) {
            shortTerm.store(e);
        }
    }

    private String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
