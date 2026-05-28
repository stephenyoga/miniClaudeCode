#!/usr/bin/env python3
"""简易 Token 统计工具"""
import sys

def count_tokens(text):
    """简单估算token数量（粗略估算：约4个字符=1个token）"""
    if not text:
        return 0
    # 粗略估算
    return len(text) // 4

if __name__ == "__main__":
    if len(sys.argv) > 1:
        text = sys.argv[1]
    else:
        text = sys.stdin.read().strip()
    
    count = count_tokens(text)
    print(f"估算Token数（粗略）：{count}")
    print(f"字符数：{len(text)}")
    print("注意：这只是一个粗略估算，实际token数取决于具体模型的分词器")
