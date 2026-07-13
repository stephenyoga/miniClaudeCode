package com.claudecode.rag;

/**
 * 代码关系：记录类/方法间的依赖和调用关系
 */
public record CodeRelation(String fromFile, String fromName,
                           String toFile, String toName, String relationType) {
}
