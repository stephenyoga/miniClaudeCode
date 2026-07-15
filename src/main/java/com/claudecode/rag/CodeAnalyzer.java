package com.claudecode.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 代码分析器 —— 基于 JavaParser AST 提取代码关系图谱。
 *
 * 提取 5 种关系类型：
 * - extends:    类继承（A extends B）
 * - implements: 接口实现（A implements B）
 * - imports:    导入依赖（import com.example.Service）
 * - contains:   包含关系（类中有哪些方法）
 * - calls:      方法调用（A.method 调用了 B.method）
 *
 * 关系数据存入 SQLite code_relations 表，可通过 /search 查询。
 */
public class CodeAnalyzer {

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public List<CodeRelation> analyzeFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String path = filePath.toString();
        List<CodeRelation> relations = new ArrayList<>();

        var result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) return relations;

        var cu = result.getResult().get();

        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (!name.startsWith("java.") && !name.startsWith("javax.")) {
                relations.add(new CodeRelation(path, "file", null,
                        name.substring(name.lastIndexOf('.') + 1), "imports"));
            }
        }

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String cn = clazz.getNameAsString();

            clazz.getExtendedTypes().forEach(ext ->
                    relations.add(new CodeRelation(path, cn, null, ext.getNameAsString(), "extends")));
            clazz.getImplementedTypes().forEach(impl ->
                    relations.add(new CodeRelation(path, cn, null, impl.getNameAsString(), "implements")));

            clazz.getMethods().forEach(method ->
                    relations.add(new CodeRelation(path, cn, path, cn + "." + method.getNameAsString(), "contains")));

            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                findParentMethod(call).ifPresent(m ->
                        relations.add(new CodeRelation(path, cn + "." + m.getNameAsString(),
                                null, call.getNameAsString(), "calls")));
            });
        });

        return relations;
    }

    private Optional<MethodDeclaration> findParentMethod(Node node) {
        Node cur = node;
        while (cur != null) {
            if (cur instanceof MethodDeclaration m) return Optional.of(m);
            cur = cur.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
