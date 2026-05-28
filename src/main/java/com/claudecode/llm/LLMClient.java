// LLMClient.java - 抽象父类
package com.claudecode.llm;

import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

/**
 * LLM客户端抽象父类
 * 提供通用的HTTP客户端配置和基础功能
 */
public abstract class LLMClient {

    /**
     * HTTP客户端实例
     */
    protected final OkHttpClient httpClient;

    /**
     * API密钥
     */
    protected final String apiKey;

    /**
     * 构造函数
     * @param apiKey API密钥
     */
    protected LLMClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取API密钥
     * @return API密钥
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 获取HTTP客户端
     * @return OkHttpClient实例
     */
    protected OkHttpClient getHttpClient() {
        return httpClient;
    }
}