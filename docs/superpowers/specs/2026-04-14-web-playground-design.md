# Web Playground UI — 设计规格

**日期**: 2026-04-14  
**项目**: claude-code-4j  
**状态**: 已批准，待实现

---

## 1. 背景与目标

claude-code-4j 目前提供 CLI REPL 和 REST API 两种交互方式，缺少浏览器可视化入口。

**目标**: 新增一个 Web Playground 页面，让用户通过浏览器与 Agent 对话，并实时看到：
- Agent 的思考过程（thinking state，模型支持时）
- 工具调用的执行状态与输入/输出
- 流式文字输出

**受众**: Java 开发者、后端工程师，对开发工具设计有较高审美要求。

---

## 2. 架构决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 前端技术栈 | 纯 HTML + Vanilla JS | 零构建依赖，Spring Boot 自动托管 `static/`，符合项目"可嵌入、轻量"定位 |
| 部署方式 | 内嵌 Spring Boot | 单文件 `index.html` 放入 `src/main/resources/static/`，启动即可用 |
| 实时输出 | SSE（Server-Sent Events） | 比 WebSocket 更简单，单向推送符合场景，原生 `EventSource` API 无需三方库 |
| UI 布局 | 单栏 + 内联展开 | 思考块和工具调用块嵌入对话流，时序最清晰，适合移动端 |
| AgentLoop 生命周期 | 每次请求独立 build | `AgentAssembler.build()` 创建独立实例，避免单例并发状态污染 |

---

## 3. 后端变更

### 3.0 OpenAiClient 流式改造（前置，必须先完成）

现有 `OpenAiClient.doPost()` 使用 `HttpURLConnection` 一次性读完响应，不支持流式。

新增方法：

```java
public void createMessageStream(String systemPrompt, JsonArray messages,
                                JsonArray tools, int maxTokens,
                                AgentEventListener listener)
```

实现要点：
- 请求体增加 `"stream": true`
- 逐行读取 `data: {...}` SSE chunk，解析 `choices[0].delta.content`
- 每个 chunk 回调 `listener.onTextDelta(text)`
- 特定模型（如 Claude）的 `thinking` block 字段若存在则回调 `onThinkingText()`，否则静默跳过
- **thinking 事件为可选**：仅在响应中有 reasoning/thinking 字段时触发，前端需容忍该事件不出现
- 流结束时解析 finish_reason，回调 `listener.onDone()`
- 异常时回调 `listener.onError(message)`（需在接口中新增）

### 3.1 新增 SSE 端点

```
POST /api/chat/stream
Content-Type: application/json
Body: { "sessionId": "(可选)", "message": "用户输入" }

Response: text/event-stream
```

### 3.2 SSE 事件协议（完整定义）

| Event | Payload JSON | 发送时机 |
|-------|-------------|---------|
| `session_id` | `{"sessionId":"uuid"}` | 首帧，sessionId 为新生成时 |
| `thinking_start` | `{}` | Agent 开始思考（模型支持时） |
| `thinking_text` | `{"text":"..."}` | 思考内容片段，可多次 |
| `thinking_end` | `{"duration_ms":3200}` | 思考结束 |
| `tool_start` | `{"id":"call_xxx","name":"bash","input":{"command":"ls"}}` | 工具开始执行 |
| `tool_end` | `{"id":"call_xxx","success":true,"result":"file1.txt\n..."}` | 工具执行完毕 |
| `tool_error` | `{"id":"call_xxx","success":false,"result":"Error: ..."}` | 工具执行失败（单独事件区分） |
| `text_delta` | `{"text":"..."}` | 最终回复文字片段，可多次 |
| `done` | `{"session_id":"uuid","usage":{"input_tokens":120,"output_tokens":80}}` | 本轮全部完成 |
| `error` | `{"message":"..."}` | 异常，连接随即关闭 |

**`tool_start.input` 序列化规则**：完整 JSON 对象，前端可用 `JSON.stringify(input, null, 2)` 格式化展示，无截断。

### 3.3 AgentEventListener 接口

```java
public interface AgentEventListener {
    void onThinkingStart();
    void onThinkingText(String text);
    void onThinkingEnd(long durationMs);
    void onToolStart(String id, String name, JsonObject input);
    void onToolEnd(String id, boolean success, String result);
    void onTextDelta(String text);
    void onDone();
    void onError(String message);
    boolean isCancelled();   // 每轮前检查，断连时返回 true
}
```

`AgentLoop.run()` 增加重载：

```java
public String run(JsonArray messages, AgentEventListener listener)
```

原有 `run(JsonArray messages)` 保持不变，内部使用 no-op listener。每轮工具调用前调用 `listener.isCancelled()` 检查，若为 true 则提前退出循环。

### 3.4 线程模型与 SseEmitter 配置

```java
// StreamController.java
@PostMapping("/api/chat/stream")
public SseEmitter stream(@RequestBody ChatRequest req) {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);  // 不超时，由 AgentLoop 控制
    executor.submit(() -> streamService.run(req, emitter));  // 异步线程，不阻塞 Tomcat
    return emitter;
}
```

`StreamService` 的 `SseAgentEventListener` 实现：
- 持有 `volatile boolean cancelled = false`
- `emitter.onCompletion(() -> cancelled = true)` — 客户端断连时设为 true
- `emitter.onTimeout(() -> cancelled = true)`
- `isCancelled()` 返回 `cancelled`
- 发送前检查 `cancelled`，已取消则静默丢弃

断连后服务端 AgentLoop 在下一轮 `isCancelled()` 检查时退出，不再继续调用 LLM，避免资源浪费。

### 3.5 AgentLoop 并发隔离策略

SSE 请求每次创建**轻量隔离实例**：共享无状态组件，仅对有状态 capability 各自 new：

```java
// StreamService.java — 由 Spring 注入共享组件
@Autowired OpenAiClient client;
@Autowired BackgroundRunner bgRunner;       // 共享线程池
@Autowired MessageBus messageBus;           // 共享收件箱
@Autowired TeammateRunner teammateRunner;   // 共享线程池
@Autowired SkillLoader skillLoader;         // 无状态
@Autowired WorktreeManager worktreeManager; // 无状态

// 每次请求独立创建有状态 capability
TodoManager todoManager     = new TodoManager();        // 每请求独立
ContextCompactor compactor  = new ContextCompactor(client, workDir);  // 每请求独立
TaskStore taskStore         = new TaskStore(workDir + "/.tasks");

AgentLoop loop = AgentAssembler.build(client, workDir,
    new BaseTools(workDir), todoManager, skillLoader, compactor,
    taskStore, bgRunner, worktreeManager, messageBus, teammateRunner);
loop.run(messages, listener);
```

`TeammateRunner`、`BackgroundRunner`、`MessageBus` 作为 Spring 单例共享（它们内部已有并发安全设计），`TodoManager` 和 `ContextCompactor` 每次请求独立创建，避免跨请求状态污染，同时不重复创建线程池。

现有同步 `/api/chat` 的 `ChatService` 继续使用 Spring 单例 `AgentLoop`，不受影响。

### 3.6 会话持久化与 SessionStore 改造

SSE 端点复用现有 `SessionStore`，并新增 `listAll()` 方法：

```java
// SessionStore.java 新增方法
public List<SessionMeta> listAll()
// SessionMeta record: { sessionId, preview(前50字), updatedAt(文件修改时间) }
// 实现：遍历 .sessions/*.json，读取文件修改时间和首条 user 消息作为 preview
```

会话流程：
- 请求开始：`sessionStore.load(sessionId)` 加载历史（sessionId 为空时自动生成 UUID）
- `done` 事件发出后：`sessionStore.save(sessionId, messages)` 持久化
- `DELETE /api/sessions/{id}` 同样适用于 stream 会话

### 3.7 新增只读端点

```java
// ChatController.java 新增
GET /api/sessions
Response: [{"sessionId":"...","preview":"帮我分析...","updatedAt":"2026-04-14T..."}]

GET /api/sessions/{sessionId}/messages
Response: {"sessionId":"...","messages":[...]}  // 原始 JsonArray，供前端回放历史
```

### 3.8 新增文件

```
claude-code-4j-service/src/main/java/ai/claude/code/
└── core/
    └── AgentEventListener.java          ← 新增接口

claude-code-4j-start/src/main/java/ai/claude/code/
└── web/
    ├── controller/
    │   └── StreamController.java        ← POST /api/chat/stream
    │   └── ChatController.java          ← 新增 GET /api/sessions, GET /api/sessions/{id}/messages
    ├── dto/
    │   └── SessionMeta.java             ← record { sessionId, preview, updatedAt }
    └── service/
        └── StreamService.java           ← SseAgentEventListener + AgentLoop 组装

claude-code-4j-service/src/main/java/ai/claude/code/capability/
└── SessionStore.java                    ← 新增 listAll() 方法
```

---

## 4. 前端规格

### 4.1 文件位置

```
claude-code-4j-start/src/main/resources/static/
└── index.html    ← 单文件，全部 CSS + JS 内联
```

### 4.2 设计系统（ui-ux-pro-max 产出）

**风格**: Dark OLED + Modern Dark Cinema  
**字体**:
- UI / 对话文字：`Plus Jakarta Sans` (Google Fonts)
- 代码 / 工具名 / 标签：`JetBrains Mono` (Google Fonts)
- **降级方案**：两者均有 `system-ui, sans-serif` / `monospace` 作为 fallback，网络不可达时不影响使用

**色彩 Token**:

```css
--s0: #07091a;          /* 页面底色 */
--s1: #0d1025;          /* 侧边栏 / 顶栏 */
--s2: #111428;          /* 卡片 / 面板 */
--s3: #181d35;          /* 输入框 / hover */
--s4: #1f2540;          /* active */
--t1: #f1f5f9;          /* 主文字 */
--t2: #94a3b8;          /* 次要文字 */
--t3: #475569;          /* 弱化文字 */
--a:  #6366f1;          /* Accent — indigo */
--a2: #818cf8;          /* Accent 亮 */
--ok: #34d399;          /* 成功绿 */
--run: #f59e0b;         /* 执行中橙 */
--b:  rgba(255,255,255,0.06);   /* 发丝边框 */
--ease: cubic-bezier(0.16, 1, 0.3, 1);
```

### 4.3 页面结构

```
┌─────────────────────────────────────────────────────┐
│  Topbar: 品牌名 / 模型选择 / Token 计量 / 新对话       │
├────────────┬────────────────────────────────────────┤
│  Sidebar   │  Messages (flex-col, overflow-y)        │
│  会话历史   │  ├─ [day separator]                    │
│  (232px)   │  ├─ user-bubble (flex-end)              │
│            │  └─ ai-row                              │
│            │      ├─ think-block (可选，collapsible) │
│            │      ├─ tool-block × N (collapsible)    │
│            │      └─ ai-text                         │
│            ├────────────────────────────────────────┤
│            │  Input area: SSE conn status + textarea │
└────────────┴────────────────────────────────────────┘
```

### 4.4 组件行为

**思考块 (think-block)**（仅 `thinking_start` 事件到达时创建）:
- 执行中：旋转 spinner + "Thinking…" + 思考内容实时追加
- 完成后："✓ Thought for Xs" + 点击可展开/折叠
- 若模型不返回 thinking 事件，整个块不创建，不留空白

**工具块 (tool-block)**（`tool_start` 事件时创建）:
- 执行中：橙色闪烁圆点 + 工具名橙色 + 显示 Input
- `tool_end`（success=true）：绿色圆点 + 绿色工具名 + 显示 Output
- `tool_error`（success=false）：红色圆点 + 红色工具名 + 显示错误信息
- 点击 header 展开/折叠

**输入框**（`<textarea>`）:
- `keydown` 监听：`Enter`（无 Shift）阻止默认换行并提交；`Shift+Enter` 正常换行
- 发送后禁用，`done` 或 `error` 事件后恢复
- SSE 断连 3 次重连失败后：输入框恢复可用，顶部显示错误横幅"连接已断开，请刷新重试"

**SSE 重连逻辑**：
- `EventSource` 断连时最多自动重连 3 次，间隔 1s / 2s / 4s（指数退避）
- 3 次均失败后：显示错误横幅，停止重连

**Token 计量**：
- `done` 事件 payload 中的 `usage` 字段更新顶栏进度条
- 首次加载前显示"—"

### 4.5 会话侧边栏

- 页面加载时 `GET /api/sessions` 获取会话列表（**需新增此端点**，返回 `[{sessionId, preview, updatedAt}]`）
- 点击会话切换：清空当前 messages，加载选中会话历史（`GET /api/sessions/{id}/messages`，**需新增**）
- "新对话"按钮：清空 messages，清空 sessionId

> **注意**：以上两个只读端点需同步在后端新增（`ChatController` 中补充），复用 `SessionStore` 的文件读取能力。

---

## 5. 不在本次范围内

- 多模型热切换后端（UI 入口保留，点击无响应或 Toast 提示"暂不支持"）
- 用户认证 / 多用户隔离
- 文件上传
- Agent Teams 可视化面板
- 现有 `/api/chat` 单例并发安全加固（超出本次范围，留待后续）

---

## 6. 实现顺序

1. **`AgentEventListener` 接口**（`service` 模块，新增文件）
2. **`OpenAiClient` 流式改造**（新增 `createMessageStream` 方法）
3. **`AgentLoop.run()` 重载**（接受 listener，每轮检查 `isCancelled()`）
4. **`StreamController` + `StreamService`**（SSE 端点，独立 AgentLoop 实例）
5. **`GET /api/sessions` + `GET /api/sessions/{id}/messages`**（只读端点）
6. **`index.html`**（EventSource 接入 SSE，基础消息渲染）
7. **思考块 / 工具块**渲染逻辑
8. **会话侧边栏**（列表加载、切换）
9. **收尾**：Token 计量、重连逻辑、错误横幅、响应式适配

---

## 7. 关键约束

- `index.html` 单文件，不引入任何前端构建工具
- 外部资源仅允许 Google Fonts CDN，提供系统字体降级
- 图标使用内联 SVG，禁止 emoji 作为图标（ui-ux-pro-max 规范）
- 所有文字对比度 ≥ 4.5:1（WCAG AA）
- thinking 事件为可选，前端不依赖其出现
- SSE 断连后服务端在下一轮检查点退出，不持续消耗 LLM
