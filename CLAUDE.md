# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

**claude-code-4j** 是一个可嵌入任何 Java 应用的 AI Agent 引擎，支持 CLI 和 REST API 两种交互方式。

**模块结构**：
- `claude-code-4j-service` — 纯 Java 17 库，包含所有 Agent 能力，无框架依赖
- `claude-code-4j-start` — Spring Boot 3.2 应用层，提供 CLI REPL 和 REST API

## 构建与运行

**配置**（首次运行前必须完成）：
```bash
# 编辑 claude-code-4j-start/src/main/resources/claude.properties
# 将 OPENAI_API_KEY=your_api_key_here 替换为真实的 API Key
# 或设置环境变量：export OPENAI_API_KEY=<your-key>
```

**编译**：
```bash
mvn compile
```

**CLI 交互模式**（REPL）：
```bash
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli
```

**REST API 模式**（端口 8080）：
```bash
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application"

# 调用 API
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "你好"}'
```

**调试**：在 `claude.properties` 中设置 `DEBUG_PRINT_PAYLOAD=true` 可打印完整的 API 请求/响应报文。

## 架构

### 模块职责

| 模块 | 定位 | 特征 |
|------|------|------|
| `claude-code-4j-service` | 纯 Java 17 库 | 无框架依赖，可被任何 Java 项目引用 |
| `claude-code-4j-start` | Spring Boot 3.2 应用 | 提供 CLI + REST API 两种交互方式 |

### service 模块包结构

```
src/main/java/ai/claude/code/
├── core/        — OpenAiClient, AnthropicClient, ClientFactory, BaseTools,
│                  SecurityUtils, ShellUtils, ToolHandler
├── capability/  — TodoManager, ContextCompactor, BackgroundRunner,
│                  TaskStore, WorktreeManager, SkillLoader, MessageBus,
│                  TeammateRunner, SessionStore, TeamProtocol, TaskPoller
├── tool/        — ToolProvider, ToolUtils,
│                  FileTools, TodoTool, SkillTool, CompactTool,
│                  TaskTools, BackgroundTools, TeamTools, WorktreeTools
└── agent/
    ├── AgentLoop.java       — 核心 while 循环（构造器注入）
    ├── TeammateLoop.java    — Teammate 独立 LLM 循环（Runnable，含空闲轮询）
    ├── AgentAssembler.java  — 组装工厂（初始化 capability，返回 AgentLoop）
    └── SlashRouter.java     — /help /tasks /compact /team /worktree 路由
```

### start 模块包结构

```
src/main/java/ai/claude/code/
├── Application.java         — @SpringBootApplication 统一入口
├── cli/
│   └── CliRunner.java       — @Profile("cli") CLI REPL
├── web/
│   ├── controller/
│   │   └── ChatController.java — POST /api/chat, DELETE /api/sessions/{id}
│   ├── dto/
│   │   ├── ChatRequest.java    — record { sessionId, message }
│   │   └── ChatResponse.java   — record { sessionId, reply }
│   └── service/
│       └── ChatService.java    — 会话管理 + 调用 AgentLoop
└── config/
    └── AgentBeans.java      — Spring @Bean 配置，调用 AgentAssembler.build()
```

### 核心设计模式

**AgentLoop**：核心 while 循环（从原 `Assistant.agentLoop()` 提取）。`stop_reason == "tool_calls"` 时继续，`"end_turn"` 时退出。`messages` 数组在 REPL 外层跨轮次共享，实现多轮对话。

**AgentAssembler**：工厂方法，初始化所有 capability，组装 ToolProvider，返回配置好的 AgentLoop。提供两个 `build()` 重载：一个自动创建所有依赖，另一个接受外部预创建的 capability（供 Spring 注入）。

**ToolProvider 模式**：每个工具类实现 `ToolProvider` 接口：
- `handlers()` — 工具名 → 处理器函数的 Map
- `definitions()` — LLM 可调用的 JSON schema 列表

新增工具：新建实现 `ToolProvider` 的类，在 `AgentAssembler.buildProviders()` 中加一行。

**TeammateRunner + TeammateLoop**：Agent Teams 核心。`TeammateRunner` 管理 Teammate 线程生命周期（spawn/list/shutdownAll）；`TeammateLoop` 是每个 Teammate 独立的 LLM 对话循环（实现 `Runnable`），拥有 8 个工具（bash/read/write/edit/msg_send/msg_read/idle/claim_task）、空闲轮询（每 5s 检查收件箱和 TaskStore，最多 60s）、身份再注入（消息数 < 6 时自动补充角色信息）。Lead 通过 `spawn_teammate` 工具触发，`TeammateRunner` 在线程池中启动。

**SessionStore**：REST 会话持久化（`.sessions/{sessionId}.json`），参考 TaskStore 的文件持久化模式。

**Spring Bean 一致性**：`AgentBeans.java` 将所有 capability 创建为 Spring Bean，然后传入 `AgentAssembler.build()`，确保 CliRunner（slash 命令）和 AgentLoop（工具执行）共享同一实例。`TeammateRunner` 也作为 Spring Bean 注入，保证线程池单例。

**配置优先级**：`System.getenv() > claude.properties > 默认值`

### 配置文件

`claude-code-4j-start/src/main/resources/claude.properties`（已纳入版本控制，API Key 为占位符）：
- `OPENAI_API_KEY` — OpenAI 协议 API 密钥（必填，或通过环境变量设置）
- `OPENAI_BASE_URL` — 服务地址，默认 `https://api.openai.com`
- `OPENAI_MODEL_ID` — 模型 ID，如 `gpt-4o`
- `CLIENT_TYPE` — `openai`（默认）或 `anthropic`
- `DEBUG_PRINT_PAYLOAD` — 是否打印完整 API 报文

## REST API

```
POST /api/chat
Body: { "sessionId": "(可选)", "message": "用户输入" }
Response: { "sessionId": "UUID", "reply": "助手回复" }

DELETE /api/sessions/{sessionId}
Response: 204 No Content

GET /actuator/health
Response: { "status": "UP" }
```

## 约束

- Java 17+（records, var, text blocks 均可使用）
- service 模块唯一外部依赖：Gson 2.10.1
- service 模块使用原生 `HttpURLConnection`，不引入任何 AI SDK
- start 模块使用 Spring Boot 3.2.5

## 文档

- `docs/study/index.html` — 项目主页（架构图、能力说明、快速上手）
- `docs/study/api.html` — REST API 参考文档
- `docs/TESTING.md` — 核心场景测试报告（10 个场景，含 Agent Teams 全流程）
