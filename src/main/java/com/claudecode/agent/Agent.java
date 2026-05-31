package com.claudecode.agent;

import com.claudecode.llm.DeepSeekClient;
import com.claudecode.llm.LLMModels;
import com.claudecode.llm.StreamCallback;
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
 * 与 PlanAndExecuteAgent 共享 ConversationManager 维护对话上下文。
 */
public class Agent {

    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ConversationManager cm;
    private final ObjectMapper objectMapper;

    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_RETRIES = 3;

    private int totalPromptTokens = 0;
    private int totalCompletionTokens = 0;
    private int totalTokens = 0;
    private int apiCallCount = 0;

    private String systemPrompt;

    private String buildSystemPrompt(String modelName) {
        return """
        你是一个基于 DeepSeek API 的智能编程助手，可以帮助用户完成各种任务。

        当前使用的模型：""" + modelName + """

        【重要】当问题是关于事实、知识、概念解释、日常对话等不需要工具的问题时，请直接回答，不需要调用工具。
        如果用户没有特殊要求，思考过程和回答都使用中文。

        只有在需要以下操作时才调用工具：
        1. read_file - 读取文件内容
        2. write_file - 写入文件内容
        3. list_dir - 列出目录内容
        4. execute_command - 执行Shell命令
        5. create_project - 创建新项目结构

        使用工具后，根据工具返回的结果继续思考下一步行动。

        请全程使用中文进行思考和回复用户。
        """;
    }

    /** 构造函数（共享 DeepSeekClient 和 ConversationManager） */
    public Agent(DeepSeekClient llmClient, ConversationManager cm) {
        this.llmClient = llmClient;
        this.cm = cm;
        this.toolRegistry = new ToolRegistry();
        this.objectMapper = new ObjectMapper();
        this.systemPrompt = buildSystemPrompt(llmClient.getModel());
    }

    public Agent(DeepSeekClient llmClient) {
        this(llmClient, new ConversationManager(buildSystemPromptStatic(llmClient.getModel())));
    }

    public Agent(String apiKey) {
        this(new DeepSeekClient(apiKey));
    }

    private static String buildSystemPromptStatic(String modelName) {
        return "你是一个基于 DeepSeek API 的智能编程助手。请用中文回复。\n当前模型：" + modelName;
    }

    // ══════════════════════════════════════════════════
    //  ReAct 循环（非流式，保留兼容）
    // ══════════════════════════════════════════════════

    public String run(String userInput) {
        cm.addUser(userInput);

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            int retryCount = 0;
            LLMModels.ChatResponse response = null;

            while (retryCount < MAX_RETRIES) {
                try {
                    response = llmClient.chat(cm.toList(), getToolDefinitions());
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

            if (response.hasToolCalls()) {
                cm.add(LLMModels.Message.assistantWithToolCall(response.getToolCalls()));

                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    Map<String, String> args = parseArguments(toolCall.function().arguments());
                    String result = toolRegistry.executeTool(toolCall.function().name(), args);
                    printToolExecution(toolCall.function().name(), args, result);
                    cm.add(LLMModels.Message.tool(result, toolCall.id()));
                }
                continue;
            } else {
                String content = response.getContent();
                LLMModels.Message firstMessage = response.getFirstMessage();
                String reasoningContent = firstMessage != null ? firstMessage.reasoningContent() : null;
                cm.add(LLMModels.Message.assistant(content, reasoningContent));

                StringBuilder result = new StringBuilder();
                if (reasoningContent != null && !reasoningContent.isEmpty()) {
                    result.append("\n🧠 思考过程\n");
                    result.append("─────────────────────────\n");
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
    //  ReAct 循环（流式）
    // ══════════════════════════════════════════════════

    public void runStream(String userInput) {
        cm.addUser(userInput);

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
                response = llmClient.chatStream(cm.toList(), getToolDefinitions(), callback);
            } catch (Exception e) {
                System.err.println("请求失败: " + e.getMessage());
                return;
            }

            if (response == null) continue;
            recordTokenUsage(response);

            if (response.hasToolCalls()) {
                cm.add(LLMModels.Message.assistantWithToolCall(response.getToolCalls()));

                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    Map<String, String> args = parseArguments(toolCall.function().arguments());
                    String result = toolRegistry.executeTool(toolCall.function().name(), args);
                    cm.add(LLMModels.Message.tool(result, toolCall.id()));
                    printToolExecution(toolCall.function().name(), args, result);
                }
                continue;
            } else {
                System.out.println();
                String reasoningContent = reasoningBuf.isEmpty() ? null : reasoningBuf.toString();
                String content = contentBuf.toString();
                cm.add(LLMModels.Message.assistant(content, reasoningContent));
                System.out.println(getTokenSummary());
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private void recordTokenUsage(LLMModels.ChatResponse response) {
        if (response.usage() != null) {
            totalPromptTokens += response.usage().promptTokens();
            totalCompletionTokens += response.usage().completionTokens();
            totalTokens += response.usage().totalTokens();
            apiCallCount++;
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
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of("arguments", argumentsJson);
        }
    }

    public void clearHistory() {
        cm.clear();
    }

    public String getTokenStats() {
        return String.format("""
💰 Token 使用统计

总调用次数: %d
输入Token: %d
输出Token: %d
总计Token: %d
""", apiCallCount, totalPromptTokens, totalCompletionTokens, totalTokens);
    }

    public String getTokenSummary() {
        return String.format("💬 [Tokens: %d / %d]", totalPromptTokens, totalCompletionTokens);
    }

    public void resetTokenStats() {
        totalPromptTokens = 0;
        totalCompletionTokens = 0;
        totalTokens = 0;
        apiCallCount = 0;
    }

    public String getSystemInfo() {
        String modelName = llmClient.getModel();
        boolean thinkingEnabled = llmClient.isThinkingEnabled();
        String reasoningEffort = llmClient.getReasoningEffort();
        return String.format("""
📊 系统信息

模型配置:
- 模型名称: DeepSeek (%s)
- API端点: https://api.deepseek.com/v1/chat/completions
- 思考模式: %s
- 思考强度: %s
- 最大迭代次数: 10
- 最大重试次数: 3

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
- /reset: 重置统计数据
- /thinking: 切换思考模式
- /effort: 设置思考强度
- /exit: 退出程序
""", modelName, thinkingEnabled ? "开启" : "关闭", reasoningEffort);
    }

    public boolean isThinkingEnabled() { return llmClient.isThinkingEnabled(); }
    public boolean toggleThinking() {
        llmClient.setThinkingEnabled(!llmClient.isThinkingEnabled());
        return llmClient.isThinkingEnabled();
    }
    public void setReasoningEffort(String effort) { llmClient.setReasoningEffort(effort); }
    public String getReasoningEffort() { return llmClient.getReasoningEffort(); }
    public String getSystemPrompt() { return systemPrompt; }
    public ConversationManager getConversationManager() { return cm; }
}
