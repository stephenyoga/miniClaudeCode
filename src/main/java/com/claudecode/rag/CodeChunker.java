package com.claudecode.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码分块器 —— 将代码文件切分为适合 Embedding 的粒度。
 *
 * 策略：
 * - Java 文件：用 JavaParser 解析 AST，按 class 和 method 边界分块
 *   - 类级别：文件中的每个类声明作为一个 chunk
 *   - 方法级别：类中的每个方法作为一个 chunk（大方法独立成块）
 * - 非 Java 文件：按行分段，每段不超过 MAX_CHUNK_CHARS（2000 字符）
 *
 * 分块目的：代码块越小越精确，embedding 检索时能精确找到"哪个类/哪个方法"。
 */
public class CodeChunker {

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    private static final int MAX_CHUNK_CHARS = 2000;

    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String path = filePath.toString();

        if (!path.endsWith(".java")) {
            return chunkLargeText(path, content);
        }
        return chunkJavaFile(path, content);
    }

    private List<CodeChunk> chunkLargeText(String filePath, String content) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            return List.of(CodeChunk.fileChunk(filePath, content));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        StringBuilder seg = new StringBuilder();
        int idx = 1, startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            if (seg.length() + lines[i].length() + 1 > MAX_CHUNK_CHARS && !seg.isEmpty()) {
                chunks.add(new CodeChunk(filePath, "file", filePath + "#" + idx,
                        seg.toString().trim(), startLine, i));
                seg.setLength(0);
                idx++;
                startLine = i + 1;
            }
            seg.append(lines[i]).append("\n");
        }
        if (!seg.isEmpty()) {
            chunks.add(new CodeChunk(filePath, "file", filePath + "#" + idx,
                    seg.toString().trim(), startLine, lines.length));
        }
        return chunks;
    }

    private List<CodeChunk> chunkJavaFile(String filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        var result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return chunkLargeText(filePath, content);
        }

        var cu = result.getResult().get();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int cs = clazz.getBegin().map(p -> p.line).orElse(0);
            int ce = clazz.getEnd().map(p -> p.line).orElse(0);
            String header = extractLines(content, cs, Math.min(cs + 5, ce));
            chunks.add(CodeChunk.classChunk(filePath, clazz.getNameAsString(), header, cs, ce));

            clazz.getMethods().forEach(method -> {
                int ms = method.getBegin().map(p -> p.line).orElse(0);
                int me = method.getEnd().map(p -> p.line).orElse(0);
                String sig = method.getDeclarationAsString(false, false, false);
                String body = extractLines(content, ms, me);
                chunks.add(CodeChunk.methodChunk(filePath,
                        clazz.getNameAsString() + "." + sig, body, ms, me));
            });
        });

        if (chunks.isEmpty()) return chunkLargeText(filePath, content);
        return chunks;
    }

    private String extractLines(String content, int start, int end) {
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < Math.min(end, lines.length); i++) {
            if (i >= 0) sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
