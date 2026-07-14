package com.claudecode.agent;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.claudecode.llm.StreamCallback;
import com.claudecode.memory.MemoryManager;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct Agent —— 即时推理与执行。
 * 集成 MemoryManager：自动检索长期记忆注入 system prompt，工具结果存入短期记忆。
 */
public class Agent {

    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager mm;
    private final ObjectMapper objectMapper;

    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_RETRIES = 3;

    private int totalPromptTokens = 0;
    private int totalCompletionTokens = 0;
    private String originalSystemPrompt;

    public Agent(DeepSeekClient llmClient, MemoryManager mm) {
        this.llmClient = llmClient;
        this.mm = mm;
        this.toolRegistry = new ToolRegistry();
        this.objectMapper = new ObjectMapper();
    }

    public Agent(DeepSeekClient llmClient) {
        this(llmClient, new MemoryManager(llmClient));
    }

    public Agent(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    // ══════════════════════════════════════════════════
    //  ReAct（非流式）
    // ══════════════════════════════════════════════════

    public String run(String userInput) {
        mm.addUserMessage(userInput);
        injectMemoryToSystemPrompt(userInput);
        mm.storeMessage(LLMModels.Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            int retryCount = 0;
            LLMModels.ChatResponse response = null;

            while (retryCount < MAX_RETRIES) {
                try {
                    mm.compactConversationHistory();
                    response = llmClient.chat(mm.getConversationContext(), getToolDefinitions());
                    break;
                } catch (IOException e) {
                    retryCount++;
                    if (retryCount >= MAX_RETRIES) return "网络错误: " + e.getMessage();
                } catch (Exception e) {
                    return "执行错误: " + e.getMessage();
                }
            }

            if (response == null) return "LLM调用失败";
            recordTokenUsage(response);
            mm.recordTokenUsage(
                    response.usage() != null ? response.usage().promptTokens() : 0,
                    response.usage() != null ? response.usage().completionTokens() : 0);

            if (response.hasToolCalls()) {
                String rc = response.getFirstMessage() != null
                        ? response.getFirstMessage().reasoningContent() : null;
                mm.storeMessage(LLMModels.Message.assistantWithToolCall(response.getToolCalls(), rc));

                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    Map<String, String> args = parseArguments(toolCall.function().arguments());
                    String result = toolRegistry.executeTool(toolCall.function().name(), args);
                    printToolExecution(toolCall.function().name(), args, result);
                    mm.storeMessage(LLMModels.Message.tool(result, toolCall.id()));
                    mm.addToolResult(toolCall.function().name(), args.toString(), result);
                }
                continue;
            } else {
                String content = response.getContent();
                LLMModels.Message firstMessage = response.getFirstMessage();
                String reasoningContent = firstMessage != null ? firstMessage.reasoningContent() : null;
                mm.storeMessage(LLMModels.Message.assistant(content));
                mm.addAssistantMessage(LLMModels.Message.assistant(content));

                StringBuilder result = new StringBuilder();
                if (reasoningContent != null && !reasoningContent.isEmpty()) {
                    result.append("\n🧠 思考过程\n─────────────────────────\n");
                    String[] lines = reasoningContent.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        result.append(i == 0 ? "│  💭 " : "│    ").append(lines[i]).append("\n");
                    }
                    result.append("─────────────────────────\n\n");
                }
                result.append(content).append("\n");
                result.append(getTokenSummary());
                return result.toString();
            }
        }
        return "达到最大迭代次数限制";
    }

    // ══════════════════════════════════════════════════
    //  ReAct（流式）
    // ══════════════════════════════════════════════════

    public void runStream(String userInput) {
        mm.addUserMessage(userInput);
        injectMemoryToSystemPrompt(userInput);
        mm.storeMessage(LLMModels.Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            StringBuilder reasoningBuf = new StringBuilder();
            StringBuilder contentBuf = new StringBuilder();
            boolean[] thinkingHeaderShown = {false};
            boolean[] thinkingEnded = {false};

            StreamCallback callback = new StreamCallback() {
                public void onThinkingChunk(String delta) {
                    if (!thinkingHeaderShown[0]) {
                        System.out.print("🧠 思考过程\n─────────────────────────\n│  💭 ");
                        thinkingHeaderShown[0] = true;
                    }
                    System.out.print(delta);
                    reasoningBuf.append(delta);
                }
                public void onContentChunk(String delta) {
                    if (thinkingHeaderShown[0] && !thinkingEnded[0]) {
                        System.out.print("\n─────────────────────────\n\n");
                        thinkingEnded[0] = true;
                    }
                    System.out.print(delta);
                    contentBuf.append(delta);
                }
                public void onComplete(LLMModels.ChatResponse response) {}
                public void onError(Exception e) {
                    System.err.println("流式错误: " + e.getMessage());
                }
            };

            LLMModels.ChatResponse response;
            try {
                mm.compactConversationHistory();
                response = llmClient.chatStream(mm.getConversationContext(), getToolDefinitions(), callback);
            } catch (Exception e) {
                System.err.println("请求失败: " + e.getMessage());
                return;
            }

            if (response == null) continue;
            recordTokenUsage(response);
            mm.recordTokenUsage(
                    response.usage() != null ? response.usage().promptTokens() : 0,
                    response.usage() != null ? response.usage().completionTokens() : 0);

            if (response.hasToolCalls()) {
                String rc = response.getFirstMessage() != null
                        ? response.getFirstMessage().reasoningContent() : null;
                mm.storeMessage(LLMModels.Message.assistantWithToolCall(response.getToolCalls(), rc));

                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    Map<String, String> args = parseArguments(toolCall.function().arguments());
                    String result = toolRegistry.executeTool(toolCall.function().name(), args);
                    mm.storeMessage(LLMModels.Message.tool(result, toolCall.id()));
                    mm.addToolResult(toolCall.function().name(), args.toString(), result);
                    printToolExecution(toolCall.function().name(), args, result);
                }
                continue;
            } else {
                String content = contentBuf.toString();
                // 流式模式下 content 可能为空（只有 reasoning_content），用 reasoning 兜底
                if (content.isEmpty()) {
                    content = reasoningBuf.toString();
                }
                if (!content.isEmpty()) {
                    mm.storeMessage(LLMModels.Message.assistant(content));
                }
                System.out.println();
                System.out.println(getTokenSummary());
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  记忆集成
    // ══════════════════════════════════════════════════

    private void injectMemoryToSystemPrompt(String userInput) {
        // 首次调用时保存原始 system prompt，避免记忆上下文重复叠加
        if (originalSystemPrompt == null) {
            originalSystemPrompt = mm.getSystemPrompt();
        }
        String memoryCtx = mm.buildContextForQuery(userInput, 500);
        if (!memoryCtx.isEmpty()) {
            mm.updateSystemPrompt(originalSystemPrompt + memoryCtx);
        }
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private void recordTokenUsage(LLMModels.ChatResponse response) {
        if (response.usage() != null) {
            totalPromptTokens += response.usage().promptTokens();
            totalCompletionTokens += response.usage().completionTokens();
        }
    }

    private void printToolExecution(String name, Map<String, String> args, String result) {
        System.out.println("🔧 执行工具: " + name);
        System.out.println("   参数: {");
        int count = 0;
        for (Map.Entry<String, String> e : args.entrySet()) {
            count++;
            System.out.print("     \"" + e.getKey() + "\": \"" + e.getValue() + "\"");
            if (count < args.size()) System.out.println(",");
            else System.out.println();
        }
        System.out.println("   }");
        System.out.println("   结果: " + result.replace("\n", "\n   ").trim());
        System.out.println();
    }

    private List<LLMModels.Tool> getToolDefinitions() {
        List<Tool> tools = toolRegistry.getAllTools();
        List<LLMModels.Tool> llmTools = new ArrayList<>();
        for (Tool tool : tools) {
            llmTools.add(new LLMModels.Tool(tool.name(), tool.description(), tool.parameters()));
        }
        return llmTools;
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (var e : raw.entrySet()) {
                result.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return result;
        } catch (Exception e) {
            return Map.of("arguments", argumentsJson);
        }
    }

    public void clearHistory() {
        mm.clearConversation();
    }

    public String getTokenStats() {
        return mm.getUsageReport() + "\n" + String.format("输入Token: %d | 输出Token: %d",
                totalPromptTokens, totalCompletionTokens);
    }

    public String getTokenSummary() {
        return String.format("💬 [Tokens: %d / %d]", totalPromptTokens, totalCompletionTokens);
    }

    public void resetTokenStats() {
        totalPromptTokens = 0;
        totalCompletionTokens = 0;
    }

    public String getSystemInfo() {
        String modelName = llmClient.getModel();
        boolean thinkingEnabled = llmClient.isThinkingEnabled();
        String reasoningEffort = llmClient.getReasoningEffort();
        return String.format("""
📊 系统信息

模型配置:
- 模型名称: DeepSeek (%s)
- 思考模式: %s
- 思考强度: %s
- 记忆使用: %d 条消息 | Token 使用率: %.1f%%

可用工具:
- read_file: 读取文件内容
- write_file: 写入文件内容
- list_dir: 列出目录内容
- execute_command: 执行Shell命令
- create_project: 创建新项目结构

💡 命令提示:
- /help: 显示帮助信息
- /clear: 清空对话历史
- /tokens: 查看Token统计
- /memory: 查看记忆状态
- /save: 保存关键事实
- /reset: 重置统计数据
- /thinking: 切换思考模式
- /effort: 设置思考强度
- /exit: 退出程序
""", modelName, thinkingEnabled ? "开启" : "关闭", reasoningEffort,
                mm.conversationSize(), mm.usageRate() * 100);
    }

    public boolean isThinkingEnabled() { return llmClient.isThinkingEnabled(); }
    public boolean toggleThinking() {
        llmClient.setThinkingEnabled(!llmClient.isThinkingEnabled());
        return llmClient.isThinkingEnabled();
    }
    public void setReasoningEffort(String effort) { llmClient.setReasoningEffort(effort); }
    public String getReasoningEffort() { return llmClient.getReasoningEffort(); }
    public MemoryManager getMemoryManager() { return mm; }
}
