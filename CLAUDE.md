# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

**claude-code-java** 是一个可嵌入任何 Java 应用的 AI Agent 引擎，支持 CLI、REST API、Web Playground 三种交互方式。

**模块结构**：
- `claude-code-java-service` — 纯 Java 17 库，包含所有 Agent 能力，无框架依赖
- `claude-code-java-start` — Spring Boot 3.2 应用层，提供 CLI REPL、REST API 和 Web Playground

## 构建、测试与运行

**配置**（首次运行前必须完成）：
启动服务后访问 Playground，点击顶部「设置」菜单配置模型（API Key、Base URL、Model ID）。配置保存在服务端 `.models/models.json`，重启后自动恢复。

**常用命令**：
```bash
# 编译全部模块
mvn compile

# 编译指定模块
mvn compile -pl claude-code-java-service

# 运行测试（JUnit 5，maven-surefire-plugin）
mvn test
mvn test -Dtest=ClassName          # 运行单个测试类
mvn test -pl claude-code-java-service

# 打包可执行 fat JAR（输出在 claude-code-java-start/target/）
mvn package

# Web Playground + REST API 模式（推荐，端口 8080）
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application"
# 访问 http://localhost:8080

# CLI 交互模式（REPL）
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli
```

**调试**：设置环境变量 `DEBUG_PRINT_PAYLOAD=true` 可打印完整的 API 请求/响应报文。

## 架构

### 模块职责

| 模块 | 定位 | 特征 |
|------|------|------|
| `claude-code-java-service` | 纯 Java 17 库 | 无框架依赖，可被任何 Java 项目引用 |
| `claude-code-java-start` | Spring Boot 3.2 应用 | CLI + REST API + Web Playground（单页应用） |

### service 模块包结构

```
src/main/java/ai/claude/code/
├── core/        — OpenAiClient, BaseTools, SecurityUtils, ShellUtils,
│                  ToolHandler, AgentEventListener
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
│   │   ├── ChatController.java   — POST /api/chat（同步）
│   │   │                           GET  /api/sessions（列表 + teammate 数量）
│   │   │                           GET  /api/sessions/{id}/messages
│   │   │                           GET  /api/sessions/{id}/teammates
│   │   │                           GET  /api/transcripts/{filename}
│   │   │                           DELETE /api/sessions/{id}
│   │   └── StreamController.java — POST /api/chat/stream（SSE 流式）
│   ├── dto/
│   │   ├── ChatRequest.java      — record { sessionId, message }
│   │   ├── ChatResponse.java     — record { sessionId, reply }
│   │   └── SessionMeta.java      — record { sessionId, preview, updatedAt, teammateCount }
│   └── service/
│       ├── ChatService.java      — 同步会话管理 + AgentLoop 调用
│       └── StreamService.java    — SSE 流式：注册 AgentEventListener → SseEmitter 推送
└── config/
    └── AgentBeans.java      — Spring @Bean 配置，调用 AgentAssembler.build()
src/main/resources/
├── static/index.html        — Web Playground 单页应用（无构建步骤，直接修改生效）
└── application.properties   — Spring Boot 配置（端口、调试开关等）
```

### 核心设计模式

**AgentLoop**：核心 while 循环。`stop_reason == "tool_calls"` 时继续，`"end_turn"` 时退出。`messages` 数组跨轮次共享，实现多轮对话。

**AgentEventListener**：事件监听接口（`core/` 包），定义 Agent 执行周期内所有关键节点的回调：
- `onThinkingStart/End`、`onTextDelta`（流式文字）
- `onToolStart/End/Error`（工具执行）
- `onTeamToolStart/End/Text/Done`（Teammate 工具事件转发）
- `onCompactDone(summary, transcriptFile)`（压缩完成，含 transcript 文件名）

`StreamService.SseAgentEventListener` 实现此接口，将每个事件序列化为 SSE 帧推送给浏览器。CLI 模式通过另一个实现打印到 stdout。同一个 AgentLoop 通过切换 listener 实现不同输出模式。

**AgentAssembler**：工厂方法，初始化所有 capability，组装 ToolProvider，返回配置好的 AgentLoop。提供两个 `build()` 重载：一个自动创建所有依赖，另一个接受外部预创建的 capability（供 Spring 注入）。

**ToolProvider 模式**：每个工具类实现 `ToolProvider` 接口：
- `handlers()` — 工具名 → 处理器函数的 Map
- `definitions()` — LLM 可调用的 JSON schema 列表

新增工具：新建实现 `ToolProvider` 的类，在 `AgentAssembler.buildProviders()` 中加一行。

**TeammateRunner + TeammateLoop**：Agent Teams 核心。`TeammateRunner` 管理 Teammate 线程生命周期（spawn/list/shutdownAll），同时持有 `mainListener` 引用（通过 `setMainListener()` 注入），将 Teammate 工具事件转发给 Lead 的 SSE 流。`TeammateLoop` 是每个 Teammate 独立的 LLM 对话循环（实现 `Runnable`），拥有 8 个工具（bash/read/write/edit/msg_send/msg_read/idle/claim_task）、空闲轮询（每 5s 检查收件箱和 TaskStore，最多 60s）、身份再注入（消息数 < 6 时自动补充角色信息）、**每次工具调用完成后立即调用 `saveSession()` 持久化**（保证 Web Playground 实时可读）。

**SessionStore**：REST 会话持久化（`.sessions/{sessionId}.json`）。命名约定：
- Lead 会话：`{sessionId}.json`
- Teammate 子会话：`{leadSessionId}-tm-{name}.json`
- `listAll()` 过滤 `-tm-` 文件，侧边栏只展示 Lead 会话
- `listTeammates(leadSessionId)` 返回该 Lead 的所有 Teammate 信息

**ContextCompactor**：三层压缩管道：
1. **Micro Compact**（每轮自动）：将 `MICRO_COMPACT_KEEP_COUNT=3` 轮以前的 `tool_result` 替换为简短摘要
2. **Auto Compact**（`messages.size() >= 40`）：调用 LLM 生成对话摘要，压缩前历史存档为 `.transcripts/{timestamp}.json`，compacted user 消息携带 `_transcript_file` 字段（私有字段，API 调用前剥离）
3. **Manual Compact**（用户执行 `/compact`）：随时可触发

多次压缩通过 `_transcript_file` 形成链式追溯，Web Playground 支持逐层加载查看。

**Spring Bean 一致性**：`AgentBeans.java` 将所有 capability 创建为 Spring Bean，然后传入 `AgentAssembler.build()`，确保 CliRunner 和 AgentLoop 共享同一实例。`TeammateRunner` 也作为 Spring Bean 注入，保证线程池单例。

### 配置文件

`claude-code-java-start/src/main/resources/application.properties`：
- `server.port` — 服务端口，默认 `8080`
- `DEBUG_PRINT_PAYLOAD` — 是否打印完整 API 报文（仅支持环境变量设置）

## REST API

### 同步接口

```
POST /api/chat
Body:     { "sessionId": "(可选)", "message": "用户输入" }
Response: { "sessionId": "UUID", "reply": "助手回复" }

DELETE /api/sessions/{sessionId}
Response: 204 No Content
```

### SSE 流式接口

```
POST /api/chat/stream
Body: { "sessionId": "(可选)", "message": "用户输入" }
Response: text/event-stream

SSE 事件类型：
  session_id       { sessionId }
  thinking_start   {}
  thinking_text    { text }
  thinking_end     { ms }
  text_delta       { text }
  tool_start       { toolName, toolCallId, input }
  tool_end         { toolCallId, output }
  tool_error       { toolCallId, error }
  compact_done     { summary, transcriptFile }
  team_tool_start  { agentId, toolName }
  team_tool_end    { agentId, toolName }
  team_text        { agentId, text }
  team_done        { agentId }
  done             {}
  error            { message }
```

### 会话与历史接口

```
GET /api/sessions
Response: [{ sessionId, preview, updatedAt, teammateCount }, ...]（仅 Lead 会话，按更新时间倒序）

GET /api/sessions/{sessionId}/messages
Response: { sessionId, messages: [...] }

GET /api/sessions/{sessionId}/teammates
Response: [{ name, sessionId }, ...]

GET /api/transcripts/{filename}
Response: [...messages]（压缩前历史；basename 校验防路径穿越）

GET /actuator/health
Response: { "status": "UP" }
```

## Web Playground

单页应用，入口：`claude-code-java-start/src/main/resources/static/index.html`

**无构建步骤**：修改 HTML/CSS/JS 后刷新浏览器即可，Spring Boot 静态资源服务直接生效。

前端 SSE 事件总入口为 `handleSseEvent(type, payload)`，负责将流式事件渲染为对话流中的思考卡片、工具卡片、文字气泡、压缩卡片和 Teammate 状态条。具体 DOM 操作函数和全局状态变量参见源码注释。

## 约束

- Java 17+（records, var, text blocks 均可使用）
- service 模块唯一外部依赖：Gson 2.10.1
- service 模块使用原生 `HttpURLConnection`，不引入任何 AI SDK
- start 模块使用 Spring Boot 3.2.5
- Web Playground 为纯原生 HTML/CSS/JS，无 npm/构建工具；引入 `marked@9`（CDN）用于 Markdown 渲染
- `.sessions/`、`.transcripts/`、`.tasks/`、`.worktrees/` 为运行时目录，不纳入代码修改范围

## 文档

- `docs/study/index.html` — 项目主页（架构图、能力说明、快速上手）
- `docs/study/api.html` — REST API 参考文档
- `docs/TESTING.md` — 核心场景测试报告（10 个场景，含 Web Playground 全流程）
- `docs/CHANGELOG.md` — 每次迭代的变更记录
- `DESIGN.md` — Linear 设计系统参考（Web Playground UI 风格规范）
- `CONTRIBUTING.md` — 扩展指南（新增工具、SSE 事件、Teammate 工具、Web Playground）

## 开发规范

### Wiki 沉淀（必须执行）

**凡是涉及 Plan（规划）、Brainstorm（头脑风暴）、Spec（需求规格）、Design（设计方案）的内容，必须以 Markdown 文件的形式保存到 `docs/` 目录，作为 Wiki 永久沉淀。**

规则：
- **触发时机**：一旦对话中出现方案设计、需求分析、架构决策、接口设计等内容，立即整理输出为文档
- **文件命名**：`docs/{type}-{topic}.md`，type = `plan` / `spec` / `design` / `adr`（Architecture Decision Record）
  - 示例：`docs/design-sse-event-pipeline.md`、`docs/spec-agent-team-api.md`、`docs/adr-session-store-naming.md`
- **文件内容要求**：
  - 背景（Context）：为什么要做这个
  - 目标（Goal）：要解决什么问题
  - 方案（Solution）：核心设计或决策
  - 关键约束（Constraints）：已知限制或不可逾越的边界
  - 待定项（Open Questions）：尚未确定的问题（如有）
- **不可跳过**：即使是口头讨论过的设计，也要整理成文字落地
- **与 Changelog 的关系**：文档创建/更新后，在 Changelog 中同步记录文件路径

**`docs/` 目录结构约定**：
```
docs/
├── CHANGELOG.md      — 版本变更记录（必填）
├── TESTING.md        — 测试场景报告
├── design-*.md       — 设计文档
├── spec-*.md         — 需求规格文档
├── plan-*.md         — 规划/迭代计划文档
├── adr-*.md          — 架构决策记录
└── study/            — 教学/展示页面
```

### Changelog 维护（必须执行）

**每次对代码或文档进行任何修改后，必须在 `docs/CHANGELOG.md` 中追加新版本条目**，然后再提交代码。格式：

```markdown
## vX.Y — YYYY-MM-DD

### 需求
> 用户的原始需求描述

### 变更

#### `文件路径`（模块名）
- 具体改动说明
```

**版本号规则**：在当前最新版本基础上 +0.1。  
**条目位置**：文件顶部（最新版本在最前）。  
**不可跳过**：即使是文档改动、样式微调、重命名，也必须记录。

### Git 提交规范

- 提交信息格式：`type: 简短描述`（type = feat / fix / refactor / docs / style）
- **绝对不加** `Co-Authored-By: Claude ...`，只使用 `fluent.lc` 账号
- 提交命令：`git -c user.name="fluent.lc" -c user.email="fluent.lc.1234@gmail.com" commit -m "..."`

### 扩展工具

新增工具只需两步：
1. 在 `claude-code-java-service` 中新建实现 `ToolProvider` 的类
2. 在 `AgentAssembler.buildProviders()` 中加一行注册

### 扩展 SSE 事件

三层管道，每层各加一处：
1. `AgentEventListener`（service）— 新增 `default` 方法
2. `StreamService.SseAgentEventListener`（start）— 实现方法，调用 `send("event_name", payload)`
3. `index.html` `handleSseEvent`（前端）— 新增 `case 'event_name'` 分支
