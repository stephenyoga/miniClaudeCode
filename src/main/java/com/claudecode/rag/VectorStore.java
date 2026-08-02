package com.claudecode.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 向量存储 —— 管理代码块和代码关系的持久化。
 *
 * 两张表：
 * - code_chunks：存储代码块的文本和向量，支持余弦相似度搜索
 * - code_relations：存储类/方法间的依赖关系（继承、调用、包含等）
 *
 * 向量以 JSON 数组形式存为 TEXT 字段（如 "[0.1, 0.2, ...]"），
 * 检索时全表逐条计算余弦相似度。对于几百到几千个块的规模足够了。
 * 如果代码库很大（10万+块），可以换 FAISS 或 pgvector。
 *
 * 注意：每个项目一个独立的 project_path 隔离，同一个 SQLite 文件可存多个项目。
 */
public class VectorStore implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection conn;       // SQLite 连接
    private final String projectPath;    // 当前项目的绝对路径（用于隔离数据）

    public VectorStore(String projectPath) throws SQLException {
        this.projectPath = projectPath;
        // 数据库文件位置：rag_db/codebase.db（可用 -Drag.dir 覆盖）
        String dbDir = System.getProperty("rag.dir", "rag_db");
        new java.io.File(dbDir).mkdirs();
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbDir + "/codebase.db");
        initTables();
    }

    /**
     * 初始化数据库表（幂等：IF NOT EXISTS，重复运行不报错）。
     * code_chunks 存代码块和向量，code_relations 存代码关系。
     */
    private void initTables() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS code_chunks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_path TEXT NOT NULL," +      // 项目隔离
                    "file_path TEXT NOT NULL," +          // 源文件路径
                    "chunk_type TEXT NOT NULL," +         // file / class / method
                    "name TEXT NOT NULL," +               // 类名/方法签名
                    "content TEXT NOT NULL," +            // 代码内容
                    "embedding_json TEXT," +              // 向量（JSON 数组）
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS code_relations (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_path TEXT NOT NULL," +
                    "from_file TEXT NOT NULL," +
                    "from_name TEXT NOT NULL," +
                    "to_file TEXT," +
                    "to_name TEXT," +
                    "relation_type TEXT NOT NULL," +      // extends/imports/calls...
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_project ON code_chunks(project_path)");
        }
    }

    /** 清空当前项目的索引数据（重新索引前调用） */
    public void clearProject() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM code_chunks WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM code_relations WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            ps.executeUpdate();
        }
    }

    /**
     * 批量插入代码块（事务保护）。
     * 用 addBatch + executeBatch 一次提交，比逐条插入快得多。
     */
    public void insertChunks(List<CodeChunkEntry> entries) throws SQLException {
        String sql = "INSERT INTO code_chunks (project_path, file_path, chunk_type, name, content, embedding_json) VALUES (?,?,?,?,?,?)";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (CodeChunkEntry e : entries) {
                ps.setString(1, projectPath);
                ps.setString(2, e.chunk.filePath());
                ps.setString(3, e.chunk.chunkType());
                ps.setString(4, e.chunk.name());
                ps.setString(5, e.chunk.content());
                ps.setString(6, embeddingToJson(e.embedding));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** 批量插入代码关系（事务保护） */
    public void insertRelations(List<CodeRelation> relations) throws SQLException {
        String sql = "INSERT INTO code_relations (project_path, from_file, from_name, to_file, to_name, relation_type) VALUES (?,?,?,?,?,?)";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (CodeRelation r : relations) {
                ps.setString(1, projectPath);
                ps.setString(2, r.fromFile());
                ps.setString(3, r.fromName());
                ps.setString(4, r.toFile());
                ps.setString(5, r.toName());
                ps.setString(6, r.relationType());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /**
     * 语义检索：按查询向量找出最相似的 TopK 代码块。
     * 全表扫描，逐条算余弦相似度，排序后取 TopK。
     */
    public List<SearchResult> search(float[] queryEmb, int topK) throws SQLException {
        String sql = "SELECT file_path, chunk_type, name, content, embedding_json FROM code_chunks WHERE project_path = ?";
        List<SearchResult> candidates = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString("embedding_json");
                    if (json == null || json.isEmpty()) continue;
                    float[] emb = jsonToEmbedding(json);
                    double sim = cosineSimilarity(queryEmb, emb);
                    candidates.add(new SearchResult(rs.getString("file_path"), rs.getString("chunk_type"),
                            rs.getString("name"), rs.getString("content"), sim));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
    }

    /**
     * 关键词检索：按类名/方法名/内容精确匹配（SQL LIKE）。
     * 返回的相似度固定 0.3（因为不是向量匹配，混合检索时会通过关键词加分提升排名）。
     */
    public List<SearchResult> searchByKeyword(String keyword) throws SQLException {
        String sql = "SELECT file_path, chunk_type, name, content FROM code_chunks WHERE project_path = ? AND (name LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')";
        List<SearchResult> results = new ArrayList<>();
        // 转义 LIKE 通配符，防止 % 和 _ 被当作模式匹配
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(rs.getString("file_path"), rs.getString("chunk_type"),
                            rs.getString("name"), rs.getString("content"), 0.3));
                }
            }
        }
        return results;
    }

    /** 统计当前项目的代码块数量（用于判断是否已索引） */
    public VectorStore.IndexStats getStats() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM code_chunks WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                return new IndexStats(rs.next() ? rs.getInt(1) : 0);
            }
        }
    }

    /**
     * 计算两个向量的余弦相似度。
     * cos(a, b) = a·b / (|a| × |b|)，值域 [-1, 1]，越接近 1 表示越相似。
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            nA += a[i] * a[i];
            nB += b[i] * b[i];
        }
        return nA == 0 || nB == 0 ? 0 : dot / (Math.sqrt(nA) * Math.sqrt(nB));
    }

    /** 向量 → JSON 字符串（存库） */
    private String embeddingToJson(float[] emb) {
        try { return mapper.writeValueAsString(emb); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    /** JSON 字符串 → 向量（读取） */
    private float[] jsonToEmbedding(String json) {
        try { return mapper.readValue(json, float[].class); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    /** 带向量的代码块条目 */
    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}
    /** 检索结果 */
    public record SearchResult(String filePath, String chunkType, String name, String content, double similarity) {}
    /** 索引统计 */
    public record IndexStats(int chunkCount) {}
}
