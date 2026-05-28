package com.claudecode.llm;

import com.claudecode.config.EnvConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API 客户端，封装与 DeepSeek 大语言模型的 HTTP 通信。
 * 继承自 LLMClient，复用通用的 HTTP 客户端配置。
 */
public class DeepSeekClient extends LLMClient {

    /** DeepSeek Chat Completion API 地址 */
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";

    /**
     * DeepSeek模型常量定义
     */
    // 基础聊天模型
    public static final String MODEL_CHAT = "deepseek-chat";
    // V4专业版模型 - 高性能
    public static final String MODEL_V4_PRO = "deepseek-v4-pro";
    // V4快速版模型 - 响应更快
    public static final String MODEL_V4_FLASH = "deepseek-v4-flash";
    // 代码专用模型
    public static final String MODEL_CODER = "deepseek-coder";
    // 推理专用模型（DeepSeek R1）
    public static final String MODEL_REASONER = "deepseek-reasoner";

    /**
     * 当前使用的DeepSeek模型名称（从环境变量读取，默认为MODEL_CHAT）
     */
    private final String model;

    /**
     * 思考模式开关（默认关闭）
     */
    private boolean thinkingEnabled = false;

    /**
     * 思考强度（high/max，默认high）
     */
    private String reasoningEffort = "high";

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * @param apiKey DeepSeek 平台的 API Key
     */
    public DeepSeekClient(String apiKey) {
        super(apiKey);
        this.objectMapper = new ObjectMapper();
        // 从 .env 文件读取模型名称，默认使用 deepseek-chat
        String envModel = EnvConfig.get("DEEPSEEK_MODEL");
        this.model = (envModel != null && !envModel.isEmpty()) ? envModel : MODEL_CHAT;

        // 输出模型配置信息（方便确认配置是否生效）
        if (envModel != null && !envModel.isEmpty()) {
            System.out.println("📦 已加载模型配置: " + model + " (来自 .env 文件)");
        } else {
            System.out.println("📦 使用默认模型配置: " + model);
        }
    }

    /**
     * 获取当前使用的模型名称
     * @return 模型名称
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置思考模式开关
     * @param enabled 是否启用思考模式
     */
    public void setThinkingEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
    }

    /**
     * 获取思考模式状态
     * @return 是否启用思考模式
     */
    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    /**
     * 设置思考强度
     * @param effort 思考强度（high/max）
     */
    public void setReasoningEffort(String effort) {
        if ("high".equalsIgnoreCase(effort) || "max".equalsIgnoreCase(effort)) {
            this.reasoningEffort = effort.toLowerCase();
        }
    }

    /**
     * 获取思考强度
     * @return 思考强度
     */
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    /**
     * 发送聊天请求到DeepSeek API（简单版本）
     * @param messages 消息列表，每条消息包含role和content字段
     *                 role可选值: "user", "assistant", "system"
     * @return 模型生成的响应内容
     * @throws IOException 网络请求失败时抛出异常
     */
    public String chat(List<Map<String, String>> messages) throws IOException {
        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);              // 指定模型
        requestBody.put("temperature", 0.7);          // 温度参数，控制输出随机性
        requestBody.put("max_tokens", 4096);         // 最大生成token数

        // 添加消息历史
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.get("role"));
            msgNode.put("content", msg.get("content"));
        }

        // 构建HTTP请求
        RequestBody body = RequestBody.create(requestBody.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        // 执行请求并处理响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DeepSeek API request failed: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
            return null;
        }
    }

    /**
     * 发送聊天请求到DeepSeek API（支持工具调用）
     * @param messages 消息列表
     * @param tools 工具定义列表
     * @return ChatResponse 响应对象
     * @throws IOException 网络请求失败时抛出异常
     */
    public LLMModels.ChatResponse chat(List<LLMModels.Message> messages, List<LLMModels.Tool> tools) throws IOException {
        ObjectNode requestBody = buildRequestBody(messages, tools);
        Request request = buildRequest(requestBody);

        // 解析响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DeepSeek API request failed: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);

            // 提取基本信息
            String id = root.get("id").asText();
            String object = root.get("object").asText();
            long created = root.get("created").asLong();
            String model = root.get("model").asText();

            // 提取choices
            List<LLMModels.ChatResponse.Choice> choices = new ArrayList<>();
            JsonNode choicesNode = root.get("choices");
            if (choicesNode != null && choicesNode.isArray()) {
                for (JsonNode choiceNode : choicesNode) {
                    // 解析消息
                    JsonNode messageNode = choiceNode.get("message");
                    String role = messageNode.get("role").asText();
                    String content = messageNode.has("content") ? messageNode.get("content").asText() : null;
                    // 提取思考内容（reasoning_content 与 content 同级）
                    String reasoningContent = messageNode.has("reasoning_content") ? messageNode.get("reasoning_content").asText() : null;
                    String toolCallId = messageNode.has("tool_call_id") ? messageNode.get("tool_call_id").asText() : null;

                    // 解析tool_calls
                    List<LLMModels.ToolCall> toolCalls = new ArrayList<>();
                    JsonNode toolCallsNode = messageNode.get("tool_calls");
                    if (toolCallsNode != null && toolCallsNode.isArray()) {
                        for (JsonNode tcNode : toolCallsNode) {
                            String tcId = tcNode.get("id").asText();
                            JsonNode funcNode = tcNode.get("function");
                            String funcName = funcNode.get("name").asText();
                            String funcArgs = funcNode.get("arguments").asText();
                            toolCalls.add(new LLMModels.ToolCall(tcId, new LLMModels.FunctionCall(funcName, funcArgs)));
                        }
                    }

                    LLMModels.Message message = new LLMModels.Message(role, content, reasoningContent, toolCalls, toolCallId);
                    String finishReason = choiceNode.has("finish_reason") ? choiceNode.get("finish_reason").asText() : null;
                    int index = choiceNode.get("index").asInt();

                    choices.add(new LLMModels.ChatResponse.Choice(message, finishReason, index));
                }
            }

            // 提取usage
            JsonNode usageNode = root.get("usage");
            int promptTokens = usageNode.get("prompt_tokens").asInt();
            int completionTokens = usageNode.get("completion_tokens").asInt();
            int totalTokens = usageNode.get("total_tokens").asInt();
            LLMModels.ChatResponse.Usage usage = new LLMModels.ChatResponse.Usage(promptTokens, completionTokens, totalTokens);

            return new LLMModels.ChatResponse(id, object, created, model, choices, usage);
        }
    }

    /**
     * 流式聊天请求，实时推送思考过程和回答内容。
     * 阻塞直到流式响应完成，通过回调通知每个增量 chunk。
     *
     * @param messages 消息列表
     * @param tools    工具定义列表（流式下暂不处理工具调用）
     * @param callback 流式回调
     * @return 完整的 ChatResponse
     * @throws IOException 网络异常
     */
    public LLMModels.ChatResponse chatStream(
            List<LLMModels.Message> messages,
            List<LLMModels.Tool> tools,
            StreamCallback callback) throws IOException {

        ObjectNode requestBody = buildRequestBody(messages, tools);
        requestBody.put("stream", true);

        Request request = buildRequest(requestBody);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DeepSeek API stream request failed: " + response.code());
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()));

            // 累积变量
            String id = null, object = null, model = null;
            long created = 0;
            StringBuilder reasoningBuf = new StringBuilder();
            StringBuilder contentBuf = new StringBuilder();
            String finishReason = null;
            int promptTokens = 0, completionTokens = 0, totalTokens = 0;
            boolean reasoningStarted = false;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data: ")) continue;

                String json = line.substring(6);
                if ("[DONE]".equals(json.trim())) break;

                JsonNode chunk = objectMapper.readTree(json);

                // 提取基础信息（首 chunk）
                if (id == null && chunk.has("id")) {
                    id = chunk.get("id").asText();
                }
                if (chunk.has("object")) object = chunk.get("object").asText();
                if (chunk.has("created")) created = chunk.get("created").asLong();
                if (chunk.has("model")) model = chunk.get("model").asText();

                // 提取 usage（最后一个 chunk）
                if (chunk.has("usage")) {
                    JsonNode usageNode = chunk.get("usage");
                    if (usageNode.has("prompt_tokens")) promptTokens = usageNode.get("prompt_tokens").asInt();
                    if (usageNode.has("completion_tokens")) completionTokens = usageNode.get("completion_tokens").asInt();
                    if (usageNode.has("total_tokens")) totalTokens = usageNode.get("total_tokens").asInt();
                }

                JsonNode choices = chunk.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) continue;

                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) continue;

                // 提取 finish_reason
                if (choices.get(0).has("finish_reason") && !choices.get(0).get("finish_reason").isNull()) {
                    finishReason = choices.get(0).get("finish_reason").asText();
                }

                // 推送思考内容增量
                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                    String rc = delta.get("reasoning_content").asText();
                    if (!reasoningStarted) {
                        reasoningStarted = true;
                    }
                    reasoningBuf.append(rc);
                    callback.onThinkingChunk(rc);
                }

                // 推送回答内容增量
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String ct = delta.get("content").asText();
                    contentBuf.append(ct);
                    callback.onContentChunk(ct);
                }
            }

            // 构建完整响应
            LLMModels.Message message = new LLMModels.Message(
                    "assistant",
                    contentBuf.isEmpty() ? null : contentBuf.toString(),
                    reasoningBuf.isEmpty() ? null : reasoningBuf.toString(),
                    null, null);

            LLMModels.ChatResponse.Choice choice = new LLMModels.ChatResponse.Choice(
                    message, finishReason, 0);

            LLMModels.ChatResponse.Usage usage = new LLMModels.ChatResponse.Usage(
                    promptTokens, completionTokens, totalTokens);

            LLMModels.ChatResponse fullResponse = new LLMModels.ChatResponse(
                    id, object, created, model, List.of(choice), usage);

            callback.onComplete(fullResponse);
            return fullResponse;
        } catch (Exception e) {
            callback.onError(e);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Stream request failed", e);
        }
    }

    /**
     * 构建请求体 JSON（chat 和 chatStream 共用）
     */
    private ObjectNode buildRequestBody(List<LLMModels.Message> messages, List<LLMModels.Tool> tools) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4096);

        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", thinkingEnabled ? "enabled" : "disabled");
        requestBody.set("thinking", thinking);

        if (thinkingEnabled) {
            requestBody.put("reasoning_effort", reasoningEffort);
        }

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (LLMModels.Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content() != null ? msg.content() : "");

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (LLMModels.ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (LLMModels.Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }

        return requestBody;
    }

    /**
     * 构建 HTTP 请求
     */
    private Request buildRequest(ObjectNode requestBody) {
        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json"));
        return new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
    }
}