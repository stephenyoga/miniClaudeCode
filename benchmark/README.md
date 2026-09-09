# Mini Claude Code 评测集

针对本项目能力面的自动化评测集：ReAct 编码、文件/Shell 工具、Plan-and-Execute、多 Agent(Team)、会话与跨会话记忆、RAG 语义检索，以及**大仓库跨文件修改 + git diff 审查**的 hard 难例。每个用例在**隔离沙盒目录**中真实驱动 Agent 运行，再由 LLM(judge) 依据每用例的评分标准打分。

用例分两层：`001~012` 为基线（小任务、快速回归）；`013~015` 为 hard 层（预置多模块仓库 `seed_shop`，测跨文件 bug 修复 / 新功能 / 行为不变重构，并用沙盒内 git baseline + diff 审查改动范围）。

## 目录结构

```
benchmark/
├── README.md            本说明
├── cases/               用例定义（JSON），见"用例字段"
├── assets/              预置素材（bug 代码、登录 demo 代码库等），随用例复制进沙盒
├── workspaces/          运行期沙盒（每用例独立目录，自动生成，已 gitignore）
├── results/             评分结果：<id>.json + report.md（自动生成，已 gitignore）
└── .tmp/                中间产物（子进程日志/transcript，已 gitignore）
src/main/java/com/claudecode/eval/  评测驱动器源码
```

## 快速开始

前置：`mvn compile` 通过、项目根 `.env` 有可用的 `DEEPSEEK_API_KEY`。
RAG 用例(011)额外需要 Ollama（`localhost:11434` 提供 embedding）；不可用时会自动标记 SKIP，不影响其余用例。

```bash
# 首次：生成依赖 classpath
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

# 全量跑（12 个用例，约 20~50 分钟，消耗 DeepSeek token）
CP="target/classes;$(cat target/cp.txt)"
java -Dfile.encoding=UTF-8 -cp "$CP" com.claudecode.eval.EvalRunner \
     'D:\实习\miniClaudeCode\.env' 'D:\实习\miniClaudeCode\benchmark'

# 只跑部分用例：按 id 前缀过滤（逗号分隔）
java -Dfile.encoding=UTF-8 -cp "$CP" com.claudecode.eval.EvalRunner \
     'D:\实习\miniClaudeCode\.env' 'D:\实习\miniClaudeCode\benchmark' "003_react,008_memory"
```

产出：
- `results/<id>.json`：单用例评分（分数、各维度评语、总评）。
- `results/report.md`：全部用例汇总表。

## 用例字段说明

```jsonc
{
  "id": "唯一ID（英文+下划线）",
  "title": "标题",
  "dimension": "react_codegen | react_bugfix | tool_write_run | tool_create_project | code_navigation | plan_execute | memory_session | memory_cross_session | team_collab | rag_semantic | react_qna",
  "mode": "react | plan | team",          // Agent 运行方式
  "enabled": true,
  "requires": ["deepseek"],               // 依赖声明；探测不到则 SKIP
  "promptDoc": "任务一句话描述",           // 可选：进 judge 转录供其理解任务
  "workspace": { "seedDir": "seed_login" }, // 可选：assets 下预置目录，复制进沙盒
                                            // 可选: "git": true → 沙盒先 git init+baseline，跑完采集 git diff 供 judge 审查改动范围
  "difficulty": "hard",                   // 可选标记（easy/medium/hard），仅信息用
  "preIndex": true,                       // 可选：会话0前先 /index 沙盒（RAG 用例）
  "saveFacts": true,                      // 可选：本轮 run 后抽取事实存入长期记忆
  "sessions": [                           // 每项在独立 fork 的子进程执行（沙盒共享）
    { "prompt": "用户输入" },
    { "turns": ["连续追问1", "追问2"] }    // 多轮：同一会话内连续提问（测会话记忆）
  ],
  "expectation": "给 judge 的参考答案/期望",
  "judge": { "maxScore": 100, "criteria": ["分维度评分标准…"] }
}
```

## 工作原理

- 每个 `(用例, 会话)` 由 `EvalRunner` fork 一个独立子 JVM，子进程工作目录 = 沙盒。
  因此 Agent 工具的相对路径写文件、`execute_command` 默认 shell 都在沙盒内执行，隔离且不污染项目。
- `CaseRunner`（子进程）装配与 `Main` 一致：加载 `.env`、`MemoryManager`、system prompt 来自
  `prompts/modes/agent.md`，再按 mode 调用 `Agent.run` / `PlanAndExecuteAgent` / `AgentOrchestrator.run`。
- 跑完后把「用户输入 + Agent 最终答复 + 会话消息摘要（含工具调用）+ 沙盒产物快照」打包为 transcript。
- `EvalRunner`（父进程）用 DeepSeek 按用例 rubric 对 transcript 打分，标准要求 judge 只输出 JSON。

## 新增一个用例

1. 在 `cases/` 新建 `<NN>_<name>.json`，参照字段说明填写。
2. 如需预置代码素材，放入 `assets/<dir>/` 并在 `workspace.seedDir` 引用。
3. 复用现有 dimension 或自定义；`criteria` 写得越具体，打分越稳定。
4. 小样先跑 `EvalRunner ... <过滤前缀>` 验证，再合入全量。

## 注意

- 评测会真实调用 DeepSeek API，产生 token 消耗；全量约几十万~百万 token 量级，请留意额度。
- judge 打分存在模型主观性；同一用例多次跑分数会有波动，宜看整体趋势而非单次绝对值。
