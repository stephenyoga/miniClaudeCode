# Mini Claude Code

基于 DeepSeek API 的智能编程助手命令行工具，仿 Claude Code 交互体验。

## 功能特性

- **交互式 CLI** — 命令行交互界面，支持多轮对话
- **流式响应** — 思考过程和回答实时逐字输出
- **思考模式** — 支持 `/thinking` 开关，可切换深度思考/快速响应
- **工具调用** — 内置文件读写、命令执行、目录浏览、项目创建等工具
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

在项目根目录创建 `.env` 文件：

```env
DEEPSEEK_API_KEY=sk-your-api-key-here
DEEPSEEK_MODEL=deepseek-v4-pro
```

### 3. 编译运行

```bash
mvn compile exec:java -Dexec.mainClass="com.claudecode.cli.Main"
```

或在 IDE 中直接运行 `Main.java`。

## 内置命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助信息 |
| `/thinking` | 切换思考模式（开启/关闭） |
| `/effort` | 查看/设置思考强度（high/max） |
| `/tokens` | 查看 Token 使用统计 |
| `/reset` | 重置统计数据 |
| `/info` | 显示系统信息 |
| `/clear` | 清空对话历史 |
| `/exit` | 退出程序 |

## 项目结构

```
src/main/java/com/claudecode/
├── agent/Agent.java         # Agent 核心，ReAct 循环
├── cli/Main.java            # 命令行入口
├── config/EnvConfig.java    # 环境配置加载
├── llm/
│   ├── DeepSeekClient.java  # DeepSeek API 客户端（含流式）
│   ├── LLMClient.java       # LLM 抽象基类
│   ├── LLMModels.java       # 消息/工具/响应模型
│   └── StreamCallback.java  # 流式回调接口
└── tool/
    ├── Tool.java            # 工具接口
    ├── ToolRegistry.java    # 工具注册中心
    ├── ToolExecutor.java    # 工具执行器
    └── ...
```

## 技术栈

- **Java 25** — 核心语言
- **OkHttp 4.12** — HTTP 客户端 + SSE 流式读取
- **Jackson 2.16** — JSON 序列化
- **SLF4J** — 日志门面
- **DeepSeek API** — 大语言模型服务

## 开发者

**希冀不是洗衣机** — [@stephenyoga](https://github.com/stephenyoga)
