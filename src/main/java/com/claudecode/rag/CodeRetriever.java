package com.claudecode.rag;

import java.sql.SQLException;
import java.util.*;

/**
 * 代码检索器 —— 语义检索 + 关键词检索的统一入口。
 *
 * 支持 3 种检索方式：
 * - semanticSearch：向量余弦相似度检索（需要 Embedding 服务）
 * - keywordSearch：SQL LIKE 精确匹配类名/方法名/内容
 * - hybridSearch：混合检索（推荐），语义 + 关键词分别跑 → 合并去重 → 类型加分 → 同文件限流
 *
 * 混合检索（hybridSearch）流程：
 * 1. 语义检索：查询转向量 → SQLite 全表余弦相似度 → TopK×2 候选
 * 2. 关键词检索：tokenize() 分词（中文双字 + 英文标识符）→ 每个 token SQL LIKE
 * 3. 合并去重：按 filePath#name 合并，语义+关键词双重命中 +0.1
 * 4. 关键词加分：类名命中 +0.3，文件名 +0.1，内容 +0.1
 * 5. 类型加分：method +0.15, class +0.1（方法比文件更直接回答"怎么实现"）
 * 6. 同文件限流：每个文件最多 2 条，总数不超过 topK
 *
 * 分词器 tokenize() 支持：
 * - 中文双字滑动窗口（"登录" 匹配 "登录逻辑"）
 * - 英文代码标识符提取（UserService、handleLogin 等驼峰/下划线命名）
 */
public class CodeRetriever implements AutoCloseable {

    private final EmbeddingClient embedding;
    private final VectorStore store;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embedding = new EmbeddingClient();
        java.nio.file.Path abs = java.nio.file.Paths.get(projectPath).toAbsolutePath().normalize();
        this.store = new VectorStore(abs.toString());
    }

    /** 语义检索 */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        return store.search(embedding.embed(query), topK);
    }

    /** 关键词检索 */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return store.searchByKeyword(keyword);
    }

    /** 混合检索：语义 + 关键词，合并去重 */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        Map<String, VectorStore.SearchResult> merged = new LinkedHashMap<>();

        // 语义检索（取更多候选用双重命中加分）
        for (VectorStore.SearchResult r : store.search(embedding.embed(query), topK * 2)) {
            merged.merge(r.filePath() + "#" + r.name(), r, (a, b) ->
                    new VectorStore.SearchResult(b.filePath(), b.chunkType(), b.name(),
                            b.content(), Math.max(a.similarity(), b.similarity()) + 0.1));
        }

        // 关键词检索
        for (String kw : tokenize(query)) {
            for (VectorStore.SearchResult r : store.searchByKeyword(kw)) {
                double bonus = 0;
                String name = r.name().toLowerCase();
                String kwLow = kw.toLowerCase();
                if (name.contains(kwLow)) bonus += 0.3;
                if (r.filePath().toLowerCase().contains(kwLow)) bonus += 0.1;
                if (r.content().toLowerCase().contains(kwLow)) bonus += 0.1;
                double finalSim = Math.min(r.similarity() + bonus, 1.0);
                String key = r.filePath() + "#" + r.name();
                merged.merge(key,
                        new VectorStore.SearchResult(r.filePath(), r.chunkType(), r.name(), r.content(), finalSim),
                        (a, b) -> new VectorStore.SearchResult(b.filePath(), b.chunkType(), b.name(),
                                b.content(), Math.max(a.similarity(), b.similarity())));
            }
        }

        // 类型加分 + 排序 + 同文件限制
        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (VectorStore.SearchResult r : merged.values()) {
            double boost = "method".equals(r.chunkType()) ? 0.15 : "class".equals(r.chunkType()) ? 0.1 : 0;
            ranked.add(new VectorStore.SearchResult(r.filePath(), r.chunkType(), r.name(),
                    r.content(), r.similarity() + boost));
        }
        ranked.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        // 同文件最多 2 条
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> count = new HashMap<>();
        for (VectorStore.SearchResult r : ranked) {
            if (count.getOrDefault(r.filePath(), 0) < 2) {
                result.add(r);
                count.merge(r.filePath(), 1, Integer::sum);
                if (result.size() >= topK) break;
            }
        }
        return result;
    }

    public VectorStore.IndexStats getStats() throws SQLException {
        return store.getStats();
    }

    @Override
    public void close() throws Exception {
        store.close();
    }

    /**
     * 查询分词器 —— 提取自然语言查询中的代码关键词，用于关键词检索加权。
     *
     * 目标：把用户问题里的代码标识符（类名/方法名/变量名）和中文关键词都保留下来。
     * 例如查询 "用户登录逻辑 UserService"：
     * - 中文双字窗口 → "用户", "户登", "登录", "录逻", "逻辑"
     * - 英文标识符    → "UserService"（整体）+ "userservice"（小写匹配）
     *
     * 三种提取方式：
     * 1. 双字滑动窗口：中文 2-gram，如"登录"能匹配"登录逻辑"
     * 2. 单字兜底：保证中文单字也能参与匹配
     * 3. 英文标识符：用正则提取驼峰/下划线命名，如 UserService、handle_login
     *    （SQL LIKE 不区分大小写，所以同时保留原样和小写）
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) return tokens;
        String lower = text.toLowerCase(Locale.ROOT);

        // 方式 1+2：双字滑动窗口 + 单字兜底（中文 2-gram）
        for (int i = 0; i < lower.length() - 1; i++) tokens.add(lower.substring(i, i + 2));
        for (int i = 0; i < lower.length(); i++) tokens.add(String.valueOf(lower.charAt(i)));

        // 方式 3：英文代码标识符（驼峰/下划线/数字后缀），如 UserService, handle_login, index2
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Za-z][A-Za-z0-9_.$]{1,}")
                .matcher(text);
        while (m.find()) {
            String token = m.group();
            // 保留原样（SQL LIKE 不区分大小写时也能匹配大写类名）
            tokens.add(token);
            tokens.add(token.toLowerCase(Locale.ROOT));
        }

        // 过滤过短的 token（中文单字保留，英文至少 2 字符）
        tokens.removeIf(t -> t.length() < 2 && t.matches("[A-Za-z0-9_]+"));
        return tokens;
    }
}
