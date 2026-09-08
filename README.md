# Mini Claude CLI

基于 DeepSeek API 的智能编程助手命令行工具，支持 **ReAct / Plan-and-Execute / Team** 三种运行模式，内置记忆系统与 RAG 代码检索。

## 快速开始

```bash
git clone https://github.com/stephenyoga/miniClaudeCode.git
cd miniClaudeCode
```

复制 `.env.example` 为 `.env`，填入 API Key：

```env
DEEPSEEK_API_KEY=sk-your-api-key-here
DEEPSEEK_MODEL=deepseek-v4-pro
```

编译运行：

```bash
mvn compile exec:java
```

## 运行模式

| 模式 | 命令 | 说明 |
|------|------|------|
| **ReAct** | 默认 | 即时推理，适合问答、文件读写等单步操作 |
| **Plan-and-Execute** | `/plan` | 先规划再执行，适合创建项目、多文件操作等复杂任务 |
| **Team（多 Agent）** | `/team` | Planner → Worker → Reviewer 分工协作，带质量审查与重试 |

## 内置命令

| 命令 | 说明 |
|------|------|
| `/plan` / `/team` | 切换运行模式 |
| `/hplan` | 分层规划（先定阶段 → 再细化） |
| `/thinking` | 切换思考模式（默认关闭） |
| `/memory` | 查看记忆状态 |
| `/save <事实>` | 手动保存长期记忆 |
| `/index <路径>` | 索引代码库到 RAG 向量存储 |
| `/search <查询>` | 混合检索代码 |
| `/clear` / `/help` / `/exit` | 清空 / 帮助 / 退出 |

## 项目结构

```
src/main/java/com/claudecode/
├── agent/          Agent 引擎（ReAct / Plan-and-Execute / SubAgent / Orchestrator）
├── cli/            命令行入口
├── config/         .env 配置加载
├── llm/            DeepSeek API 封装（流式、工具调用）
├── memory/         记忆系统（短期 FIFO + 长期 JSON 持久化 + 中文检索 + 上下文压缩）
├── plan/           DAG 规划引擎（拓扑排序、分层规划、并行调度）
├── rag/            代码索引与混合检索（SQLite + Embedding + JavaParser AST）
└── tool/           工具注册与执行
```

## 评测（评测集）

`benchmark/` 内含针对本项目能力的自动化评测集（12 个用例），覆盖 ReAct 编码、文件/Shell 工具、Plan-and-Execute、多 Agent(Team)、会话与跨会话记忆、RAG 语义检索。每个用例在隔离沙盒中真实运行 Agent，再由 LLM(judge) 按用例评分标准打分。

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes;$(cat target/cp.txt)"
java -Dfile.encoding=UTF-8 -cp "$CP" com.claudecode.eval.EvalRunner \
     "$PWD/.env" "$PWD/benchmark"
```

运行结果写入 `benchmark/results/`（单例评分 + `report.md` 汇总）。详细字段与用法见 `benchmark/README.md`。驱动器源码位于 `src/main/java/com/claudecode/eval/`。RAG 用例依赖本地 Ollama embedding，不可用时自动 SKIP。

## 技术栈

Java 25 · OkHttp · Jackson · SQLite · JavaParser · DeepSeek API

## 开发者

[@stephenyoga](https://github.com/stephenyoga)
