# Changelog — claude-code-4j

每个版本对应一次对话迭代，记录用户需求和实际改动。
Each version corresponds to one conversation iteration.

---

## v0.12 — 2026-04-15

### 需求
> 1. 左侧对话历史展示了 teammate session，应只展示 lead session
> 2. 工作空间中 teammate 对话渲染顺序错误：assistant 消息先于 user 消息显示
> 3. Context Compressed 和工作空间的 summary 没有渲染 markdown 格式

### 根因
1. `SessionStore.listAll()` 未过滤 `-tm-` 文件名，teammate session 混入列表
2. `TeammateLoop.reinjectIdentityIfNeeded()` 写入 user `<identity>` + assistant "I am..." 配对；`renderMiniMessages` 只跳过了 user 那条，assistant ack 成了第一条可见消息
3. `loadCompactTabContent` 用 `textContent` 赋值 summary，未走 markdown 渲染

### 变更

#### `SessionStore.java`（service 模块）
- `listAll()`：文件过滤条件加 `&& !name.contains("-tm-")`，teammate session 不再出现在侧边栏

#### `index.html` JS
- `renderMiniMessages()`：新增 `skipNextAssistantAck` flag；遇到 `<identity>` user 消息时设为 true，下一条 assistant 消息一并跳过，彻底过滤 identity 注入配对
- `renderMiniMessages()`：`<message from="...">` 格式的消息总线注入消息改为渲染成单行斜体提示（`↳ 来自 xxx: ...`），不作普通 user bubble
- `loadCompactTabContent()`：summary 改用 `summaryEl.className = 'ai-text'` + `innerHTML = renderMarkdown(summary)`，支持完整 markdown 样式（标题、列表、代码块等）
- `renderTranscriptDrawer()`：无 transcriptFile 时的 fallback 也改用 `renderMarkdown` + `.ai-text` 类

---

## v0.11 — 2026-04-15

### 需求
> 1. 对话过程中工作空间没有数据（session 已有数据但看不到）；工作空间渲染风格应与对话流一致（卡片、头像、颜色等）
> 2. 对话结束后 teammate 悬浮卡消失了，但摘要卡没有放到对话流末尾；只有点击左侧历史才能看到

### 根因
1. `saveSession()` 仅在每个 LLM 轮结束后调用，第一个工具调用执行期间无 session 数据；`currentTeammateSessions[agId]` 在 `team_tool_start` 时为 null，导致抽屉无法加载
2. `team_done` SSE 事件存在竞态：lead `done` 后 `setMainListener(null)` 执行，TeammateLoop 之后触发的 `updateStatus("done")` 找不到 listener；前端 `done` 处理仅遍历 `tmCompletedAgents`，但 `team_done` 丢失时该 map 为空

### 变更

#### `TeammateLoop.java`（service 模块）
- `runWorkingSession()`：每次工具调用完成后（`messages.add(toolResultMessage)` 后）立即调用 `saveSession(messages)`，实现增量持久化，抽屉可实时加载最新数据

#### `index.html` JS
- `team_tool_start` 事件：在首次触发时立即计算并设置 `currentTeammateSessions[agId] = sessionId + '-tm-' + agId`，不再等到 `team_done`
- `team_tool_end` 事件：每次工具结束后调用 `loadTeammateContent()` 刷新抽屉（因为 session 已写入）
- `done` 事件：兜底处理——合并 `tmCompletedAgents` 和仍在 `tmFloatAgents` 中的 agent（应对 `team_done` 丢失的场景），为全部 agent 创建摘要卡并追加到对话流末尾
- `renderMiniMessages()`：完整重写，改用与主对话流完全一致的 CSS 类：`.msg-user`/`.user-bubble`（用户消息）、`.msg-ai`/`.ai-body`/`.ai-reply-row`/`.ai-avatar`/`.ai-text`（AI 回复带头像和 markdown）、`.tool-block`/`.think-block`（工具和思考卡片，同款折叠样式）
- `.drawer-content` 新增 CSS：覆盖 `.user-bubble` max-width 和字号，适配 440px 抽屉宽度

---

## v0.10 — 2026-04-15

### 需求
> 1. Context Compressed 卡片高度不足，看不到 summary 内容；
> 2. 模型头像要换高级一点的，且只有文字答复时才显示头像（工具/思考卡片不显示）；
> 3. team 子 agent 卡片可点击，首次 team 工具调用自动弹出工作空间抽屉；
> 4. 压缩卡片应在对话流顶部，多次压缩只保留一个卡片，抽屉中用 Tab 区分各次压缩；
> 5. Teammate 工作时，输入框上方悬浮状态卡片，自动打开工作空间；对话结束后悬浮卡消失，摘要卡进入对话流

### 变更

#### `index.html` CSS
- `.msg-ai`：去掉 flex 布局（头像不再固定在行首）；新增 `.ai-reply-row`（flex + gap，包裹头像 + 文字）
- `.ai-avatar`：改为渐变背景 + 内阴影，呈现更高级的视觉效果；内部改用 sparkle 四角星 SVG
- `.compact-card`：重设计为块级布局——`.compact-card-header`（图标+标题+次数）+ `.compact-summary-text`（最多 4 行摘要）+ `.compact-footer`（"查看完整历史"按钮）；增加 hover 效果
- `.tm-status-card`：增加 `cursor:pointer` + hover 背景
- 新增 `#tmFloat` + `.tm-float-bar` + `.tm-float-dot` + `.tm-float-text`：输入框上方的悬浮 Teammate 状态条样式

#### `index.html` HTML
- `.input-wrap` 里 `.input-box` 前新增 `<div id="tmFloat" style="display:none">`

#### `index.html` JS
- 新增 `avatarSvg` 常量（四角星/sparkle SVG，premium 风格）
- `appendAiRow()`：不再内嵌头像 HTML，返回纯 `ai-body` div
- `getOrCreateTextEl()`（sendMessage 内）：创建文字元素时，用 `.ai-reply-row` 包裹头像 + `.ai-text`，确保只有文字回复带头像
- `switchSession()` 文字回复渲染：同样用 `.ai-reply-row` 包裹
- 新增 `compactSessions = []`、`compactCard = null` 全局状态
- `createCompactCard` → 替换为 `recordCompact(summary, transcriptFile)`：卡片 `insertBefore` 到 msgWrap 顶部，多次压缩更新同一张卡片并 push 到 `compactSessions`
- 新增 `renderCompactDrawer()`：抽屉标题"压缩历史"，Tabs 对应各次压缩
- 新增 `loadCompactTabContent({summary, transcriptFile})`：显示摘要 + 异步加载 transcript 历史
- `openDrawer()`：新增 `'compact'` 类型分支
- `ensureTeammateStatusCard()`：整张卡 `onclick` 打开抽屉（不只是按钮）；按钮点击 `stopPropagation`
- 新增 `tmFloatAgents`、`tmCompletedAgents`、`hasAutoOpenedDrawer` 状态
- 新增 `updateTmFloat()`：根据 `tmFloatAgents` 更新/隐藏悬浮条
- `team_tool_start` 事件：更新 float bar，首次触发时 `hasAutoOpenedDrawer=true` + `openDrawer`
- `team_tool_end` 事件：更新 float bar，刷新抽屉内容
- `team_text` 事件：更新 float bar 状态文字
- `team_done` 事件：将 agent 移至 `tmCompletedAgents`，隐藏 float bar；若抽屉已打开则刷新内容
- `done` 事件：隐藏 float bar，为 `tmCompletedAgents` 中的每个 agent 调用 `ensureTeammateStatusCard` 创建对话流摘要卡
- `sendMessage()` 重置：清空 float 状态、隐藏 `#tmFloat`
- `switchSession()` / `newConversation()`：重置 `compactSessions`、`compactCard`、float 状态

---

## v0.9 — 2026-04-15

### 需求
> 对话过程中看不到 Agent Team 卡片；对话完成后才在底部出现卡片；右侧工作空间太简陋，看不到执行细节

### 根因
1. `ensureTeammateStatusCard` 将卡片嵌套插入 `.ai-body`，被主 agent 内容压住不可见
2. `finishTeammateCard` 只更新已有卡片，若 `team_done` 先于 `team_tool_start` 到达则不创建卡片
3. `renderMiniMessages` 只渲染工具名 chip，没有 input/output 内容

### 变更
- `index.html` `ensureTeammateStatusCard()`：改为直接 `msgWrap.appendChild(card)`，Teammate 状态卡作为顶层行插入，实时可见
- `index.html` `finishTeammateCard()`：改为调用 `ensureTeammateStatusCard()`，即使 `team_done` 先到也能创建卡片
- `index.html` `renderMiniMessages()`：完整重写——先建 tool_call_id→result 查找表，再用可折叠 `.mini-tool-block` 展示每个工具的 Input 和 Output（输出最多 3000 字符）；AI 文本完整渲染
- `index.html` CSS：新增 `.mini-tool-block`、`.mini-tool-header`、`.mini-tool-body`、`.mini-tool-label`、`.mini-tool-code` 等工作空间抽屉用的工具块样式，支持点击折叠/展开

---

## v0.8 — 2026-04-15

### 需求
> 点击左侧对话历史恢复时，右侧工具卡片会默认展开，应该是已完成的默认关闭

### 变更
- `index.html` `switchSession()`：工具卡片渲染时移除 `open` class（原为 `tool-block done open`，改为 `tool-block done`），历史恢复后所有已完成工具卡默认折叠，用户点击展开查看

---

## v0.7 — 2026-04-15

### 需求
> Agent Team 模式下，对话被压缩后恢复时对话流乱掉；Teammate 工作数据丢失；工具卡片塞满对话流；
> 自动压缩（Layer 2）不触发前端 compact 事件；历史恢复时压缩消息作为普通气泡渲染

### 变更

#### 后端 service 模块
- `ContextCompactor.java`：`saveTranscript()` 改为返回文件名（basename）；`compact()` 将 `_transcript_file` 写入 compacted user 消息（私有字段，API 前剥离）
- `AgentEventListener.java`：`onCompactDone(summary)` → `onCompactDone(summary, transcriptFile)`
- `AgentLoop.java`：新增 `extractTranscriptFile()` 方法；model-triggered compact 和 auto-compact（Layer 2）均调用 `listener.onCompactDone(summary, transcriptFile)`（修复 Layer 2 静默 bug）
- `TeammateRunner.java`：新增 `SessionStore sessionStore` 字段（构造器注入）、`volatile String leadSessionId`、`setLeadSessionId()` 方法；spawn 时将两者传给 `TeammateLoop`
- `TeammateLoop.java`：新增 `sessionStore` 和 `leadSessionId` 字段；每次 working session 结束及线程退出时调用 `sessionStore.save(leadSessionId + "-tm-" + name, messages)`
- `SessionStore.java`：新增 `TeammateInfo` record 和 `listTeammates(leadSessionId)` 方法，扫描匹配的 teammate session 文件
- `AgentAssembler.java`：自动构建 `SessionStore` 并传入 `TeammateRunner`

#### 后端 start 模块
- `AgentBeans.java`：`teammateRunner()` bean 注入 `SessionStore`
- `StreamService.java`：请求开始时调用 `setLeadSessionId(sessionId)`，finally 时清除；`onCompactDone` 实现包含 `transcriptFile` 字段
- `ChatController.java`：新增 `GET /api/sessions/{sessionId}/teammates`（扫描 teammate session 列表）；新增 `GET /api/transcripts/{filename}`（按需加载压缩前历史，basename 校验防路径穿越）

#### 前端 `index.html`

**工作空间抽屉（右侧）**
- 新增 `.workspace-drawer` CSS：右侧 440px 固定面板，`transform: translateX(100%)` 收起，`.open` 时滑入
- 新增 `.drawer-header`、`.drawer-tabs`、`.drawer-tab.active`、`.drawer-content` 样式
- 新增 `.mini-*` 样式：抽屉内 mini message list 渲染
- `<div id="workspaceDrawer">` 加到 `.layout` 外侧
- `openDrawer(type, data)` / `closeDrawer()`：控制抽屉开关，同步 `.layout` `margin-right`
- `renderTranscriptDrawer({ transcriptFile, summary })`：加载 `/api/transcripts/{file}`，渲染压缩前历史
- `renderTeammateDrawer({ agentId })`：Tab 栏 + `loadTeammateContent()` 加载 `/api/sessions/{tmSessionId}/messages`

**Compact 卡片重设计**
- 替换为 slim 单行样式：`[icon] Context Compressed [20字摘要...] [查看完整历史 →]`
- 点击按钮调用 `openDrawer('transcript', { transcriptFile, summary })`
- 移除旧的 expand/collapse 交互

**Teammate 状态卡重设计**
- 移除旧的 `workspaceMap` + inline 工具块方案
- 新增 `teammateMap`、`currentTeammateSessions`
- `ensureTeammateStatusCard(agentId)`：创建 slim 单行状态卡
- `team_tool_start`：更新状态文本（工具名），计数++
- `team_tool_end` / `team_text`：更新状态文本
- `team_done`：记录 teammate sessionId，显示"查看工作区 →"按钮

**历史恢复 `switchSession()` 修复**
- 检测 `role=user && content.startsWith('[Context was compacted.')` → 渲染为 Compact 卡，不作 user bubble
- 新增 `skipNextAssistantAck` 标志：跳过 compact 后的 `Understood...` assistant 确认消息
- `switchSession` 末尾调用 `GET /api/sessions/{id}/teammates`，若有 teammate 则追加工作区入口卡

---

## v0.6 — 2026-04-15

### 需求
> 前台页面先展示了模型答复，然后展示了已思考卡片，最后展示的是工具卡片（顺序错误）

### 根因
前端用单个 `textEl` 变量接收整个流式会话的所有文字。该元素在**第一次** `text_delta` 时创建并固定插入 DOM，后续轮次（工具执行完后的最终回复）复用同一元素 → 最终回复出现在工具卡之前。当模型先吐文字再做推理（`text_delta` 早于 `thinking_start`）时，textEl 已在 think_block 之前，导致 reply → thinking → tool 的错误视觉顺序。

### 变更

#### `index.html`
- 新增 `textEls[]` 数组：追踪本次流式响应创建的所有文字块，用于 `done` 时批量渲染 markdown（之前只渲染最后一个 textEl）
- 新增 `needNewTextEl` 布尔标志：为 true 时 `getOrCreateTextEl()` 强制创建新元素而非复用
- `tool_end` / `tool_error`：执行后设 `needNewTextEl = true`，确保最终回复在所有工具卡之后创建新元素
- `thinking_start`：若 `textEl` 已存在（文字比推理先到达），设 `needNewTextEl = true`，保证推理卡之后的回复不会倒退到推理卡之前
- 断线重试逻辑：补充重置 `textEls = []` 和 `needNewTextEl = false`

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
