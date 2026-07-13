package com.claudecode.rag;

import java.sql.SQLException;
import java.util.*;

/**
 * 代码检索器：语义检索 + 关键词检索的统一入口
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

    /** 简单的双字分词（与 MemoryQueryTokenizer 一致） */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        String t = text.toLowerCase();
        for (int i = 0; i < t.length() - 1; i++) tokens.add(t.substring(i, i + 2));
        for (int i = 0; i < t.length(); i++) tokens.add(String.valueOf(t.charAt(i)));
        return tokens;
    }
}
