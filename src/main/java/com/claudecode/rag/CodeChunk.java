package com.claudecode.rag;

/**
 * 代码块数据模型 —— RAG 索引的最小单元。
 *
 * 代码文件被 CodeChunker 切分成多个 CodeChunk，每个 chunk 代表：
 * - 一个完整的文件（小文件）
 * - 一个类声明（Java 文件）
 * - 一个方法声明（Java 文件中的大方法）
 * - 文件的一部分（大文件的按行分段）
 *
 * @param filePath  源文件路径
 * @param chunkType 块类型: file / class / method
 * @param name      名称：类名、方法签名或文件名
 * @param content   代码内容文本
 * @param startLine 起始行号
 * @param endLine   结束行号
 */
public record CodeChunk(String filePath, String chunkType, String name,
                        String content, int startLine, int endLine) {

    public static CodeChunk fileChunk(String filePath, String content) {
        return new CodeChunk(filePath, "file", filePath, content, 0, 0);
    }

    public static CodeChunk classChunk(String filePath, String className,
                                       String content, int startLine, int endLine) {
        return new CodeChunk(filePath, "class", className, content, startLine, endLine);
    }

    public static CodeChunk methodChunk(String filePath, String methodName,
                                        String content, int startLine, int endLine) {
        return new CodeChunk(filePath, "method", methodName, content, startLine, endLine);
    }

    /** 生成用于 Embedding 的文本（类型 + 名称 + 内容） */
    public String toEmbeddingText() {
        return String.format("[%s:%s] %s", chunkType, name, content);
    }
}
