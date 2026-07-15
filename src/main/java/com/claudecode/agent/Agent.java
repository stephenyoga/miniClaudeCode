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
 *
 * ReAct = Reasoning + Acting。核心思路是"边想边做"：
 * 1. 把对话历史和工具定义发给 LLM
 * 2. LLM 选择"直接回答"或"调用工具"
 * 3. 如果调用工具 → 执行工具 → 结果回灌到对话历史 → 回到步骤 1
 * 4. 如果直接回答 → 返回结果
 *
 * 集成 MemoryManager：自动检索长期记忆注入 system prompt，工具结果存入短期记忆。
 */
public class Agent {

    private final DeepSeekClient llmClient;   // LLM API 客户端
    private final ToolRegistry toolRegistry;   // 工具注册表，管理所有可用工具
    private final MemoryManager mm;            // 记忆系统门面
    private final ObjectMapper objectMapper;   // JSON 解析器

    // ReAct 循环：最多 10 轮（防止无限循环）
    private static final int MAX_ITERATIONS = 10;
    // 网络请求重试：最多 3 次
    private static final int MAX_RETRIES = 3;

    // Token 统计（只统计输入和输出，不用 totalTokens 和 apiCallCount）
    private int totalPromptTokens = 0;
    private int totalCompletionTokens = 0;
    // 保存原始 system prompt，用于注入记忆时每次基于原始版拼接（防止重复叠加）
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
    //  run() 方法用于 Plan-and-Execute 内部的 task 执行，
    //  以及需要同步获取完整返回结果的场景。
    // ══════════════════════════════════════════════════

    public String run(String userInput) {
        // 1. 用户输入 → 存短期记忆 + 检索长期记忆
        mm.addUserMessage(userInput);
        injectMemoryToSystemPrompt(userInput);
        mm.storeMessage(LLMModels.Message.user(userInput));

        // 2. ReAct 主循环
        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            int retryCount = 0;
            LLMModels.ChatResponse response = null;

            // 网络请求重试循环
            while (retryCount < MAX_RETRIES) {
                try {
                    // 调 LLM 前先检查对话历史 token 是否超预算，超则压缩
                    mm.compactConversationHistory();
                    // 把当前对话历史和工具定义一起发给 LLM
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
            // Token 消耗同时记录到记忆系统
            mm.recordTokenUsage(
                    response.usage() != null ? response.usage().promptTokens() : 0,
                    response.usage() != null ? response.usage().completionTokens() : 0);

            // 分支 1：LLM 返回了工具调用
            if (response.hasToolCalls()) {
                // 保存 assistant 消息（含 tool_calls）到对话历史
                String rc = response.getFirstMessage() != null
                        ? response.getFirstMessage().reasoningContent() : null;
                mm.storeMessage(LLMModels.Message.assistantWithToolCall(response.getToolCalls(), rc));

                // 逐个执行工具，执行结果回灌到对话历史
                for (LLMModels.ToolCall toolCall : response.getToolCalls()) {
                    // 解析工具参数（LLM 传的是 JSON 字符串，转为 Map）
                    Map<String, String> args = parseArguments(toolCall.function().arguments());
                    String result = toolRegistry.executeTool(toolCall.function().name(), args);
                    printToolExecution(toolCall.function().name(), args, result);
                    // 工具结果存入对话历史（后续 LLM 调用能看到）
                    mm.storeMessage(LLMModels.Message.tool(result, toolCall.id()));
                    // 工具结果同时存入短期记忆（用于检索和事实提取）
                    mm.addToolResult(toolCall.function().name(), args.toString(), result);
                }
                continue;  // 回到循环开头，让 LLM 看到工具结果后继续
            }
            // 分支 2：LLM 直接回答（没有工具调用）
            else {
                String content = response.getContent();
                LLMModels.Message firstMessage = response.getFirstMessage();
                String reasoningContent = firstMessage != null ? firstMessage.reasoningContent() : null;
                // 保存到对话历史和短期记忆
                mm.storeMessage(LLMModels.Message.assistant(content));
                mm.addAssistantMessage(LLMModels.Message.assistant(content));

                // 组装显示给用户：思考过程 + 回答 + Token 统计
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
                return result.toString();  // 返回最终结果，循环结束
            }
        }
        return "达到最大迭代次数限制";
    }

    // ══════════════════════════════════════════════════
    //  ReAct（流式）
    //  runStream() 用于主对话界面的实时输出。
    //  思考过程和回答内容通过 StreamCallback 逐字推送显示。
    // ══════════════════════════════════════════════════

    public void runStream(String userInput) {
        mm.addUserMessage(userInput);
        injectMemoryToSystemPrompt(userInput);
        mm.storeMessage(LLMModels.Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 流式回调：在收到 LLM 的每个 chunk 时实时显示
            StringBuilder reasoningBuf = new StringBuilder();
            StringBuilder contentBuf = new StringBuilder();
            boolean[] thinkingHeaderShown = {false};
            boolean[] thinkingEnded = {false};

            StreamCallback callback = new StreamCallback() {
                // 收到思考内容的增量（reasoning_content 字段）
                public void onThinkingChunk(String delta) {
                    if (!thinkingHeaderShown[0]) {
                        System.out.print("🧠 思考过程\n─────────────────────────\n│  💭 ");
                        thinkingHeaderShown[0] = true;
                    }
                    System.out.print(delta);
                    reasoningBuf.append(delta);
                }
                // 收到回答内容的增量（content 字段）
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

            // 调 LLM（流式模式），传入回调接收增量
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

            // 和 run() 一样的分支判断
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
                // 流式模式下 content 可能为空（有时 LLM 只返回 reasoning_content）
                // 此时用 reasoningBuf 的内容兜底，保证一定有内容存到记忆
                String content = contentBuf.toString();
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

    /**
     * 将相关长期记忆检索出来注入到 system prompt。
     * 首次调用时保存原始 prompt，之后每次基于原始版拼接，避免重复叠加。
     */
    private void injectMemoryToSystemPrompt(String userInput) {
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

    /** 记录本轮 API 调用的 Token 消耗 */
    private void recordTokenUsage(LLMModels.ChatResponse response) {
        if (response.usage() != null) {
            totalPromptTokens += response.usage().promptTokens();
            totalCompletionTokens += response.usage().completionTokens();
        }
    }

    /** 格式化打印工具调用信息（工具名、参数、结果） */
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

    /** 把 ToolRegistry 的工具转为 LLM API 认识的 Tool 格式（JSON Schema） */
    private List<LLMModels.Tool> getToolDefinitions() {
        List<Tool> tools = toolRegistry.getAllTools();
        List<LLMModels.Tool> llmTools = new ArrayList<>();
        for (Tool tool : tools) {
            llmTools.add(new LLMModels.Tool(tool.name(), tool.description(), tool.parameters()));
        }
        return llmTools;
    }

    /**
     * 解析 LLM 返回的工具参数 JSON。
     * LLM 传的参数可能是 {"recursive": true}（布尔）或 {"count": 5}（数字），
     * 先用 Map<String, Object> 接收，再统一转 String，避免类型转换异常。
     */
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

    /** 清空对话历史 */
    public void clearHistory() {
        mm.clearConversation();
    }

    /** Token 统计详情（含记忆系统的统计） */
    public String getTokenStats() {
        return mm.getUsageReport() + "\n" + String.format("输入Token: %d | 输出Token: %d",
                totalPromptTokens, totalCompletionTokens);
    }

    /** Token 统计摘要（单行，用于会话结束显示） */
    public String getTokenSummary() {
        return String.format("💬 [Tokens: %d / %d]", totalPromptTokens, totalCompletionTokens);
    }

    /** 重置 Token 统计 */
    public void resetTokenStats() {
        totalPromptTokens = 0;
        totalCompletionTokens = 0;
    }

    /** 显示系统信息：模型、思考模式、工具列表、命令提示 */
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

    // ── 思考模式控制 ──
    public boolean isThinkingEnabled() { return llmClient.isThinkingEnabled(); }
    public boolean toggleThinking() {
        llmClient.setThinkingEnabled(!llmClient.isThinkingEnabled());
        return llmClient.isThinkingEnabled();
    }
    public void setReasoningEffort(String effort) { llmClient.setReasoningEffort(effort); }
    public String getReasoningEffort() { return llmClient.getReasoningEffort(); }
    public MemoryManager getMemoryManager() { return mm; }
}
