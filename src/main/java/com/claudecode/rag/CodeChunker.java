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
 * 分块目的：代码块越小越精确，embedding 检索时能精确找到"哪个类/哪个方法"。
 * 如果把整个大文件作为一个块，语义向量会"稀释"，检索不到具体方法。
 *
 * 分块策略：
 * - Java 文件：用 JavaParser 解析 AST，按 class 和 method 边界分块
 *   - 类级别：文件中的每个类声明作为一个 chunk（只取类头前 5 行）
 *   - 方法级别：类中的每个方法作为一个 chunk（大方法独立成块）
 *   - AST 解析失败 → 回退到按行分段
 * - 非 Java 文件：按行分段，每段不超过 MAX_CHUNK_CHARS（2000 字符）
 *
 * 每个 chunk 记录：文件路径、块类型（file/class/method）、名称、内容、起止行号。
 * 行号用于检索后快速定位代码位置。
 */
public class CodeChunker {

    /** JavaParser 实例，配置为 Java 17 语法级别（支持 record、text block 等） */
    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    /** 单个 chunk 的最大字符数，防止 embedding 输入过长 */
    private static final int MAX_CHUNK_CHARS = 2000;

    /**
     * 分块入口：根据文件类型选择 Java AST 分块或按行分段。
     * @param filePath 文件路径
     * @return 该文件的代码块列表
     */
    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String path = filePath.toString();

        if (!path.endsWith(".java")) {
            return chunkLargeText(path, content);
        }
        return chunkJavaFile(path, content);
    }

    /**
     * 非 Java 文件按行分段。
     * 逐行累积到 segment，超过 MAX_CHUNK_CHARS 就切出一块，继续累积下一块。
     */
    private List<CodeChunk> chunkLargeText(String filePath, String content) {
        // 文件不大，直接整个文件作为一个块
        if (content.length() <= MAX_CHUNK_CHARS) {
            return List.of(CodeChunk.fileChunk(filePath, content));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        StringBuilder seg = new StringBuilder();
        int idx = 1, startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            // 加上当前行会超上限，且已有内容 → 切出一块
            if (seg.length() + lines[i].length() + 1 > MAX_CHUNK_CHARS && !seg.isEmpty()) {
                chunks.add(new CodeChunk(filePath, "file", filePath + "#" + idx,
                        seg.toString().trim(), startLine, i));
                seg.setLength(0);
                idx++;
                startLine = i + 1;
            }
            seg.append(lines[i]).append("\n");
        }
        // 处理最后一段
        if (!seg.isEmpty()) {
            chunks.add(new CodeChunk(filePath, "file", filePath + "#" + idx,
                    seg.toString().trim(), startLine, lines.length));
        }
        return chunks;
    }

    /**
     * Java 文件用 AST 解析分块。
     *
     * 对每个类/接口声明：
     * 1. 生成一个 class 级 chunk（内容取类头前 5 行，含类名和签名）
     * 2. 对类内每个方法生成一个 method 级 chunk（内容取完整方法体）
     *
     * chunk 的 name 用"类名.方法签名"（如 UserService.login(String)），
     * 检索时能精确匹配方法。
     */
    private List<CodeChunk> chunkJavaFile(String filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        var result = parser.parse(content);
        // AST 解析失败（语法错误等）→ 回退到按行分段
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return chunkLargeText(filePath, content);
        }

        var cu = result.getResult().get();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            int cs = clazz.getBegin().map(p -> p.line).orElse(0);
            int ce = clazz.getEnd().map(p -> p.line).orElse(0);
            // 类头前 5 行（含类名、extends/implements 签名）
            String header = extractLines(content, cs, Math.min(cs + 5, ce));
            chunks.add(CodeChunk.classChunk(filePath, clazz.getNameAsString(), header, cs, ce));

            // 每个方法独立成块
            clazz.getMethods().forEach(method -> {
                int ms = method.getBegin().map(p -> p.line).orElse(0);
                int me = method.getEnd().map(p -> p.line).orElse(0);
                String sig = method.getDeclarationAsString(false, false, false);
                String body = extractLines(content, ms, me);
                chunks.add(CodeChunk.methodChunk(filePath,
                        clazz.getNameAsString() + "." + sig, body, ms, me));
            });
        });

        // 空文件或没有类声明 → 回退到按行分段
        if (chunks.isEmpty()) return chunkLargeText(filePath, content);
        return chunks;
    }

    /** 提取文件内容的指定行区间（第 start 行到第 end 行），用于取 chunk 的正文 */
    private String extractLines(String content, int start, int end) {
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < Math.min(end, lines.length); i++) {
            if (i >= 0) sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
