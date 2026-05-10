<div align="center">

# claude-code-java

**可嵌入任何 Java 应用的 AI Agent 引擎 — CLI · REST API · Web Playground**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
[![Stars](https://img.shields.io/github/stars/fluentlc/claude-code-java?style=social)](https://github.com/fluentlc/claude-code-java/stargazers)

[中文](#中文) | [English](#english)

</div>

---

## 中文

### 这是什么？

**claude-code-java** 是一个可嵌入任何 Java 应用的 AI Agent 引擎。它兼容 **OpenAI Chat Completions 协议**，可对接 OpenAI、Azure OpenAI、Ollama、DashScope 或任何兼容端点。

提供三种开箱即用的交互方式：
- **CLI 模式** — 终端 REPL，适合本地开发和调试
- **REST API 模式** — 标准 HTTP 接口（同步 + SSE 流式），适合集成到其他系统
- **Web Playground** — 内置实时流式对话界面，可视化 Agent 工作过程

所有能力都源自同一个核心循环：

```java
// AI Agent 的本质
while ("tool_calls".equals(finishReason)) {
    response = client.chat(messages, tools);
    executeTools(response);    // 执行工具调用
    appendResults(messages);   // 将结果追回对话
}
// 10 项核心能力只是往这个循环里注入新工具和新上下文
```

---

### Web Playground

启动服务后访问 `http://localhost:8080`，无需额外配置即可使用内置的对话界面。

```
┌─────────────────────────────────────────────────────────────────┐
│  claude-code-java                                    [新对话]      │
├──────────────────┬──────────────────────────────────────────────┤
│ 对话历史          │                                              │
│                  │  [Context Compressed]  第 1 次压缩  [查看 →]  │
│ > 帮我审查代码    │                                              │
│   创建 Agent 团队 │  ┌─ 🤖 assistant ──────────────────────┐    │
│                  │  │ 我将为你创建两个专家 Teammate...     │    │
│                  │  └─────────────────────────────────────┘    │
│                  │                                              │
│                  │  [reviewer · 正在执行 read_file ···]         │
│                  │  [tester   · 正在执行 write_file ···]        │
│                  │                                              │
│                  │  ┌ ── ── ── ── ── ── ── ── ── ── ── ── ┐    │
│                  │    ✦ reviewer 完成（12 个工具）[工作区 →]     │
│                  │  └ ── ── ── ── ── ── ── ── ── ── ── ── ┘    │
│                  │                                              │
│                  │  [输入消息...                    发送]        │
└──────────────────┴──────────────────────────────────────────────┘
                                                 ┌──── 工作空间 ────┐
                                                 │ 第1次  第2次      │
                                                 │ [压缩前完整历史]  │
                                                 │ [Teammate 工具流]│
                                                 └──────────────────┘
```

**核心交互特性：**

| 特性 | 说明 |
|------|------|
| **流式渲染** | 思考过程、工具调用、文字回复逐 token 实时呈现 |
| **思考卡片** | 执行中展开、完成自动折叠，显示耗时 |
| **工具卡片** | 展示 Input/Output，执行完自动折叠 |
| **压缩卡片** | 上下文压缩后不中断对话，卡片展示摘要 + 可进入历史抽屉 |
| **Teammate 悬浮条** | Agent Teams 工作时输入框上方实时显示各 Teammate 状态 |
| **工作空间抽屉** | 右侧滑入面板，按 Tab 区分各次压缩历史和各 Teammate 工作细节 |
| **压缩链导航** | 多次压缩形成链式历史，可逐层回溯查看每次压缩前的完整对话 |
| **会话侧边栏** | 历史会话持久化，点击即恢复完整对话流（含所有卡片） |
| **Markdown 渲染** | 所有 AI 回复、摘要均支持完整 Markdown 格式 |

---

### 架构

```
claude-code-java （父 pom）
├── claude-code-java-service  —— 纯 Java 17 库（无框架依赖）
│   ├── core/        OpenAiClient · BaseTools · SecurityUtils
│   │                ShellUtils · ToolHandler
│   ├── capability/  TodoManager · ContextCompactor · BackgroundRunner
│   │                TaskStore · WorktreeManager · SkillLoader
│   │                MessageBus · TeammateRunner · SessionStore
│   │                TeamProtocol · TaskPoller
│   ├── tool/        8 个 ToolProvider
│   └── agent/       AgentLoop · TeammateLoop · AgentAssembler · SlashRouter
│
└── claude-code-java-start    —— Spring Boot 3.2 应用层
    ├── Application.java     统一入口
    ├── cli/CliRunner        @Profile("cli") REPL
    ├── web/controller/      ChatController（REST + SSE 流式端点）
    ├── web/service/         StreamService（AgentEventListener → SSE 事件）
    │                        ChatService（同步对话）
    ├── config/AgentBeans   Spring @Bean 配置
    └── resources/static/   index.html（Web Playground 单页应用）
```

---

### 10 项核心能力

| 能力 | 说明 |
|------|------|
| **TodoManager** | Agent 自我跟踪任务，每 3 轮未完成自动触发提醒 |
| **SkillLoader** | 从 `./skills/` 目录按需注入技能提示词，不污染主上下文 |
| **ContextCompactor** | 三层压缩管道（微压缩 → 自动压缩 40 条消息 → 手动 `/compact`），压缩历史持久化为 `.transcripts/` 文件 |
| **TaskStore** | JSON 文件持久化任务状态，含依赖图，重启后自动恢复 |
| **BackgroundRunner** | 线程池异步执行，fire-and-forget，完成后通知注入主循环 |
| **MessageBus** | JSONL 格式收件箱/发件箱，支持多 Agent 间消息传递 |
| **TeammateRunner** | Agent Teams 核心 — 动态 spawn Teammate，每个 Teammate 在独立线程中运行完整 LLM 循环，通过 MessageBus 通信、TaskStore 自主认领任务；会话实时持久化（每次工具调用后），Web Playground 可实时查看 |
| **SessionStore** | REST 会话持久化（`.sessions/{id}.json`），进程重启后自动恢复；Teammate 子会话独立存储（`{leadId}-tm-{name}.json`） |
| **TeamProtocol** | request_id 关联的关闭/审批协议，规范 Agent 间交互 |
| **WorktreeManager** | Git Worktree 目录级隔离，每个任务独立目录 + 独立分支 |

---

### 快速开始

**第一步：克隆并配置**

```bash
git clone https://github.com/fluentlc/claude-code-java.git
cd claude-code-java
```

启动后访问 Playground，点击顶部「设置」菜单配置模型：

| 配置项 | 说明 |
|--------|------|
| API Key | 你的 OpenAI 协议 API 密钥 |
| Base URL | 服务端地址，如 `https://api.openai.com` |
| Model ID | 模型 ID，如 `gpt-4o` |

配置保存在服务端 `.models/models.json`，重启后自动恢复。

**第二步：编译**

```bash
mvn compile
```

**第三步：选择启动方式**

```bash
# Web Playground + REST API 模式（端口 8080，推荐）
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application"
# 然后访问 http://localhost:8080

# CLI 交互模式（REPL）
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli
```

---

### REST API

#### 同步接口

```bash
# 发送消息（首次对话，省略 sessionId）
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "帮我创建一个 Hello.java"}'

# 继续对话（携带上一次返回的 sessionId）
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "550e8400-...", "message": "再添加一个 main 方法"}'

# 清除会话历史
curl -X DELETE http://localhost:8080/api/sessions/550e8400-...
```

#### SSE 流式接口

```bash
# 流式对话（Server-Sent Events）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message": "用 Python 写一个快排"}'
```

**SSE 事件协议：**

| 事件 | payload | 说明 |
|------|---------|------|
| `session_id` | `{"sessionId":"..."}` | 会话 ID（首条事件） |
| `thinking_start` | — | 思考开始 |
| `thinking_text` | `{"text":"..."}` | 思考内容 token |
| `thinking_end` | `{"ms":1234}` | 思考结束（含耗时） |
| `text_delta` | `{"text":"..."}` | 文字回复 token |
| `tool_start` | `{"toolName":"bash","toolCallId":"...","input":"..."}` | 工具开始执行 |
| `tool_end` | `{"toolCallId":"...","output":"..."}` | 工具执行完成 |
| `tool_error` | `{"toolCallId":"...","error":"..."}` | 工具执行出错 |
| `compact_done` | `{"summary":"...","transcriptFile":"..."}` | 上下文压缩完成 |
| `team_tool_start` | `{"agentId":"...","toolName":"..."}` | Teammate 工具开始 |
| `team_tool_end` | `{"agentId":"...","toolName":"..."}` | Teammate 工具完成 |
| `team_text` | `{"agentId":"...","text":"..."}` | Teammate 文字输出 |
| `team_done` | `{"agentId":"..."}` | Teammate 完成 |
| `done` | — | 本轮对话结束 |
| `error` | `{"message":"..."}` | 错误 |

#### 会话与历史接口

```bash
# 获取所有会话列表（仅 lead 会话，按更新时间排序）
GET /api/sessions

# 获取会话消息历史
GET /api/sessions/{sessionId}/messages

# 获取会话关联的 Teammate 列表
GET /api/sessions/{sessionId}/teammates

# 获取压缩前历史记录文件（transcript chain 导航）
GET /api/transcripts/{filename}

# 健康检查
GET /actuator/health
```

---

### Slash 命令（CLI 模式）

| 命令 | 说明 |
|------|------|
| `/help` | 显示所有可用命令 |
| `/tasks` | 列出当前任务状态 |
| `/skill [name]` | 加载指定技能 |
| `/compact` | 手动触发上下文压缩 |
| `/team` | 查看团队消息总线状态 |
| `/worktree [name]` | 管理 Git Worktree |

---

### 扩展：添加自定义工具

扩展只需两步：

```java
// 第一步：实现 ToolProvider 接口
public class MyTool implements ToolProvider {
    @Override
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> m = new LinkedHashMap<>();
        m.put("my_tool", input -> doSomething(input.get("arg").getAsString()));
        return m;
    }

    @Override
    public List<JsonObject> definitions() {
        return List.of(ToolUtils.toolDef("my_tool", "Does something useful.",
            ToolUtils.schema("arg", "string", "true")));
    }
}

// 第二步：在 AgentAssembler.buildProviders() 中注册
list.add(new MyTool());
```

---

### Harness 实践

本项目完整展示了以下 AI Agent harness 设计模式，可直接用于生产系统参考：

#### AgentLoop 模式
核心 while 循环是所有 Agent 框架的本质。`stop_reason == "tool_calls"` 继续，`"end_turn"` 退出。工具注册、上下文管理、多轮状态维护均封装在 `AgentLoop` 中，可被任何 Java 应用嵌入。

#### ToolProvider 接口
工具以插件式 `ToolProvider` 接口注册，每个工具类同时提供：
- `handlers()` — 工具名到处理器函数的映射（执行逻辑）
- `definitions()` — 供 LLM 识别的 JSON Schema 描述

新增工具只需两行代码，不修改任何框架代码。

#### AgentEventListener 事件总线
`AgentLoop` 在每个关键节点发出事件（thinking start/end、tool start/end、compact done、team events）。通过 `AgentEventListener` 接口解耦，同一个 Agent 引擎可以同时服务：
- CLI 模式（打印到 stdout）
- SSE 流式模式（推送给浏览器）
- 测试模式（收集断言数据）

#### TeammateRunner 多 Agent 编排
Teammate 在独立线程中运行完整 LLM 循环，通过 `MessageBus` 通信、`TaskStore` 自主认领任务、`sessionStore` 实时持久化。Lead 通过 `AgentEventListener` 接收 Teammate 事件，无需轮询。

#### ContextCompactor 三层压缩
- **Layer 1 Micro Compact**：每轮自动压缩旧 `tool_result`，保持最近 3 轮工具结果原文
- **Layer 2 Auto Compact**：消息数超过阈值时 LLM 生成摘要，历史存档到 `.transcripts/`
- **Layer 3 Manual Compact**：用户随时可手动触发 `/compact`

每次压缩通过 `_transcript_file` 字段形成链式追溯，Web Playground 支持逐层回看。

---

### AI Coding 实践

本项目本身是 **AI 辅助开发**的实际产物 — 所有迭代均通过与 Claude Code 的对话完成，0 行手写代码：

- **完整 AI 开发流程**：从系统架构设计、Java 后端实现、Spring Boot 配置，到 Web 前端的全部代码，均在对话中生成
- **迭代驱动**：每个版本（v0.1 → v0.13）对应一轮对话，见 [docs/CHANGELOG.md](docs/CHANGELOG.md)
- **实时调试**：通过 Web Playground 观察 Agent 自身的思考和工具调用过程，发现并修复 bug
- **自举验证**：用 claude-code-java 引擎驱动 Claude Code Agent，生成并维护这个 claude-code-java 项目本身

这是一个「用 AI 工具构建 AI 工具」的完整循环，每条 CHANGELOG 条目都是 AI 辅助开发的真实记录。

---

### 安全模型

- **`SecurityUtils.isDangerous()`** — 危险命令黑名单（`rm -rf /`、`sudo`、`shutdown` 等）
- **`BaseTools.safePath()`** — 路径穿越保护，所有文件操作严格限制在工作目录内
- **`ShellUtils`** — 可配置执行超时 + 输出截断，防止失控命令阻塞主循环
- **Transcript API** — basename 校验防路径穿越，只读取 `.transcripts/` 目录内文件

---

### 参与贡献

欢迎任何形式的贡献：

- **发现 Bug** — [提交 Issue](https://github.com/fluentlc/claude-code-java/issues)
- **有新想法** — [发起讨论](https://github.com/fluentlc/claude-code-java/issues)
- **想改代码** — Fork → 新建分支 → 提 PR

贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

### 开源协议

本项目采用 [MIT License](LICENSE) 开源。

© 2026 fluentlc

---

<div align="center">

如果这个项目对你有帮助，欢迎点一个 Star ⭐

</div>

---

## English

### What is this?

**claude-code-java** is an embeddable AI Agent engine for Java applications. It speaks the **OpenAI Chat Completions protocol** and works with OpenAI, Azure OpenAI, Ollama, DashScope, or any compatible endpoint.

Three interaction modes out of the box:
- **CLI mode** — Terminal REPL for local development
- **REST API mode** — Standard HTTP interface (synchronous + SSE streaming) for system integration
- **Web Playground** — Built-in real-time streaming UI to visualize agent execution

---

### Web Playground

Start the server and visit `http://localhost:8080` — no extra configuration needed.

Key features:
- **Real-time SSE streaming** — thinking blocks, tool calls, and text replies rendered token-by-token
- **Agent Teams visualization** — floating teammate status bar + workspace drawer with per-agent tabs
- **Context compression history** — multi-layer transcript chain, navigate between compression snapshots
- **Session sidebar** — persistent session history, click to restore full conversation with all cards
- **Workspace drawer** — right-side sliding panel for compression history and teammate work details

---

### Architecture

```
claude-code-java (parent pom)
├── claude-code-java-service  — Pure Java 17 library (no framework dependencies)
│   ├── core/        OpenAiClient · BaseTools · SecurityUtils
│   │                ShellUtils · ToolHandler
│   ├── capability/  TodoManager · ContextCompactor · BackgroundRunner
│   │                TaskStore · WorktreeManager · SkillLoader
│   │                MessageBus · TeammateRunner · SessionStore
│   │                TeamProtocol · TaskPoller
│   ├── tool/        8 ToolProviders
│   └── agent/       AgentLoop · TeammateLoop · AgentAssembler · SlashRouter
│
└── claude-code-java-start    — Spring Boot 3.2 application layer
    ├── Application.java     unified entry point
    ├── cli/CliRunner        @Profile("cli") REPL
    ├── web/controller/      ChatController (REST + SSE streaming)
    ├── web/service/         StreamService (AgentEventListener → SSE events)
    │                        ChatService (synchronous chat)
    ├── config/AgentBeans   Spring @Bean configuration
    └── resources/static/   index.html (Web Playground SPA)
```

---

### Quick Start

**Step 1: Clone and configure**

```bash
git clone https://github.com/fluentlc/claude-code-java.git
cd claude-code-java
```

After starting the server, open the Playground and configure your model via the **Settings** menu in the top nav:

| Setting | Description |
|---------|-------------|
| API Key | Your OpenAI-compatible API key |
| Base URL | Endpoint, e.g. `https://api.openai.com` |
| Model ID | Model identifier, e.g. `gpt-4o` |

Configuration is persisted server-side in `.models/models.json` and survives restarts.

**Step 2: Build**

```bash
mvn compile
```

**Step 3: Start**

```bash
# Web Playground + REST API mode (port 8080, recommended)
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application"
# Visit http://localhost:8080

# CLI mode (REPL)
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli
```

---

### Harness Patterns

This project demonstrates production-grade AI Agent harness patterns:

**AgentLoop** — The fundamental while-loop of every Agent framework. `stop_reason == "tool_calls"` continues execution; `"end_turn"` exits. Embeddable in any Java application.

**ToolProvider** — Plugin interface for tools. Each class provides `handlers()` (execution logic) and `definitions()` (JSON Schema for LLM). Register in one line.

**AgentEventListener** — Event bus for the agent lifecycle. Same engine serves CLI, SSE streaming, and test mode by swapping the listener implementation.

**TeammateRunner** — Multi-agent orchestration with independent LLM loops per teammate, shared MessageBus, TaskStore for task claiming, and real-time session persistence.

**ContextCompactor** — Three-layer compression pipeline with transcript chain linking for full history navigation.

---

### Extension Guide

```java
// Step 1: implement ToolProvider
public class MyTool implements ToolProvider {
    @Override
    public Map<String, ToolHandler> handlers() {
        var m = new LinkedHashMap<String, ToolHandler>();
        m.put("my_tool", input -> doSomething(input.get("arg").getAsString()));
        return m;
    }
    @Override
    public List<JsonObject> definitions() {
        return List.of(ToolUtils.toolDef("my_tool", "Does something useful.",
            ToolUtils.schema("arg", "string", "true")));
    }
}

// Step 2: register in AgentAssembler.buildProviders()
list.add(new MyTool());
```

---

### License

[MIT License](LICENSE) — © 2026 fluentlc

---

<div align="center">

If this project helps you, a Star goes a long way ⭐

</div>
