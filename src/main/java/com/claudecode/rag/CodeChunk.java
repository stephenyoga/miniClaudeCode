package com.claudecode.rag;

/**
 * 代码块数据模型
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

    public String toEmbeddingText() {
        return String.format("[%s:%s] %s", chunkType, name, content);
    }
}
