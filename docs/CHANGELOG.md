# Changelog — claude-code-4j

每个版本对应一次对话迭代，记录用户需求和实际改动。
Each version corresponds to one conversation iteration.

---

## v0.5 — 2026-04-15

### 需求
> 工具、思考两类卡片执行过程中可以展开，执行完之后自动折叠，否则太长了
>
> 工具卡片会展示到模型答复结果的后面（原因：启动了 teammate 模式，子 agent 产生的工具卡片调用展示到了后面）。
> 需要在 playground 中：
> 1. 新建 **Workspace Card**：子 agent / team 的工作空间，内容与主 agent 对话分开，默认折叠，可展开查看工作过程
> 2. 新建 **Compact Card**：压缩发生时不再让对话消失，而是显示压缩摘要卡片，可展开阅读

### 变更

#### 自动折叠
- `index.html` `finishToolBlock()`：完成后移除 `open` class，工具卡执行中展开、完成自动折叠（与 think block 行为一致）

#### Workspace Card（Teammate 工作空间）
**后端事件管道**
- `AgentEventListener.java`：新增 `onTeamToolStart/End/Text/Done` 和 `onCompactDone` 5 个 default 方法
- `TeammateRunner.java`：新增 `volatile mainListener` + `setMainListener()` + 4 个 `notifyTeam*` 转发方法；`updateStatus("done")` 时自动触发 `notifyTeamDone`
- `TeammateLoop.java`：`runWorkingSession()` 工具执行前后调用 `runner.notifyTeamToolStart/End()`，LLM 文本时调用 `runner.notifyTeamText()`
- `StreamService.java`：`run()` 前注册 `teammateRunner.setMainListener(listener)`，finally 清除；`SseAgentEventListener` 实现新 SSE 事件 `team_tool_start/end/text/done`

**前端**
- 新增 `.workspace-card` CSS（紫色点缀，折叠/展开动画）
- `ensureWorkspaceCard(agentId)` — `team_tool_start` 时 lazy 创建卡片
- `finishWorkspaceCard(agentId)` — `team_done` 时标记完成并自动折叠
- `addToolBlockToWorkspace / addTextToWorkspace` — 向工作空间注入内容
- `handleSseEvent` 新增 `team_tool_start/end/text/done` 分支

#### Compact Card（压缩摘要卡片）
**后端**
- `AgentLoop.java`：compact 后调用 `extractCompactSummary()` 提取摘要；`markCompactSummaryInMessages()` 将摘要写入 compact tool result 消息的 `_compact_summary` 字段（随 session 持久化）；streaming 版本调用 `listener.onCompactDone(summary)`
- `StreamService.java`：`SseAgentEventListener` 实现 `onCompactDone`，发送 `compact_done` SSE 事件

**前端**
- 新增 `.compact-card` CSS（蓝紫色调，默认展开摘要可折叠）
- `createCompactCard(summary)` — 渲染 Compact Card
- `switchSession()` 恢复时：检测 `role=tool && _compact_summary` → 渲染 Compact Card

---

## v0.4 — 2026-04-15

### 需求
> session 中没有记录思考的内容；历史记录恢复不完整（缺少工具调用、工具结果、思考块）；恢复时只展示"已思考"无最终文本；think block 显示"思考过程"而非"已思考 Xs"；markdown 未渲染；content 内容渲染到所有工具前面

### 变更

#### 空工具 ID 修复
- `OpenAiClient.java`：Qwen 等模型返回 `"id": ""` 时，生成 `tc-{uuid8}-{idx}` 作为 fallback ID（streaming delta 和 finalize 两处均修复）

#### 思考内容持久化
- `OpenAiClient.java`：`doPostStream()` 中累积 `reasoningAccum` 和 `reasoningMs`，流结束时将 `_reasoning` / `_reasoning_ms` 写入 assistant 消息（`_` 前缀字段不发给 API）
- `OpenAiClient.java`：新增 `stripPrivateFields()` 在 `createMessage()` / `createMessageStream()` 中调用，剥离所有 `_` 前缀字段后再发 API

#### 工具结果持久化
- `AgentLoop.java`：tool result 消息创建时追加 `_display_content` 字段，保留完整原始内容（microCompact 会压缩 content，但 `_display_content` 保留）

#### Session 恢复重写
- `index.html` `switchSession()`：完整重写
  - 先构建 `tool_call_id → tool result message` O(1) 查找表
  - 跳过 `<system-reminder>` 注入消息和 `role=tool` 消息
  - 每条 assistant 消息依次渲染：think block → `_content` 插值文本 → tool call blocks（使用 `_display_content`）→ 文本回复（markdown）

#### Interleaved 文本顺序修复
- `OpenAiClient.java`：tool_calls 存在时，若 `textAccum` 非空，将其写入 `_content` 字段（而非丢弃）
- `index.html` `switchSession()`：渲染 tool blocks 前，先检查 `_content` 并渲染（还原直播顺序）

#### Markdown 渲染
- 引入 `marked@9` CDN
- 新增 `renderMarkdown()` helper
- 流式累积 `textEl._rawText`，`done` 事件时统一 `innerHTML = renderMarkdown(rawText)`
- 恢复时文本内容均通过 `renderMarkdown()` 渲染

---

## v0.3 — 2026-04-15

### 需求
> Playground 界面改版，采用 Linear 设计语言

### 变更
- `index.html` 全量重写样式
  - 色彩 token：`#08090a` 页面背景、`#0f1011` 面板、`#191a1b` 表面；品牌色 `#5e6ad2` / `#7170ff`
  - 字体：Inter Variable，`font-feature-settings: "cv01","ss03"`，weight 510
  - 半透明白色边框：`rgba(255,255,255,0.05~0.08)`
  - 新增 topbar / sidebar / message row / tool block / think block / input box 全套组件样式
  - Token 用量进度条、会话列表、空状态页

---

## v0.2 — 2026-04-15

### 需求
> 基础 Playground 前端：支持实时流式对话、工具调用展示、思考过程展示、会话历史

### 变更
- `index.html` 初版实现
  - SSE 流式接收（fetch + ReadableStream 手动解析 `event:/data:` 行）
  - Think block：正在思考 → 已思考（含耗时）
  - Tool block：执行中 → 完成/失败，含 Input/Output 展示
  - 文本流式渲染
  - 会话列表 + 历史恢复
  - 断线重试（指数退避，最多 3 次）

---

## v0.1 — 2026-04-15

### 需求
> 代码初始化：可运行的 Java Agent 引擎，支持 CLI 和 REST API

### 变更
- 项目初始化（见 commit `feat(init)`）
- `claude-code-4j-service`：AgentLoop、AgentAssembler、工具体系（FileTools、TodoTool、CompactTool 等）
- `claude-code-4j-start`：Spring Boot 入口、CLI REPL、REST API (`/api/chat`、`/api/chat/stream`)
- TeammateRunner + TeammateLoop：Agent Teams 支持
- SessionStore：会话持久化（`.sessions/` 目录）
- ContextCompactor：三层压缩（microCompact、auto-compact、model-triggered compact）
