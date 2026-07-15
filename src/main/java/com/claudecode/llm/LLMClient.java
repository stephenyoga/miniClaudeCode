package com.claudecode.llm;

import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

/**
 * LLM 客户端抽象父类。
 *
 * 封装所有 LLM 提供方通用的 HTTP 客户端配置。
 * DeepSeekClient 继承此类实现具体的 API 调用逻辑。
 */
public abstract class LLMClient {

    /** 共享的 OkHttp 客户端实例（连接超时 60s，读取超时 120s） */
    protected final OkHttpClient httpClient;

    /** API 密钥 */
    protected final String apiKey;

    /**
     * @param apiKey LLM 提供方的 API 密钥
     */
    protected LLMClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String getApiKey() { return apiKey; }
    protected OkHttpClient getHttpClient() { return httpClient; }
}
