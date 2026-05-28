package com.claudecode.llm;

/**
 * 流式响应回调接口，用于实时接收 LLM 的增量输出。
 */
public interface StreamCallback {

    /**
     * 收到思考内容增量
     */
    void onThinkingChunk(String delta);

    /**
     * 收到回答内容增量
     */
    void onContentChunk(String delta);

    /**
     * 流式响应完成
     * @param fullResponse 完整的 ChatResponse，包含 usage 等信息
     */
    void onComplete(LLMModels.ChatResponse fullResponse);

    /**
     * 流式响应出错
     */
    void onError(Exception e);
}
