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
 * - imports:    导入依赖（import com.example.Service，跳过 JDK 自带包）
 * - contains:   包含关系（类中有哪些方法）
 * - calls:      方法调用（A.method 调用了 B.method，只记录同文件内的调用）
 *
 * 关系数据存入 SQLite code_relations 表。
 * 用途：构建代码调用链图谱，回答"谁调用了这个方法""这个类依赖哪些类"类的问题。
 */
public class CodeAnalyzer {

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /**
     * 分析单个 Java 文件，提取所有代码关系。
     * @param filePath 文件路径
     * @return 关系列表（imports + 类级 + 方法调用）
     */
    public List<CodeRelation> analyzeFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String path = filePath.toString();
        List<CodeRelation> relations = new ArrayList<>();

        var result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) return relations;

        var cu = result.getResult().get();

        // 1. 提取 import 关系（跳过 JDK 自带包 java.*/javax.*，只保留项目内/第三方依赖）
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (!name.startsWith("java.") && !name.startsWith("javax.")) {
                relations.add(new CodeRelation(path, "file", null,
                        name.substring(name.lastIndexOf('.') + 1), "imports"));
            }
        }

        // 2. 提取类级别关系（extends / implements / contains / calls）
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String cn = clazz.getNameAsString();

            // extends 继承关系
            clazz.getExtendedTypes().forEach(ext ->
                    relations.add(new CodeRelation(path, cn, null, ext.getNameAsString(), "extends")));

            // implements 实现关系
            clazz.getImplementedTypes().forEach(impl ->
                    relations.add(new CodeRelation(path, cn, null, impl.getNameAsString(), "implements")));

            // contains 包含关系：类里有哪些方法
            clazz.getMethods().forEach(method ->
                    relations.add(new CodeRelation(path, cn, path, cn + "." + method.getNameAsString(), "contains")));

            // calls 调用关系：方法体内调用了哪些方法
            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                // 找到这个调用所属的父方法（调用者）
                findParentMethod(call).ifPresent(m ->
                        relations.add(new CodeRelation(path, cn + "." + m.getNameAsString(),
                                null, call.getNameAsString(), "calls")));
            });
        });

        return relations;
    }

    /**
     * 从 AST 节点向上查找它所属的方法声明。
     * 用于确定"这个方法调用发生在哪个方法内部"。
     */
    private Optional<MethodDeclaration> findParentMethod(Node node) {
        Node cur = node;
        while (cur != null) {
            if (cur instanceof MethodDeclaration m) return Optional.of(m);
            cur = cur.getParentNode().orElse(null);  // 沿 AST 父链向上找
        }
        return Optional.empty();
    }
}
