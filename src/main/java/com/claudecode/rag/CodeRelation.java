package com.claudecode.rag;

/**
 * 代码关系 —— 记录类/方法之间的依赖和调用关系。
 *
 * 由 CodeAnalyzer 通过 JavaParser AST 解析提取，关系类型包括：
 * - extends:    类继承（A extends B）
 * - implements: 接口实现（A implements B）
 * - imports:    导入依赖（A 中 import B）
 * - calls:      方法调用（A.method() 调用了 B）
 * - contains:   包含关系（类包含方法）
 */
public record CodeRelation(
        String fromFile,   // 源文件路径
        String fromName,   // 来源类/方法名
        String toFile,     // 目标文件路径（可能为 null，如 JDK 类）
        String toName,     // 目标类/方法名
        String relationType // 关系类型: extends / implements / imports / calls / contains
) {}
