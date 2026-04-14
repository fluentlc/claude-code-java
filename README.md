<div align="center">

# claude-code-4j

**可嵌入任何 Java 应用的 AI Agent 引擎 — CLI + REST API**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
[![Stars](https://img.shields.io/github/stars/fluentlc/claude-code-4j?style=social)](https://github.com/fluentlc/claude-code-4j/stargazers)

[中文](#中文) | [English](#english)

</div>

---

## 中文

### 这是什么？

**claude-code-4j** 是一个可嵌入任何 Java 应用的 AI Agent 引擎。它兼容 **OpenAI Chat Completions 协议**，可对接 OpenAI、Azure OpenAI、Ollama、DashScope 或任何兼容端点，同时也支持直接对接 Anthropic Messages API。

提供两种开箱即用的交互方式：
- **CLI 模式** — 终端 REPL，适合本地开发和调试
- **REST API 模式** — HTTP 服务器（Spring Boot 3.2），适合集成到其他系统

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

### 架构

```
claude-code-4j （父 pom）
├── claude-code-4j-service  —— 纯 Java 17 库（无框架依赖）
│   ├── core/        OpenAiClient · AnthropicClient · ClientFactory
│   │                BaseTools · SecurityUtils · ShellUtils · ToolHandler
│   ├── capability/  TodoManager · ContextCompactor · BackgroundRunner
│   │                TaskStore · WorktreeManager · SkillLoader
│   │                MessageBus · TeammateRunner · SessionStore
│   │                TeamProtocol · TaskPoller
│   ├── tool/        8 个 ToolProvider
│   └── agent/       AgentLoop · TeammateLoop · AgentAssembler · SlashRouter
│
└── claude-code-4j-start    —— Spring Boot 3.2 应用层
    ├── Application.java     统一入口
    ├── cli/CliRunner        @Profile("cli") REPL
    ├── web/controller       POST /api/chat · DELETE /api/sessions/{id}
    ├── web/service          ChatService（会话持久化 + AgentLoop）
    └── config/AgentBeans   Spring @Bean 配置
```

---

### 10 项核心能力

| 能力 | 说明 |
|------|------|
| **TodoManager** | Agent 自我跟踪任务，每 3 轮未完成自动触发提醒 |
| **SkillLoader** | 从 `./skills/` 目录按需注入技能提示词，不污染主上下文 |
| **ContextCompactor** | 三层压缩管道（微压缩 → 自动压缩 40 条消息 → 手动 `/compact`）|
| **TaskStore** | JSON 文件持久化任务状态，含依赖图，重启后自动恢复 |
| **BackgroundRunner** | 线程池异步执行，fire-and-forget，完成后通知注入主循环 |
| **MessageBus** | JSONL 格式收件箱/发件箱，支持多 Agent 间消息传递 |
| **TeammateRunner** | Agent Teams 核心 — 动态 spawn Teammate，每个 Teammate 在独立线程中运行完整 LLM 循环，通过 MessageBus 通信、TaskStore 自主认领任务 |
| **SessionStore** | REST 会话持久化（`.sessions/{id}.json`），进程重启后自动恢复 |
| **TeamProtocol** | request_id 关联的关闭/审批协议，规范 Agent 间交互 |
| **WorktreeManager** | Git Worktree 目录级隔离，每个任务独立目录 + 独立分支 |

---

### 快速开始

**第一步：克隆并配置**

```bash
git clone https://github.com/fluentlc/claude-code-4j.git
cd claude-code-4j
```

编辑 `claude-code-4j-start/src/main/resources/claude.properties`，填入你的 API Key：

```properties
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxx
OPENAI_BASE_URL=https://api.openai.com
OPENAI_MODEL_ID=gpt-4o
```

也可通过环境变量覆盖（优先级最高）：

```bash
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxx
```

**第二步：编译**

```bash
mvn compile
```

**第三步：选择启动方式**

```bash
# CLI 交互模式（REPL）
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli

# REST API 模式（端口 8080）
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application"
```

---

### REST API

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

# 健康检查
curl http://localhost:8080/actuator/health
```

完整 API 文档：[docs/study/api.html](docs/study/api.html)

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
        return List.of(OpenAiClient.toolDef("my_tool", "Does something useful.",
            OpenAiClient.schema("arg", "string", "true")));
    }
}

// 第二步：在 AgentAssembler.buildProviders() 中注册
list.add(new MyTool());
```

---

### 安全模型

- **`SecurityUtils.isDangerous()`** — 危险命令黑名单（`rm -rf /`、`sudo`、`shutdown` 等）
- **`BaseTools.safePath()`** — 路径穿越保护，所有文件操作严格限制在工作目录内
- **`ShellUtils`** — 可配置执行超时 + 输出截断，防止失控命令阻塞主循环

---

### 参与贡献

欢迎任何形式的贡献：

- **发现 Bug** — [提交 Issue](https://github.com/fluentlc/claude-code-4j/issues)
- **有新想法** — [发起讨论](https://github.com/fluentlc/claude-code-4j/issues)
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

**claude-code-4j** is an embeddable AI Agent engine for Java applications. It speaks the **OpenAI Chat Completions protocol** and works with OpenAI, Azure OpenAI, Ollama, DashScope, or any compatible endpoint. Direct Anthropic Messages API support is also included.

Two interaction modes out of the box:
- **CLI mode** — Terminal REPL for local development
- **REST API mode** — HTTP server (Spring Boot 3.2) for system integration

---

### Architecture

```
claude-code-4j (parent pom)
├── claude-code-4j-service  — Pure Java 17 library (no framework dependencies)
│   ├── core/        OpenAiClient · AnthropicClient · ClientFactory
│   │                BaseTools · SecurityUtils · ShellUtils · ToolHandler
│   ├── capability/  TodoManager · ContextCompactor · BackgroundRunner
│   │                TaskStore · WorktreeManager · SkillLoader
│   │                MessageBus · TeammateRunner · SessionStore
│   │                TeamProtocol · TaskPoller
│   ├── tool/        8 ToolProviders
│   └── agent/       AgentLoop · TeammateLoop · AgentAssembler · SlashRouter
│
└── claude-code-4j-start    — Spring Boot 3.2 application layer
    ├── Application.java     unified entry point
    ├── cli/CliRunner        @Profile("cli") REPL
    ├── web/controller       POST /api/chat · DELETE /api/sessions/{id}
    ├── web/service          ChatService (session persistence + AgentLoop)
    └── config/AgentBeans   Spring @Bean configuration
```

---

### Quick Start

**Step 1: Clone and configure**

```bash
git clone https://github.com/fluentlc/claude-code-4j.git
cd claude-code-4j
```

Edit `claude-code-4j-start/src/main/resources/claude.properties`:

```properties
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxx
OPENAI_BASE_URL=https://api.openai.com
OPENAI_MODEL_ID=gpt-4o
```

**Step 2: Build**

```bash
mvn compile
```

**Step 3: Start**

```bash
# CLI mode (REPL)
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli

# REST API mode (port 8080)
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application"
```

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
        return List.of(OpenAiClient.toolDef("my_tool", "Does something useful.",
            OpenAiClient.schema("arg", "string", "true")));
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
