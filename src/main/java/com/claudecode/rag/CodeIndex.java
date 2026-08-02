package com.claudecode.rag;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码索引管理器 —— 将代码库分块 → 向量化 → 持久化到 SQLite。
 *
 * 完整索引流程：
 * 1. collectFiles() 遍历项目目录，跳过 node_modules、.git、target 等非代码目录
 * 2. CodeChunker.chunkFile() 把每个文件切分成语义块（类级/方法级/行级）
 * 3. EmbeddingClient.embed() 把每个块转成向量（调用 Ollama 或 API）
 * 4. CodeAnalyzer.analyzeFile() 提取代码关系（仅 Java 文件）
 * 5. VectorStore 写入 SQLite（code_chunks + code_relations 两张表）
 *
 * 用户命令：/index D:\myproject
 * 索引完成后，Agent 可通过 search_code 工具或 /search 命令检索代码库。
 */
public class CodeIndex {

    private final EmbeddingClient embedding;  // 生成向量的客户端
    private final CodeChunker chunker;        // 代码分块器
    private final CodeAnalyzer analyzer;      // 代码关系分析器

    public CodeIndex() {
        this.embedding = new EmbeddingClient();
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
    }

    /**
     * 索引指定路径的代码库。
     *
     * 流程：遍历文件 → 逐个分块 + 向量化 → 分析关系 → 写入 SQLite。
     * 每 10 个文件打印一次进度，方便用户观察索引过程。
     *
     * @param projectPath 项目根目录
     * @return 索引结果描述（成功/失败 + 统计信息）
     */
    public String index(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) return "❌ 路径不存在: " + projectPath;

        // 1. 收集需要索引的文件列表
        List<Path> files = new ArrayList<>();
        collectFiles(root, files);

        List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        int errors = 0;

        // 2. 逐文件处理：分块 + 向量化 + 关系分析
        for (int i = 0; i < files.size(); i++) {
            Path f = files.get(i);
            if (i % 10 == 0 || i == files.size() - 1) {
                System.out.println("   进度: " + (i + 1) + "/" + files.size() + " (" + f.getFileName() + ")");
            }
            try {
                // 每个代码块生成向量
                for (CodeChunk chunk : chunker.chunkFile(f)) {
                    float[] emb = embedding.embed(chunk.toEmbeddingText());
                    entries.add(new VectorStore.CodeChunkEntry(chunk, emb));
                }
                // Java 文件额外提取代码关系
                if (f.toString().endsWith(".java")) {
                    relations.addAll(analyzer.analyzeFile(f));
                }
            } catch (Exception e) {
                System.err.println("   ⚠️ 索引失败: " + f + " - " + e.getMessage());
                errors++;
            }
        }

        // 3. 全部处理完后一次性写入 SQLite（清空旧索引，避免重复）
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

    /**
     * 递归遍历目录，收集所有需要索引的代码文件。
     * 使用 Files.walkFileTree（NIO），比递归更容易控制目录跳过。
     *
     * 跳过的目录：node_modules、target、build、.git、.idea、.vscode、dist、out、隐藏目录（.开头）
     * 只索引文本代码文件：java/py/js/ts/go/rs/c/cpp/h/md/xml/properties/yaml/yml/json/sh/kt
     */
    private void collectFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    // 跳过常见非代码目录（SKIP_SUBTREE 表示不进入该子目录）
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
                    // 只索引代码/配置文本文件（按扩展名过滤）
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
                    // 无法访问的文件直接跳过，不影响整体索引
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("❌ 遍历文件失败: " + e.getMessage());
        }
    }
}
