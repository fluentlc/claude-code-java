# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

**claude-code-java** 是一个可嵌入任何 Java 应用的 AI Agent 引擎，支持 CLI、REST API、Web Playground 三种交互方式。

**模块结构**：
- `claude-code-java-service` — 纯 Java 17 库，包含所有 Agent 能力，无框架依赖
- `claude-code-java-start` — Spring Boot 3.2 应用层，提供 CLI REPL、REST API 和 Web Playground

## 构建与运行

**配置**（首次运行前必须完成）：
```bash
# 编辑 claude-code-java-start/src/main/resources/claude.properties
# 将 OPENAI_API_KEY=your_api_key_here 替换为真实的 API Key
# 或设置环境变量：export OPENAI_API_KEY=<your-key>
```

**编译**：
```bash
mvn compile
```

**Web Playground + REST API 模式**（推荐，端口 8080）：
```bash
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application"
# 访问 http://localhost:8080
```

**CLI 交互模式**（REPL）：
```bash
mvn exec:java -pl claude-code-java-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli
```

**调试**：在 `claude.properties` 中设置 `DEBUG_PRINT_PAYLOAD=true` 可打印完整的 API 请求/响应报文。

## 架构

### 模块职责

| 模块 | 定位 | 特征 |
|------|------|------|
| `claude-code-java-service` | 纯 Java 17 库 | 无框架依赖，可被任何 Java 项目引用 |
| `claude-code-java-start` | Spring Boot 3.2 应用 | CLI + REST API + Web Playground（单页应用） |

### service 模块包结构

```
src/main/java/ai/claude/code/
├── core/        — OpenAiClient, AnthropicClient, ClientFactory, BaseTools,
│                  SecurityUtils, ShellUtils, ToolHandler, AgentEventListener
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
└── claude.properties        — API Key、模型、调试开关
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

**配置优先级**：`System.getenv() > claude.properties > 默认值`

### 配置文件

`claude-code-java-start/src/main/resources/claude.properties`（已纳入版本控制，API Key 为占位符）：
- `OPENAI_API_KEY` — OpenAI 协议 API 密钥（必填，或通过环境变量设置）
- `OPENAI_BASE_URL` — 服务地址，默认 `https://api.openai.com`
- `OPENAI_MODEL_ID` — 模型 ID，如 `gpt-4o`
- `CLIENT_TYPE` — `openai`（默认）或 `anthropic`
- `DEBUG_PRINT_PAYLOAD` — 是否打印完整 API 报文

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

**关键 JS 函数**：

| 函数 | 职责 |
|------|------|
| `handleSseEvent(type, payload)` | SSE 事件分发总入口 |
| `appendAiRow()` | 创建 `.msg-ai` 行，返回 `.ai-body` |
| `getOrCreateTextEl()` | 创建/复用文字块（内含头像，仅文字回复时调用） |
| `recordCompact(summary, file)` | 记录压缩，卡片插入对话流顶部（多次压缩更新同一张卡） |
| `openDrawer(type, data)` | 打开右侧工作空间抽屉（`'compact'` / `'teammate'`） |
| `renderMiniMessages(container, msgs, onCompactClick)` | 渲染 mini 消息列表（复用主对话流 CSS 类），第三参数为压缩分隔符点击回调 |
| `buildTranscriptChain(latestFile)` | 沿 `_transcript_file` 链递归加载，返回 oldest→newest 有序数组 |
| `showChainTab(chain, idx)` | 渲染指定层级的 Tab 内容，构造层级间跳转回调 |
| `updateTmFloat()` | 根据 `tmFloatAgents` 更新/隐藏输入框上方悬浮状态条 |
| `switchSession(id)` | 恢复历史会话（压缩卡、Teammate 卡、工具卡正确还原） |
| `loadSessionList()` | 加载侧边栏会话列表，含 teammate 数量徽标 |

**关键全局状态**：

```javascript
let sessionId          // 当前会话 ID（SSE session_id 事件赋值）
let compactSessions    // [{summary, transcriptFile}]，多次压缩追加
let compactCard        // 压缩卡 DOM 元素（始终唯一，置于顶部）
let tmFloatAgents      // { agentId → {toolCount, lastStatus} }，悬浮条数据源
let tmCompletedAgents  // { agentId → {toolCount} }，done 后移入此 map
let currentTeammateSessions  // { agentId → tmSessionId }，抽屉加载用
let hasAutoOpenedDrawer      // 首次 team_tool_start 自动开抽屉后置 true
```

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

## 开发规范

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
