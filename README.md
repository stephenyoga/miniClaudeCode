# Mini Claude CLI

基于 DeepSeek API 的智能编程助手命令行工具，仿 Claude Code 交互体验。支持 **ReAct 即时推理**和 **Plan-and-Execute 规划执行** 双模式。

## 功能特性

- **双模式 Agent** — ReAct 即时推理 + Plan-and-Execute 规划执行，简单/复杂任务自动切换
- **分层规划** — 先定宏观阶段，再逐阶段细化子任务，大型项目可灵活调整局部
- **并行执行** — DAG 无依赖冲突的任务同时执行（虚拟线程 + CompletableFuture）
- **自我修正** — 执行失败率 >50% 时自动触发重新规划，基于失败原因调整计划
- **记忆系统** — Token 预算驱动 FIFO 淘汰 + 中文分词检索 + 时间衰减排序 + 跨会话持久化
- **上下文压缩** — Map-Reduce 策略，旧消息分 5 条一组调 LLM 摘要，保留最近 3 轮
- **事实提取** — 对话结束自动提取关键事实（用户偏好、项目配置）到长期记忆
- **流式响应** — 思考过程和回答实时逐字输出（SSE）
- **思考模式** — 支持 `/thinking` 开关，可切换深度思考/快速响应
- **进度可视化** — 进度条 + 任务状态图标实时刷新
- **OS 自适应** — 自动检测操作系统，注入 System Prompt，命令格式自动适配 Windows/Linux
- **Token 优化** — 非工具调用轮次的 reasoning_content 不进上下文，节省 Token 预算
- **Token 预算** — 根据模型自动匹配上下文窗口（V4 Pro 1M / Chat 64K），预留系统/工具/回复空间
- **多模型支持** — 支持 deepseek-chat、deepseek-v4-pro、deepseek-reasoner 等模型

## 运行环境

- **JDK** 25+
- **Maven** 3.8+
- **DeepSeek API Key** — 从 [DeepSeek 平台](https://platform.deepseek.com/) 获取

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/stephenyoga/miniClaudeCode.git
cd miniClaudeCode
```

### 2. 配置 API Key

复制 `.env.example` 为 `.env`，填入你的 API Key：

```env
DEEPSEEK_API_KEY=sk-your-api-key-here
DEEPSEEK_MODEL=deepseek-v4-pro
```

### 3. 编译运行

```bash
mvn compile exec:java
```

若终端 Emoji 显示为问号，运行前先执行：

```powershell
chcp 65001
```

或在 IDE 中直接运行 `Main.java`。

## 运行模式

| 模式 | 触发方式 | 适用场景 |
|------|---------|---------|
| **ReAct** | 默认，简单对话 | 问答、文件读写、单步操作 |
| **Plan-and-Execute** | `/plan` 强制切换，或复杂任务自动切换 | 创建项目、多文件操作、编译运行 |

复杂任务自动判断规则：输入长度 > 50 字符，或包含 3 个以上动作关键词。

### 分层规划

Plan 模式下可用 `/hplan` 开启分层规划：

```
第一层：宏观阶段划分     → LLM 调 1 次，输出阶段列表
  ├─ 阶段 1：环境搭建
  ├─ 阶段 2：核心功能开发
  └─ 阶段 3：测试验证

第二层：逐阶段细化       → LLM 调 N 次（N=阶段数），各阶段独立细化
  ├─ [环境搭建] → task_1 ~ task_3
  ├─ [核心功能] → task_4 ~ task_7
  └─ [测试验证] → task_8 ~ task_9
```

每个阶段之间有**阶段门**（phase gate）：前序阶段全部任务完成后才进入下一阶段。阶段内支持依赖分析和并行执行。

### 自我修正

Plan 执行中如果失败率超过 50%（且 ≥ 2 次失败），自动触发重新规划：

```
⚠️ 任务失败率 67%(2/3)，正在重新规划...
  → 携带失败摘要调 Planner.replan()
  → ✅ 已生成新计划，继续执行
```

## 记忆系统

### 架构

```
MemoryManager（门面）
  ├─ ConversationMemory：短期记忆，Token 预算驱动 FIFO 淘汰
  ├─ LongTermMemory：长期记忆，自动去重 + 跨会话持久化（JSON）
  ├─ ContextCompressor：Map-Reduce 压缩（每 5 条一组 → LLM 摘要）
  ├─ TokenBudget：预算分配器（窗口 / 系统 / 工具 / 回复）
  └─ MemoryRetriever：分词检索 + 时间衰减 + 来源加权
```

- **中文分词**：双字滑动窗口 + 单字重叠匹配，"学校"可匹配"西南财经大学"
- **检索排序**：关键词匹配 × 时间衰减（24h 衰减至 0.5）× 来源加权（长期记忆 ×1.2）
- **上下文压缩**：使用率超 80% → Map-Reduce 压缩，保留最近 3 轮原样
- **事实提取**：`/save` 或 `/clear` 时调 LLM 提取关键事实到长期记忆
- **存储路径**：`memory_db/long_term_memory.json`（已加入 `.gitignore`）

## 内置命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助信息 |
| `/plan` | 切换 ReAct / Plan-and-Execute 模式 |
| `/hplan` | 切换分层规划（先定阶段 → 再细化任务） |
| `/thinking` | 切换思考模式（开启/关闭） |
| `/effort` | 查看当前思考强度 |
| `/effort high/max` | 设置思考强度 |
| `/memory` | 查看记忆状态和 Token 预算 |
| `/save` | 提取关键事实保存到长期记忆 |
| `/tokens` | 查看 Token 使用统计 |
| `/reset` | 重置统计数据 |
| `/info` | 显示系统信息 |
| `/clear` | 清空对话历史 |
| `/exit` | 退出程序 |

## 项目结构

```
src/main/java/com/claudecode/
├── agent/
│   ├── Agent.java                 # ReAct Agent，即时推理 + 记忆集成
│   └── PlanAndExecuteAgent.java   # Plan-and-Execute Agent，规划→执行→总结
├── cli/
│   └── Main.java                  # 命令行入口，模式分发
├── config/
│   └── EnvConfig.java             # .env 环境配置加载
├── llm/
│   ├── DeepSeekClient.java        # DeepSeek API 客户端（含流式 SSE）
│   ├── LLMClient.java             # LLM 抽象基类
│   ├── LLMModels.java             # 消息/工具/响应模型
│   └── StreamCallback.java        # 流式回调接口
├── memory/
│   ├── Memory.java                # 记忆接口（store / search / clear）
│   ├── MemoryManager.java         # 门面，Agent 统一入口
│   ├── MemoryEntry.java           # 记忆单元（id / content / type / tokenCount）
│   ├── MemoryType.java            # 类型枚举（CONVERSATION / FACT / SUMMARY / TOOL_RESULT）
│   ├── ConversationMemory.java    # 短期记忆，FIFO 淘汰
│   ├── LongTermMemory.java        # 长期记忆，JSON 持久化 + 去重
│   ├── ContextCompressor.java     # Map-Reduce 压缩 + 事实提取
│   ├── TokenBudget.java           # 预算分配器（模型自适应）
│   ├── MemoryRetriever.java       # 分词检索 + 时间衰减排序
│   └── MemoryQueryTokenizer.java  # 中文分词（双字滑动窗口）
├── plan/
│   ├── Task.java                  # 任务模型，含 DAG 依赖关系
│   ├── TaskType.java              # 任务类型枚举（6 种）
│   ├── TaskStatus.java            # 任务状态枚举（5 种）
│   ├── Phase.java                 # 阶段定义（分层规划用）
│   ├── ExecutionPlan.java         # 执行计划，含拓扑排序
│   ├── PlanStatus.java            # 计划状态枚举
│   └── Planner.java               # 规划器（单层 + 分层）
└── tool/
    ├── Tool.java                  # 工具定义 record
    ├── ToolExecutor.java          # 工具执行器接口
    ├── ToolRegistry.java          # 工具注册中心（6 个内置工具）
    ├── Param.java                 # 参数定义 record
    └── ASCIIArtGenerator.java     # ASCII 艺术字生成
```

## 架构

```
用户输入
  │
  ├─ 简单任务 → Agent (ReAct)
  │     ├─ MemoryManager.injectMemoryToSystemPrompt()
  │     │    └─ 检索长期记忆 → 追加到 system prompt
  │     ├─ LLM 对话 + 工具调用循环
  │     ├─ reasoning_content 显示但不存历史（省 Token）
  │     └─ 流式输出思考过程 & 回答
  │
  └─ 复杂任务 → PlanAndExecuteAgent
        ├─ 规划阶段（可选分层）
        │    ├─ /[hplan] 第一层：LLM 输出宏观阶段
        │    └─ 第二层：逐阶段调用 LLM 细化子任务
        ├─ 确认阶段 → 用户反馈 / 取消 / 回车执行
        ├─ 执行阶段
        │    ├─ 拓扑排序 → 计算 DAG 执行顺序
        │    ├─ 并行（虚拟线程 + CompletableFuture）
        │    ├─ 自我修正（失败率 >50% 则重新规划）
        │    └─ 进度条 + 状态图标实时刷新
        ├─ 总结阶段 → LLM 汇总执行结果
        └─ MemoryManager.addPlanSummary() → 共享上下文
```

## 技术栈

- **Java 25** — 核心语言（虚拟线程）
- **OkHttp 4.12** — HTTP 客户端 + SSE 流式读取
- **Jackson 2.16** — JSON 序列化 / 记忆持久化
- **SLF4J** — 日志门面
- **DeepSeek API** — 大语言模型服务（1M 上下文窗口）

## 开发者

**希冀不是洗衣机** — [@stephenyoga](https://github.com/stephenyoga)
