package com.claudecode.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 向量存储，管理代码块和代码关系
 */
public class VectorStore implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection conn;
    private final String projectPath;

    public VectorStore(String projectPath) throws SQLException {
        this.projectPath = projectPath;
        String dbDir = System.getProperty("rag.dir", "rag_db");
        new java.io.File(dbDir).mkdirs();
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbDir + "/codebase.db");
        initTables();
    }

    private void initTables() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS code_chunks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_path TEXT NOT NULL," +
                    "file_path TEXT NOT NULL," +
                    "chunk_type TEXT NOT NULL," +
                    "name TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "embedding_json TEXT," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS code_relations (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_path TEXT NOT NULL," +
                    "from_file TEXT NOT NULL," +
                    "from_name TEXT NOT NULL," +
                    "to_file TEXT," +
                    "to_name TEXT," +
                    "relation_type TEXT NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_project ON code_chunks(project_path)");
        }
    }

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

    public List<SearchResult> searchByKeyword(String keyword) throws SQLException {
        String sql = "SELECT file_path, chunk_type, name, content FROM code_chunks WHERE project_path = ? AND (name LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')";
        List<SearchResult> results = new ArrayList<>();
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

    public VectorStore.IndexStats getStats() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM code_chunks WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                return new IndexStats(rs.next() ? rs.getInt(1) : 0);
            }
        }
    }

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

    private String embeddingToJson(float[] emb) {
        try { return mapper.writeValueAsString(emb); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    private float[] jsonToEmbedding(String json) {
        try { return mapper.readValue(json, float[].class); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}
    public record SearchResult(String filePath, String chunkType, String name, String content, double similarity) {}
    public record IndexStats(int chunkCount) {}
}
