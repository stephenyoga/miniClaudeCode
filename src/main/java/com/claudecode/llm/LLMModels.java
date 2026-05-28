// LLMModels.java - 统一的LLM模型类
package com.claudecode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;

/**
 * LLM模型类集合
 * 将所有LLM相关的模型类统一管理在此文件中
 */
public final class LLMModels {

    /**
     * 私有构造函数，防止实例化
     */
    private LLMModels() {}

    // ==================== 消息相关模型 ====================

    /**
     * 聊天消息模型
     *
     * @param role 角色: system, user, assistant, tool
     * @param content 消息内容
     * @param reasoningContent 思考内容（开启思考模式时返回）
     * @param toolCalls 工具调用列表（assistant角色使用）
     * @param toolCallId 工具调用ID（tool角色使用）
     */
    public record Message(
            String role,
            String content,
            String reasoningContent,
            List<ToolCall> toolCalls,
            String toolCallId
    ) {
        /**
         * 创建用户消息
         */
        public static Message user(String content) {
            return new Message("user", content, null, null, null);
        }

        /**
         * 创建系统消息
         */
        public static Message system(String content) {
            return new Message("system", content, null, null, null);
        }

        /**
         * 创建助手消息
         */
        public static Message assistant(String content) {
            return new Message("assistant", content, null, null, null);
        }

        /**
         * 创建带思考内容的助手消息
         */
        public static Message assistant(String content, String reasoningContent) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        /**
         * 创建工具响应消息
         */
        public static Message tool(String content, String toolCallId) {
            return new Message("tool", content, null, null, toolCallId);
        }

        /**
         * 创建带工具调用的助手消息
         */
        public static Message assistantWithToolCall(List<ToolCall> toolCalls) {
            return new Message("assistant", null, null, toolCalls, null);
        }

        /**
         * 创建带思考内容和工具调用的助手消息
         */
        public static Message assistantWithToolCall(List<ToolCall> toolCalls, String reasoningContent) {
            return new Message("assistant", null, reasoningContent, toolCalls, null);
        }

        /**
         * 获取工具调用列表（空列表而非null）
         */
        public List<ToolCall> toolCalls() {
            return toolCalls != null ? toolCalls : Collections.emptyList();
        }
    }

    /**
     * 工具调用模型
     *
     * @param id 调用ID
     * @param function 函数调用详情
     */
    public record ToolCall(
            String id,
            FunctionCall function
    ) {}

    /**
     * 函数调用模型
     *
     * @param name 函数名称
     * @param arguments 函数参数（JSON字符串）
     */
    public record FunctionCall(
            String name,
            String arguments
    ) {}

    // ==================== 工具定义模型 ====================

    /**
     * 工具定义模型
     *
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 参数定义（JSON Schema）
     */
    public record Tool(
            String name,
            String description,
            JsonNode parameters
    ) {}

    // ==================== 响应模型 ====================

    /**
     * 聊天响应模型
     *
     * @param id 响应ID
     * @param object 对象类型
     * @param created 创建时间戳
     * @param model 使用的模型
     * @param choices 响应选项列表
     * @param usage Token使用统计
     */
    public record ChatResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage
    ) {
        /**
         * 响应选项
         */
        public record Choice(
                Message message,
                String finishReason,
                int index
        ) {}

        /**
         * Token使用统计
         */
        public record Usage(
                int promptTokens,
                int completionTokens,
                int totalTokens
        ) {}

        /**
         * 获取第一个选择的消息
         */
        public Message getFirstMessage() {
            if (choices != null && !choices.isEmpty()) {
                return choices.get(0).message();
            }
            return null;
        }

        /**
         * 获取第一个选择的结束原因
         */
        public String getFinishReason() {
            if (choices != null && !choices.isEmpty()) {
                return choices.get(0).finishReason();
            }
            return null;
        }

        /**
         * 判断是否需要调用工具
         */
        public boolean needsToolCall() {
            String finishReason = getFinishReason();
            return "tool_calls".equals(finishReason) ||
                    (getFirstMessage() != null &&
                            !getFirstMessage().toolCalls().isEmpty());
        }

        /**
         * 判断是否有工具调用（与 needsToolCall 同义）
         */
        public boolean hasToolCalls() {
            return needsToolCall();
        }

        /**
         * 获取工具调用列表
         */
        public List<ToolCall> getToolCalls() {
            Message msg = getFirstMessage();
            return msg != null ? msg.toolCalls() : List.of();
        }

        /**
     * 获取响应内容
     */
        public String getContent() {
            Message msg = getFirstMessage();
            return msg != null ? msg.content() : "";
        }

            }
}