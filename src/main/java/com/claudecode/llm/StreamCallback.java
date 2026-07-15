package com.claudecode.llm;

/**
 * 流式响应回调接口，用于实时接收 LLM 的增量输出。
 *
 * DeepSeek API 的流式响应把一次 LLM 调用拆成多个 chunk 逐块返回。
 * 每一块可能是 reasoning_content、content 或 tool_calls 的一部分，
 * 通过回调实时推送给调用方，实现逐字显示的效果。
 */
public interface StreamCallback {

    /**
     * 收到思考内容增量（reasoning_content 字段）。
     * 对应 LLM 的"内心独白"，只在开启思考模式时才有。
     * @param delta 本次推送到来的文本片段
     */
    void onThinkingChunk(String delta);

    /**
     * 收到回答内容增量（content 字段）。
     * 最终回答文本的拼图，逐字推送，拼起来就是完整回答。
     * @param delta 本次推送到来的文本片段
     */
    void onContentChunk(String delta);

    /**
     * 流式响应完成，返回完整的 ChatResponse（含 token 用量统计）。
     * @param fullResponse 完整的 ChatResponse 对象
     */
    void onComplete(LLMModels.ChatResponse fullResponse);

    /**
     * 流式响应过程中出错。
     * @param e 异常信息
     */
    void onError(Exception e);
}
