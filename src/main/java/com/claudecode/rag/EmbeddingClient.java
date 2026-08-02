package com.claudecode.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端 —— 调用 Ollama 或 OpenAI 兼容 API 生成文本向量。
 *
 * 向量用于计算文本之间的语义相似度。
 * "JDK 版本"和"Java 版本"的向量距离应该很近，即使文本内容不同。
 * 语义检索 = 把查询转成向量，和所有代码块向量算余弦相似度，找最近的。
 *
 * 支持两种后端：
 * - Ollama（默认）：http://localhost:11434/api/embeddings，模型 nomic-embed-text
 * - OpenAI 兼容：如智谱 GLM 的 embedding 接口，Bearer token 认证
 *
 * 配置通过 .env 文件（或系统属性）：
 *   EMBEDDING_PROVIDER=ollama
 *   EMBEDDING_MODEL=nomic-embed-text:latest
 *   EMBEDDING_BASE_URL=http://localhost:11434
 *   EMBEDDING_API_KEY=xxx
 */
public class EmbeddingClient {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final String provider;  // 后端类型: ollama / openai / zhipu / glm
    private final String model;     // embedding 模型名
    private final String baseUrl;   // API 地址
    private final String apiKey;    // API Key（ollama 本地不需要）
    /** 输入文本最大字符数，超长截断（防止 API 报错，也控制成本） */
    private static final int MAX_INPUT_CHARS = 2000;

    public EmbeddingClient() {
        this.provider = getEnv("EMBEDDING_PROVIDER", "ollama");
        this.model = getEnv("EMBEDDING_MODEL", "nomic-embed-text:latest");
        this.baseUrl = getEnv("EMBEDDING_BASE_URL", inferDefaultUrl(provider));
        this.apiKey = getEnv("EMBEDDING_API_KEY", "");
    }

    /**
     * 生成文本的向量表示。
     * 根据配置的 provider 选择对应的 API 调用方式。
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) return new float[0];
        String input = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        return switch (provider.toLowerCase()) {
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu", "glm" -> embedOpenAI(input);
            default -> embedOllama(input);
        };
    }

    /**
     * Ollama embedding API：
     * POST {base}/api/embeddings，body {model, prompt}
     * 响应 {embedding: [0.1, 0.2, ...]}，取 embedding 数组。
     */
    private float[] embedOllama(String text) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", text);

        String resp = post(baseUrl + "/api/embeddings", body.toString(), false);
        JsonNode root = mapper.readTree(resp);
        JsonNode emb = root.path("embedding");
        if (!emb.isArray()) throw new IOException("Ollama embedding 响应格式不正确");
        return toFloatArray(emb);
    }

    /**
     * OpenAI 兼容 embedding API：
     * POST {base}/embeddings，body {model, input}，带 Bearer token
     * 响应 {data: [{embedding: [...]}]}，取 data[0].embedding。
     */
    private float[] embedOpenAI(String text) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", text);

        String resp = post(baseUrl + "/embeddings", body.toString(), true);
        JsonNode root = mapper.readTree(resp);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) throw new IOException("API embedding 响应格式不正确");
        return toFloatArray(data.get(0).path("embedding"));
    }

    /** 把 JSON 数组转成 float[] 向量 */
    private float[] toFloatArray(JsonNode arr) {
        float[] result = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = (float) arr.get(i).asDouble();
        return result;
    }

    /** 发送 POST JSON 请求。useAuth=true 时带 Bearer token。 */
    private String post(String url, String json, boolean useAuth) throws IOException {
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder().url(url).header("Content-Type", "application/json").post(body);
        if (useAuth && !apiKey.isEmpty()) builder.header("Authorization", "Bearer " + apiKey);
        try (Response resp = HTTP.newCall(builder.build()).execute()) {
            if (!resp.isSuccessful()) throw new IOException("Embedding API [" + resp.code() + "]: " + (resp.body() != null ? resp.body().string() : "无响应"));
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    /** 根据 provider 推断默认 API 地址 */
    private static String inferDefaultUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "http://localhost:11434";
            default -> "http://localhost:11434";
        };
    }

    /** 从环境变量或系统属性读取配置 */
    private static String getEnv(String key, String def) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) return v;
        v = System.getProperty(key);
        return v != null && !v.isEmpty() ? v : def;
    }
}
