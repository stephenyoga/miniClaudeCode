# Mini Claude Code

基于 DeepSeek API 的智能编程助手命令行工具，仿 Claude Code 交互体验。支持 **ReAct 即时推理**和 **Plan-and-Execute 计划执行** 双模式。

## 功能特性

- **双模式 Agent** — ReAct 即时推理 + Plan-and-Execute 计划执行，简单/复杂任务自动切换
- **流式响应** — 思考过程和回答实时逐字输出（SSE）
- **思考模式** — 支持 `/thinking` 开关，可切换深度思考/快速响应
- **工具调用** — 内置文件读写、命令执行、目录浏览、项目创建共 6 个工具
- **任务规划** — LLM 拆解复杂任务为 DAG 任务图，拓扑排序后逐项执行
- **进度可视化** — 进度条 + 任务状态图标实时刷新
- **共享上下文** — Plan 和 ReAct 模式共享对话历史，操作结果可跨模式延续
- **OS 自适应** — 自动检测操作系统，命令格式自动适配 Windows/Linux
- **Token 统计** — 实时统计输入/输出 Token 消耗
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

复杂任务自动判断规则：输入长度 > 50 字符，或包含 3 个以上动作关键词("创建""编译""运行""项目"等)。

## 内置命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助信息 |
| `/plan` | 切换 ReAct / Plan-and-Execute 模式 |
| `/thinking` | 切换思考模式（开启/关闭） |
| `/effort` | 查看当前思考强度 |
| `/effort high/max` | 设置思考强度 |
| `/tokens` | 查看 Token 使用统计 |
| `/reset` | 重置统计数据 |
| `/info` | 显示系统信息 |
| `/clear` | 清空对话历史 |
| `/exit` | 退出程序 |

## 项目结构

```
src/main/java/com/claudecode/
├── agent/
│   ├── Agent.java                 # ReAct Agent，即时推理循环
│   ├── PlanAndExecuteAgent.java   # Plan-and-Execute Agent，规划→执行→总结
│   └── ConversationManager.java   # 共享对话上下文管理器
├── cli/
│   └── Main.java                  # 命令行入口，模式分发
├── config/
│   └── EnvConfig.java             # .env 环境配置加载
├── llm/
│   ├── DeepSeekClient.java        # DeepSeek API 客户端（含流式 SSE）
│   ├── LLMClient.java             # LLM 抽象基类
│   ├── LLMModels.java             # 消息/工具/响应模型
│   └── StreamCallback.java        # 流式回调接口
├── plan/
│   ├── Task.java                  # 任务模型，含 DAG 依赖关系
│   ├── TaskType.java              # 任务类型枚举
│   ├── TaskStatus.java            # 任务状态枚举
│   ├── ExecutionPlan.java         # 执行计划，含拓扑排序
│   ├── PlanStatus.java            # 计划状态枚举
│   └── Planner.java               # 规划器，LLM 生成 JSON 任务计划
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
  │     ├─ LLM 对话 + 工具调用
  │     └─ 流式输出思考过程 & 回答
  │
  └─ 复杂任务 → PlanAndExecuteAgent
        ├─ Planner → LLM 生成 JSON 任务计划
        ├─ 拓扑排序 → 计算执行顺序
        ├─ 逐任务执行 → LLM 翻译参数 → 工具调用
        └─ 回写摘要到共享上下文
              │
              └─ ConversationManager (共享上下文)
                    ├─ Agent 读写
                    └─ PlanAndExecuteAgent 回写
```

## 技术栈

- **Java 25** — 核心语言
- **OkHttp 4.12** — HTTP 客户端 + SSE 流式读取
- **Jackson 2.16** — JSON 序列化
- **SLF4J** — 日志门面
- **DeepSeek API** — 大语言模型服务

## 开发者

**希冀不是洗衣机** — [@stephenyoga](https://github.com/stephenyoga)
