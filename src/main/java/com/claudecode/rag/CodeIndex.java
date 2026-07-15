package com.claudecode.rag;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码索引管理器 —— 将代码库分块 → 向量化 → 持久化到 SQLite。
 *
 * 流程：
 * 1. collectFiles() 遍历项目目录，跳过 node_modules、.git、target 等
 * 2. CodeChunker.chunkFile() 把每个文件切分成语义块（类级/方法级/行级）
 * 3. EmbeddingClient.embed() 把每个块转成向量（调用 Ollama 或 API）
 * 4. CodeAnalyzer.analyzeFile() 提取代码关系（仅 Java 文件）
 * 5. VectorStore 写入 SQLite（code_chunks + code_relations 两张表）
 *
 * 用户命令：/index D:\myproject
 */
public class CodeIndex {

    private final EmbeddingClient embedding;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;

    public CodeIndex() {
        this.embedding = new EmbeddingClient();
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
    }

    /** 索引指定路径，返回 human-readable 状态信息 */
    public String index(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) return "❌ 路径不存在: " + projectPath;

        List<Path> files = new ArrayList<>();
        collectFiles(root, files);

        List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        int errors = 0;

        for (int i = 0; i < files.size(); i++) {
            Path f = files.get(i);
            if (i % 10 == 0 || i == files.size() - 1) {
                System.out.println("   进度: " + (i + 1) + "/" + files.size() + " (" + f.getFileName() + ")");
            }
            try {
                for (CodeChunk chunk : chunker.chunkFile(f)) {
                    float[] emb = embedding.embed(chunk.toEmbeddingText());
                    entries.add(new VectorStore.CodeChunkEntry(chunk, emb));
                }
                if (f.toString().endsWith(".java")) {
                    relations.addAll(analyzer.analyzeFile(f));
                }
            } catch (Exception e) {
                System.err.println("   ⚠️ 索引失败: " + f + " - " + e.getMessage());
                errors++;
            }
        }

        try (VectorStore store = new VectorStore(root.toString())) {
            store.clearProject();
            store.insertChunks(entries);
            store.insertRelations(relations);
            var stats = store.getStats();
            return String.format("✅ 索引完成：%d 个文件，%d 个代码块，%d 条关系%s",
                    files.size(), stats.chunkCount(), relations.size(),
                    errors > 0 ? " (" + errors + " 个文件失败)" : "");
        } catch (Exception e) {
            return "❌ 持久化失败: " + e.getMessage();
        }
    }

    private void collectFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals("node_modules") || name.equals("target") || name.equals("build")
                            || name.equals(".git") || name.equals(".idea") || name.equals(".vscode")
                            || name.equals("dist") || name.equals("out") || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (n.endsWith(".java") || n.endsWith(".py") || n.endsWith(".js") || n.endsWith(".ts")
                            || n.endsWith(".go") || n.endsWith(".rs") || n.endsWith(".c") || n.endsWith(".cpp")
                            || n.endsWith(".h") || n.endsWith(".md") || n.endsWith(".xml")
                            || n.endsWith(".properties") || n.endsWith(".yaml") || n.endsWith(".yml")
                            || n.endsWith(".json") || n.endsWith(".sh") || n.endsWith(".kt")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("❌ 遍历文件失败: " + e.getMessage());
        }
    }
}
