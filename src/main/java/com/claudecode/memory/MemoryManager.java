package com.claudecode.memory;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MemoryManager —— 记忆系统门面，Agent 通过它与所有记忆子系统交互。
 *
 * 内部维护两条数据线：
 *
 * 1. conversation（List<LLMModels.Message>）
 *    - 实际发给 LLM API 的消息列表
 *    - 由 Agent.java 通过 storeMessage() 写入
 *    - 由 ConversationHistoryCompactor() 在调 LLM 前压缩
 *
 * 2. shortTerm（ConversationMemory）
 *    - MemoryEntry 列表，用于检索和事实提取
 *    - 由 addUserMessage/addAssistantMessage/addToolResult 写入
 *    - 由 ContextCompressor 在超预算时做 Map-Reduce 压缩
 *
 * 3. longTerm（LongTermMemory）
 *    - 跨会话持久化的事实
 *    - 由 extractFacts() 自动提取 或 /save 手动存储
 *
 * 两条数据线各自压缩，互不干扰。但都是靠 TokenBudget 算预算。
 */
public class MemoryManager {

    /** 完整对话消息列表（发给 LLM 用），索引 0 固定为 system prompt */
    private final List<LLMModels.Message> conversation = new ArrayList<>();
    /** 短期记忆（检索用） */
    private final ConversationMemory shortTerm = new ConversationMemory();
    /** 长期记忆（跨会话持久化） */
    private final LongTermMemory longTerm = new LongTermMemory();
    /** ContextCompressor：压缩 shortTerm 的 MemoryEntry */
    private final ContextCompressor compressor;
    /** ConversationHistoryCompactor：压缩 conversation 消息列表 */
    private final ConversationHistoryCompactor historyCompactor;
    /** MemoryRetriever：从 shortTerm + longTerm 检索相关记忆 */
    private final MemoryRetriever retriever = new MemoryRetriever(shortTerm, longTerm);
    /** TokenBudget：计算各模型的上下文窗口和压缩触发阈值 */
    private final TokenBudget budget;

    /** 原始 system prompt（不含注入的记忆上下文） */
    private String systemPrompt;

    public MemoryManager(DeepSeekClient llmClient) {
        this.compressor = new ContextCompressor(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.budget = new TokenBudget(llmClient.getModel());
    }

    // ══════════════════════════════════════════════════
    //  Agent 集成入口
    // ══════════════════════════════════════════════════

    /** 用户输入 → 存短期记忆（检索用） */
    public synchronized void addUserMessage(String userInput) {
        shortTerm.store(new MemoryEntry(userInput, MemoryType.CONVERSATION, Map.of("role", "user")));
    }

    /** 助手回复 → 存短期记忆 + 检查是否需要自动压缩 */
    public synchronized void addAssistantMessage(LLMModels.Message msg) {
        String content = msg.content() != null ? msg.content()
                : msg.toolCalls() != null ? "[工具调用]"
                : "";
        shortTerm.store(new MemoryEntry(content, MemoryType.CONVERSATION, Map.of("role", "assistant")));
        autoCompressIfNeeded();
    }

    /** 工具执行结果 → 存短期记忆 */
    public synchronized void addToolResult(String toolName, String args, String result) {
        String text = "工具[" + toolName + "] " + args + " → " + truncate(result, 200);
        shortTerm.store(new MemoryEntry(text, MemoryType.TOOL_RESULT, Map.of("tool", toolName)));
    }

    /** 记录一次 LLM 调用的 Token 消耗 */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        budget.recordUsage(inputTokens, outputTokens);
    }

    /** 检索相关记忆并构建上下文文本（用于注入 system prompt） */
    public String buildContextForQuery(String query, int maxTokens) {
        String ctx = retriever.buildContextForQuery(query, maxTokens);
        if (!ctx.isEmpty()) {
            ctx = "\n【相关记忆】" + ctx;
        }
        return ctx;
    }

    /** 从短期记忆中提取事实并存入长期记忆（/clear 或 /save 时触发） */
    public void extractAndSaveFacts() {
        compressor.extractFacts(shortTerm.getAll(), longTerm);
    }

    /** 手动存储一条事实到长期记忆（/save <事实> 直接存，不走 LLM） */
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(fact, MemoryType.FACT,
                Map.of("source", "manual", "key", inferKey(fact)));
        longTerm.store(entry);
    }

    /** 根据事实内容推断一个分类 key */
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
    //  对话上下文管理（操作内部 conversation 列表）
    // ══════════════════════════════════════════════════

    /** 设置 system prompt */
    public synchronized void setSystemMessage(String prompt) {
        this.systemPrompt = prompt;
        if (conversation.isEmpty()) {
            conversation.add(LLMModels.Message.system(prompt));
        } else {
            conversation.set(0, LLMModels.Message.system(prompt));
        }
    }

    /** 更新 system prompt（注入记忆上下文后的版本） */
    public synchronized void updateSystemPrompt(String enrichedPrompt) {
        if (conversation.isEmpty()) {
            conversation.add(LLMModels.Message.system(enrichedPrompt));
        } else {
            conversation.set(0, LLMModels.Message.system(enrichedPrompt));
        }
    }

    /** 获取原始 system prompt */
    public String getSystemPrompt() { return systemPrompt; }

    /** 调 LLM 前压缩对话历史（削减 Token） */
    public synchronized boolean compactConversationHistory() {
        return historyCompactor.compactIfNeeded(conversation, budget.getAvailableForConversation());
    }

    public int getAvailableConversationTokens() {
        return budget.getAvailableForConversation();
    }

    /** 获取完整对话上下文（返回副本，防止外部修改） */
    public synchronized List<LLMModels.Message> getConversationContext() {
        return new ArrayList<>(conversation);
    }

    /** 向对话历史追加消息 */
    public synchronized void storeMessage(LLMModels.Message msg) {
        conversation.add(msg);
    }

    /** 清空对话（先提取事实到长期记忆，再清空短期记忆和对话历史，保留 system prompt） */
    public synchronized void clearConversation() {
        extractAndSaveFacts();                      // 事实提取到长期记忆
        LLMModels.Message sys = conversation.isEmpty() ? null : conversation.get(0);
        conversation.clear();                       // 清空对话历史
        shortTerm.clear();                          // 清空短期记忆
        if (sys != null) conversation.add(sys);     // 保留 system prompt
    }

    // ══════════════════════════════════════════════════
    //  查询 & 统计
    // ══════════════════════════════════════════════════

    /** 检索相关记忆（默认 Top 10） */
    public List<MemoryEntry> retrieve(String query) {
        return retriever.retrieve(query, 10);
    }

    /** 获取记忆系统状态（用于 /memory 命令） */
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

    /** 检查短期记忆使用率，超 80% 则触发 Map-Reduce 压缩 */
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
