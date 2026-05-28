// Agent.java - 修复后的完整代码
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
 * 智能编程助手Agent
 * 负责协调LLM和工具执行，完成用户任务
 */
public class Agent {

    private final DeepSeekClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LLMModels.Message> conversationHistory;
    private final ObjectMapper objectMapper;
    
    /** 最大迭代次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 10;
    
    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;
    
    /** Token 使用统计 */
    private int totalPromptTokens = 0;
    private int totalCompletionTokens = 0;
    private int totalTokens = 0;
    private int apiCallCount = 0;

    /** 系统提示词，告诉LLM它是谁、能干什么、有哪些工具可用 */
    private String systemPrompt;

    /**
     * 生成系统提示词（包含当前模型信息）
     */
    private String buildSystemPrompt(String modelName) {
        return """
        你是一个基于 DeepSeek API 的智能编程助手，可以帮助用户完成各种任务。

        当前使用的模型：""" + modelName + """

        【重要】当问题是关于事实、知识、概念解释、日常对话等不需要工具的问题时，请直接回答，不需要调用工具。

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

    /**
     * 构造函数
     * @param apiKey DeepSeek API密钥
     */
    public Agent(String apiKey) {
        this.llmClient = new DeepSeekClient(apiKey);
        this.toolRegistry = new ToolRegistry();
        this.conversationHistory = new ArrayList<>();
        this.objectMapper = new ObjectMapper();

        // 生成包含模型信息的系统提示
        String modelName = ((DeepSeekClient) llmClient).getModel();
        this.systemPrompt = buildSystemPrompt(modelName);

        // 添加系统提示
        conversationHistory.add(LLMModels.Message.system(systemPrompt));
    }

    /**
     * ReAct循环核心逻辑
     */
    public String run(String userInput) {
        // 添加用户输入
        conversationHistory.add(LLMModels.Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            int retryCount = 0;
            LLMModels.ChatResponse response = null;

            // 带重试的LLM调用
            while (retryCount < MAX_RETRIES) {
                try {
                    // 调用LLM
                    response = llmClient.chat(
                        conversationHistory,
                        getToolDefinitions()
                    );
                    break;
                } catch (IOException e) {
                    // 网络错误，可以重试
                    retryCount++;
                    if (retryCount >= MAX_RETRIES) {
                        return "网络错误: " + e.getMessage();
                    }
                } catch (Exception e) {
                    // 其他错误，返回错误信息
                    return "执行错误: " + e.getMessage();
                }
            }

            if (response == null) {
                    return "LLM调用失败";
                }

                // 记录 Token 使用
                recordTokenUsage(response);

                // 如果有工具调用
                if (response.hasToolCalls()) {
                // 记录助手消息
                conversationHistory.add(
                    LLMModels.Message.assistantWithToolCall(response.getToolCalls())
                );

                // 执行每个工具调用
                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    // 解析参数
                    Map<String, String> args = parseArguments(toolCall.function().arguments());

                    // 执行工具
                    String result = toolRegistry.executeTool(
                        toolCall.function().name(),
                        args
                    );

                    // 记录工具结果
                    conversationHistory.add(
                        LLMModels.Message.tool(result, toolCall.id())
                    );
                }
                // 继续循环，让LLM根据结果继续思考
                continue;
            } else {
                    // 没有工具调用，任务完成
                    String content = response.getContent();
                    LLMModels.Message firstMessage = response.getFirstMessage();
                    String reasoningContent = firstMessage != null ? firstMessage.reasoningContent() : null;
                    
                    conversationHistory.add(
                        LLMModels.Message.assistant(content, reasoningContent)
                    );

                    StringBuilder result = new StringBuilder();

                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                        result.append("\n🧠 思考过程\n");
                        result.append("─────────────────────────────────────────────────────\n");

                        String[] lines = reasoningContent.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            String line = lines[i];
                            if (i == 0) {
                                result.append("│  💭 ").append(line).append("\n");
                            } else {
                                result.append("│    ").append(line).append("\n");
                            }
                        }

                        result.append("─────────────────────────────────────────────────────\n");
                        result.append("\n");
                    }

                    result.append(content);
                    result.append("\n");

                    // 添加 token 统计
                    String tokenSummary = getTokenSummary();
                    result.append(tokenSummary);

                    return result.toString();
                }
            }

            return "达到最大迭代次数限制";
        }

        /**
         * 记录 Token 使用
         */
        private void recordTokenUsage(LLMModels.ChatResponse response) {
            if (response.usage() != null) {
                totalPromptTokens += response.usage().promptTokens();
                totalCompletionTokens += response.usage().completionTokens();
                totalTokens += response.usage().totalTokens();
                apiCallCount++;
            }
        }

        /**
         * 获取 Token 使用统计（完整版本）
         */
        public String getTokenStats() {
            return String.format("""
💰 Token 使用统计

总调用次数: %d
输入Token: %d
输出Token: %d
总计Token: %d
""", apiCallCount, totalPromptTokens, totalCompletionTokens, totalTokens);
        }

        /**
         * 获取 Token 使用摘要（简洁版本，用于回答末尾）
         */
        public String getTokenSummary() {
            return String.format("💬 [Tokens: %d / %d]", totalPromptTokens, totalCompletionTokens);
        }

        /**
         * 重置 Token 统计
         */
        public void resetTokenStats() {
            totalPromptTokens = 0;
            totalCompletionTokens = 0;
            totalTokens = 0;
            apiCallCount = 0;
        }

        /**
         * 获取系统信息
         */
        public String getSystemInfo() {
        DeepSeekClient client = (DeepSeekClient) llmClient;
        String modelName = client.getModel();
        boolean thinkingEnabled = client.isThinkingEnabled();
        String reasoningEffort = client.getReasoningEffort();
        
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

        /**
         * 获取工具定义列表（适配LLM调用格式）
         */
    private List<LLMModels.Tool> getToolDefinitions() {
        List<Tool> tools = toolRegistry.getAllTools();
        List<LLMModels.Tool> llmTools = new ArrayList<>();

        for (Tool tool : tools) {
            llmTools.add(new LLMModels.Tool(
                    tool.name(),
                    tool.description(),
                    tool.parameters()
            ));
        }

        return llmTools;
    }

    /**
     * 解析工具调用参数（JSON字符串转Map）
     */
    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(argumentsJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            // 如果解析失败，尝试作为简单字符串处理
            return Map.of("arguments", argumentsJson);
        }
    }

    /**
     * 获取对话历史
     */
    public List<LLMModels.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 清空对话历史
     */
    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LLMModels.Message.system(systemPrompt));
    }

    /**
     * 流式运行 Agent，实时输出思考过程和回答。
     */
    public void runStream(String userInput) {
        conversationHistory.add(LLMModels.Message.user(userInput));

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
                response = llmClient.chatStream(
                        conversationHistory,
                        getToolDefinitions(),
                        callback);
            } catch (Exception e) {
                System.err.println("请求失败: " + e.getMessage());
                return;
            }

            if (response == null) continue;

            recordTokenUsage(response);

            if (response.hasToolCalls()) {
                conversationHistory.add(
                        LLMModels.Message.assistantWithToolCall(response.getToolCalls()));

                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    Map<String, String> args = parseArguments(toolCall.function().arguments());
                    String result = toolRegistry.executeTool(
                            toolCall.function().name(), args);
                    conversationHistory.add(
                            LLMModels.Message.tool(result, toolCall.id()));
                    System.out.println("🔧 执行工具: " + toolCall.function().name());
                }
                continue;
            } else {
                System.out.println();
                String reasoningContent = reasoningBuf.isEmpty() ? null : reasoningBuf.toString();
                String content = contentBuf.toString();
                conversationHistory.add(
                        LLMModels.Message.assistant(content, reasoningContent));
                System.out.println(getTokenSummary());
                return;
            }
        }
    }

    /**
     * 获取当前思考模式状态
     */
    public boolean isThinkingEnabled() {
        return llmClient.isThinkingEnabled();
    }

    /**
     * 切换思考模式
     * @return 切换后的状态
     */
    public boolean toggleThinking() {
        DeepSeekClient client = (DeepSeekClient) llmClient;
        boolean current = client.isThinkingEnabled();
        client.setThinkingEnabled(!current);
        return !current;
    }

    /**
     * 设置思考强度
     * @param effort 思考强度（high/max）
     */
    public void setReasoningEffort(String effort) {
        DeepSeekClient client = (DeepSeekClient) llmClient;
        client.setReasoningEffort(effort);
    }

    /**
     * 获取当前思考强度
     * @return 当前思考强度
     */
    public String getReasoningEffort() {
        DeepSeekClient client = (DeepSeekClient) llmClient;
        return client.getReasoningEffort();
    }
}