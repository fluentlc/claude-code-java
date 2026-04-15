# Web Playground Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a browser-based Web Playground so users can chat with the Agent, see streaming output, tool call visualization, and thinking states — all in real-time via SSE.

**Architecture:** New `POST /api/chat/stream` SSE endpoint backed by a fresh per-request `AgentLoop` instance. `OpenAiClient` gains a streaming method that fires events through a new `AgentEventListener` interface. Frontend is a single `index.html` with `EventSource` connecting to the SSE endpoint.

**Tech Stack:** Java 17 + Spring Boot 3.2 `SseEmitter`; Vanilla JS + EventSource; `Plus Jakarta Sans` + `JetBrains Mono` fonts; Gson for JSON parsing on backend.

---

## File Map

### New files

| File | Responsibility |
|------|---------------|
| `claude-code-4j-service/src/main/java/ai/claude/code/core/AgentEventListener.java` | Interface: streaming event callbacks for AgentLoop |
| `claude-code-4j-start/src/main/java/ai/claude/code/web/controller/StreamController.java` | `POST /api/chat/stream` SSE endpoint |
| `claude-code-4j-start/src/main/java/ai/claude/code/web/service/StreamService.java` | SseAgentEventListener + per-request AgentLoop assembly |
| `claude-code-4j-start/src/main/java/ai/claude/code/web/dto/SessionMeta.java` | Record: `{sessionId, preview, updatedAt}` |
| `claude-code-4j-start/src/main/resources/static/index.html` | Single-file frontend (CSS + JS inline) |

### Modified files

| File | Change |
|------|--------|
| `claude-code-4j-service/src/main/java/ai/claude/code/core/OpenAiClient.java` | Add `createMessageStream()` |
| `claude-code-4j-service/src/main/java/ai/claude/code/agent/AgentLoop.java` | Add `run(messages, listener)` overload |
| `claude-code-4j-service/src/main/java/ai/claude/code/capability/SessionStore.java` | Add `listAll()` method |
| `claude-code-4j-start/src/main/java/ai/claude/code/web/controller/ChatController.java` | Add `GET /api/sessions` and `GET /api/sessions/{id}/messages` |
| `claude-code-4j-start/src/main/java/ai/claude/code/config/AgentBeans.java` | Add `ExecutorService` bean for SSE threads |

---

## Task 1: AgentEventListener Interface

**Files:**
- Create: `claude-code-4j-service/src/main/java/ai/claude/code/core/AgentEventListener.java`

- [ ] **Step 1: Create the interface**

```java
package ai.claude.code.core;

import com.google.gson.JsonObject;

/**
 * Streaming event callbacks for AgentLoop.
 * Implement to receive real-time events during Agent execution.
 * All methods have empty defaults so implementors only override what they need.
 */
public interface AgentEventListener {
    /** Agent begins a thinking phase (model-dependent, may not fire). */
    default void onThinkingStart() {}
    /** Incremental thinking text fragment. */
    default void onThinkingText(String text) {}
    /** Thinking phase ended. @param durationMs elapsed milliseconds */
    default void onThinkingEnd(long durationMs) {}
    /** Tool call is about to execute. @param input complete tool input JSON */
    default void onToolStart(String id, String name, JsonObject input) {}
    /** Tool call completed successfully. */
    default void onToolEnd(String id, boolean success, String result) {}
    /** Incremental text fragment from the model's final reply. */
    default void onTextDelta(String text) {}
    /** All rounds complete — AgentLoop.run() is about to return. */
    default void onDone() {}
    /** Fatal error — AgentLoop.run() will throw after this callback. */
    default void onError(String message) {}
    /** AgentLoop checks this before each tool-execution round. Return true to abort. */
    default boolean isCancelled() { return false; }
}
```

- [ ] **Step 2: Compile to verify no errors**

```bash
mvn compile -pl claude-code-4j-service -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add claude-code-4j-service/src/main/java/ai/claude/code/core/AgentEventListener.java
git commit -m "feat: add AgentEventListener interface for SSE streaming"
```

---

## Task 2: OpenAiClient — createMessageStream()

**Files:**
- Modify: `claude-code-4j-service/src/main/java/ai/claude/code/core/OpenAiClient.java`

The existing `createMessage()` reads the full response at once. The new `createMessageStream()` must:
1. Add `"stream": true` to request body
2. Read SSE lines (`data: {...}`) one by one
3. Fire `listener.onTextDelta()` for each text chunk
4. Accumulate the complete message so AgentLoop can extract tool_calls and stop_reason
5. Return the assembled `JsonObject` in the same shape as `createMessage()` returns (so AgentLoop works unchanged)

OpenAI streaming chunk format:
```
data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null,"index":0}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"bash","arguments":""}}]},"finish_reason":null}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"command\":\"ls\"}"}}]},"finish_reason":null}]}
data: {"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}],"usage":{"prompt_tokens":120,"completion_tokens":25}}
data: [DONE]
```

- [ ] **Step 1: Add `createMessageStream()` method to OpenAiClient**

Insert after the closing brace of `createMessage()` (around line 182), before `private String doPost(...)`:

```java
/**
 * Streaming variant of createMessage(). Fires listener events for each chunk.
 * Returns the assembled JsonObject in the same shape as createMessage()
 * so AgentLoop can process tool_calls and finish_reason identically.
 *
 * @param listener event callbacks; must not be null
 * @return assembled response JsonObject (choices[0].message + choices[0].finish_reason)
 */
public JsonObject createMessageStream(String systemPrompt, JsonArray messages,
                                      JsonArray tools, int maxTokens,
                                      AgentEventListener listener) {
    JsonArray fullMessages = new JsonArray();
    fullMessages.add(systemMessage(systemPrompt));
    for (JsonElement el : messages) fullMessages.add(el);

    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.add("messages", fullMessages);
    if (tools != null && tools.size() > 0) body.add("tools", tools);
    body.addProperty("max_tokens", maxTokens);
    body.addProperty("stream", true);

    return doPostStream(apiPath, GSON.toJson(body), listener);
}

private JsonObject doPostStream(String path, String jsonBody, AgentEventListener listener) {
    HttpURLConnection conn = null;
    try {
        URL url = new URL(baseUrl + path);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);  // 5-minute read timeout for long tool chains
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        if (debugPrintPayload) System.out.println("=====流式请求报文=====\n" + jsonBody);

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            InputStream errIs = conn.getErrorStream();
            StringBuilder errSb = new StringBuilder();
            if (errIs != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(errIs, StandardCharsets.UTF_8))) {
                    String l; while ((l = br.readLine()) != null) errSb.append(l);
                }
            }
            throw new RuntimeException("API Error (HTTP " + status + "): " + errSb);
        }

        // Accumulators for assembling the complete response
        StringBuilder textAccum    = new StringBuilder();
        // tool_calls[index] -> partial JsonObject being assembled
        java.util.Map<Integer, JsonObject> toolCallMap = new java.util.LinkedHashMap<>();
        // tool_calls[index] -> arguments string builder
        java.util.Map<Integer, StringBuilder> argsMap  = new java.util.LinkedHashMap<>();
        String finishReason = "";
        JsonObject usageObj = null;

        long thinkingStart = 0;
        boolean inThinking = false;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                JsonObject chunk;
                try { chunk = JsonParser.parseString(data).getAsJsonObject(); }
                catch (Exception ex) { continue; }

                if (debugPrintPayload) System.out.println("[stream] " + data);

                // Extract usage if present
                if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                    usageObj = chunk.getAsJsonObject("usage");
                }

                JsonArray choices = chunk.has("choices") ? chunk.getAsJsonArray("choices") : new JsonArray();
                if (choices.size() == 0) continue;
                JsonObject choice = choices.get(0).getAsJsonObject();

                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    finishReason = choice.get("finish_reason").getAsString();
                }

                JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : new JsonObject();

                // --- thinking / reasoning field (Claude / o1 style) ---
                if (delta.has("reasoning") && !delta.get("reasoning").isJsonNull()) {
                    String thinkText = delta.get("reasoning").getAsString();
                    if (!inThinking) {
                        inThinking = true;
                        thinkingStart = System.currentTimeMillis();
                        listener.onThinkingStart();
                    }
                    listener.onThinkingText(thinkText);
                }

                // --- text content ---
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    String text = delta.get("content").getAsString();
                    if (!text.isEmpty()) {
                        if (inThinking) {
                            // Transition: thinking ended, text started
                            inThinking = false;
                            listener.onThinkingEnd(System.currentTimeMillis() - thinkingStart);
                        }
                        textAccum.append(text);
                        listener.onTextDelta(text);
                    }
                }

                // --- tool_calls deltas ---
                if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                    for (JsonElement tcEl : delta.getAsJsonArray("tool_calls")) {
                        JsonObject tcDelta = tcEl.getAsJsonObject();
                        int idx = tcDelta.has("index") ? tcDelta.get("index").getAsInt() : 0;

                        if (!toolCallMap.containsKey(idx)) {
                            toolCallMap.put(idx, new JsonObject());
                            argsMap.put(idx, new StringBuilder());
                        }
                        JsonObject tc = toolCallMap.get(idx);

                        if (tcDelta.has("id") && !tcDelta.get("id").isJsonNull()) {
                            tc.addProperty("id", tcDelta.get("id").getAsString());
                            tc.addProperty("type", "function");
                        }
                        if (tcDelta.has("function")) {
                            JsonObject fnDelta = tcDelta.getAsJsonObject("function");
                            if (fnDelta.has("name") && !fnDelta.get("name").isJsonNull()) {
                                if (!tc.has("function")) tc.add("function", new JsonObject());
                                tc.getAsJsonObject("function").addProperty("name", fnDelta.get("name").getAsString());
                            }
                            if (fnDelta.has("arguments") && !fnDelta.get("arguments").isJsonNull()) {
                                argsMap.get(idx).append(fnDelta.get("arguments").getAsString());
                            }
                        }
                    }
                }
            }
        }

        // Finalize tool_calls: set assembled arguments
        JsonArray toolCallsArray = new JsonArray();
        for (Map.Entry<Integer, JsonObject> e : toolCallMap.entrySet()) {
            JsonObject tc = e.getValue();
            String argsStr = argsMap.get(e.getKey()).toString();
            if (!tc.has("function")) tc.add("function", new JsonObject());
            tc.getAsJsonObject("function").addProperty("arguments", argsStr);
            toolCallsArray.add(tc);
        }

        if (inThinking) {
            listener.onThinkingEnd(System.currentTimeMillis() - thinkingStart);
        }

        // Assemble response in the same shape createMessage() returns
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        if (textAccum.length() > 0) {
            message.addProperty("content", textAccum.toString());
        } else {
            message.add("content", com.google.gson.JsonNull.INSTANCE);
        }
        if (toolCallsArray.size() > 0) {
            message.add("tool_calls", toolCallsArray);
        }

        JsonObject choiceObj = new JsonObject();
        choiceObj.add("message", message);
        choiceObj.addProperty("finish_reason", finishReason);

        JsonArray choicesArr = new JsonArray();
        choicesArr.add(choiceObj);

        JsonObject response = new JsonObject();
        response.add("choices", choicesArr);
        if (usageObj != null) response.add("usage", usageObj);

        return response;

    } catch (IOException e) {
        throw new RuntimeException("Stream request failed: " + e.getMessage(), e);
    } finally {
        if (conn != null) conn.disconnect();
    }
}
```

- [ ] **Step 2: Add missing import for `AgentEventListener`**

At the top of `OpenAiClient.java`, after the existing imports, add:
```java
import ai.claude.code.core.AgentEventListener;
```

- [ ] **Step 3: Compile**

```bash
mvn compile -pl claude-code-4j-service -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add claude-code-4j-service/src/main/java/ai/claude/code/core/OpenAiClient.java
git commit -m "feat: add createMessageStream() to OpenAiClient for SSE streaming"
```

---

## Task 3: AgentLoop — streaming run() overload

**Files:**
- Modify: `claude-code-4j-service/src/main/java/ai/claude/code/agent/AgentLoop.java`

The new `run(JsonArray messages, AgentEventListener listener)` overload mirrors the existing `run(JsonArray messages)` but:
- Uses `client.createMessageStream()` instead of `client.createMessage()`
- Fires `listener.onToolStart()` / `listener.onToolEnd()` around each tool execution
- Checks `listener.isCancelled()` at the start of each iteration
- Calls `listener.onDone()` at the end

The existing `run(JsonArray messages)` must NOT change (it uses the no-op default interface methods implicitly when called from CliRunner and ChatService).

- [ ] **Step 1: Add the streaming `run()` overload**

Insert after `run(JsonArray messages)` closes (after line 151) and before `inplaceReplace()`:

```java
/**
 * Streaming variant of run(). Fires AgentEventListener callbacks for real-time output.
 * Checks listener.isCancelled() before each tool-execution round to support disconnect abort.
 *
 * @param messages conversation history (appended in-place)
 * @param listener event callbacks
 * @return last text reply (may be empty if run ends on a tool call)
 */
public String run(JsonArray messages, AgentEventListener listener) {
    String lastText = "";

    for (int i = 0; i < MAX_TURNS; i++) {
        if (listener.isCancelled()) break;

        System.out.println("[Agent/SSE] 第 " + (i + 1) + " 轮...");
        JsonObject response     = client.createMessageStream(systemPrompt, messages, toolDefs, 8000, listener);
        JsonObject assistantMsg = OpenAiClient.getAssistantMessage(response);
        String stopReason       = OpenAiClient.getStopReason(response);

        messages.add(OpenAiClient.assistantMessage(assistantMsg));

        String text = OpenAiClient.extractText(assistantMsg);
        if (!text.isEmpty()) lastText = text;

        if (!"tool_calls".equals(stopReason)) break;

        if (listener.isCancelled()) break;

        boolean usedTodo    = false;
        boolean usedCompact = false;
        JsonArray toolCalls = OpenAiClient.getToolCalls(assistantMsg);

        for (JsonElement el : toolCalls) {
            if (listener.isCancelled()) break;

            JsonObject tc   = el.getAsJsonObject();
            String toolId   = tc.get("id").getAsString();
            JsonObject fn   = tc.getAsJsonObject("function");
            String toolName = fn.get("name").getAsString();
            JsonObject toolInput = JsonParser.parseString(
                    fn.get("arguments").getAsString()).getAsJsonObject();

            listener.onToolStart(toolId, toolName, toolInput);
            System.out.println("[Tool/SSE] " + toolName + " <- " + ToolUtils.brief(toolInput.toString(), 100));

            ToolHandler handler = dispatch.get(toolName);
            boolean success = true;
            String result;
            try {
                result = handler != null
                        ? handler.execute(toolInput)
                        : "Error: unknown tool '" + toolName + "'";
                if (handler == null) success = false;
            } catch (Exception ex) {
                result = "Error: " + ex.getMessage();
                success = false;
            }

            System.out.println("[Tool/SSE] " + toolName + " -> " + ToolUtils.brief(result, 150));
            listener.onToolEnd(toolId, success, result);

            if (TODO_TOOL.equals(toolName))    usedTodo    = true;
            if (COMPACT_TOOL.equals(toolName)) usedCompact = true;
            messages.add(OpenAiClient.toolResultMessage(toolId, result));
        }

        if (usedCompact) inplaceReplace(messages, compactor.compact(messages, systemPrompt));
        inplaceReplace(messages, compactor.microCompact(messages));
        if (compactor.shouldAutoCompact(messages))
            inplaceReplace(messages, compactor.compact(messages, systemPrompt));

        if (todoManager.needsReminder())
            messages.add(OpenAiClient.userMessage(
                    "<system-reminder>Please update your todos to reflect current progress.</system-reminder>"));
        if (!usedTodo) todoManager.tick();

        for (String n : bgRunner.drainNotifications())
            messages.add(OpenAiClient.userMessage("<system-reminder>" + n + "</system-reminder>"));

        for (JsonObject msg : messageBus.readInbox(LEAD_ID)) {
            String from    = msg.has("from")    ? msg.get("from").getAsString()    : "unknown";
            String content = msg.has("content") ? msg.get("content").getAsString() : msg.toString();
            messages.add(OpenAiClient.userMessage(
                    "<system-reminder>[Message from " + from + "] " + content + "</system-reminder>"));
        }
    }

    listener.onDone();
    return lastText;
}
```

- [ ] **Step 2: Add import for AgentEventListener at top of AgentLoop.java**

```java
import ai.claude.code.core.AgentEventListener;
```

- [ ] **Step 3: Compile**

```bash
mvn compile -pl claude-code-4j-service -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add claude-code-4j-service/src/main/java/ai/claude/code/agent/AgentLoop.java
git commit -m "feat: add streaming run(messages, listener) overload to AgentLoop"
```

---

## Task 4: SessionStore — listAll() method

**Files:**
- Modify: `claude-code-4j-service/src/main/java/ai/claude/code/capability/SessionStore.java`

`listAll()` traverses `.sessions/*.json`, reads modification time from filesystem and extracts the first user message as `preview`.

- [ ] **Step 1: Add `listAll()` and `SessionMeta` return type to SessionStore**

Insert after the `delete()` method (around line 88), before `sessionPath()`:

```java
/**
 * Metadata snapshot for one session — used by GET /api/sessions.
 */
public record SessionMeta(String sessionId, String preview, java.time.Instant updatedAt) {}

/**
 * List all sessions, sorted by most-recently-updated first.
 * Preview = first user message text, truncated to 50 chars.
 */
public java.util.List<SessionMeta> listAll() {
    File dir = new File(sessionsDir);
    File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
    if (files == null) return java.util.Collections.emptyList();

    java.util.List<SessionMeta> result = new java.util.ArrayList<>();
    for (File f : files) {
        String name = f.getName();
        String sessionId = name.substring(0, name.length() - 5); // strip .json
        java.time.Instant updatedAt = java.time.Instant.ofEpochMilli(f.lastModified());

        String preview = "";
        try {
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(content).getAsJsonArray();
            for (com.google.gson.JsonElement el : arr) {
                com.google.gson.JsonObject msg = el.getAsJsonObject();
                if ("user".equals(msg.has("role") ? msg.get("role").getAsString() : "")) {
                    com.google.gson.JsonElement c = msg.get("content");
                    if (c != null && !c.isJsonNull() && c.isJsonPrimitive()) {
                        String raw = c.getAsString();
                        preview = raw.length() > 50 ? raw.substring(0, 50) + "…" : raw;
                    }
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[SessionStore] listAll parse error for " + sessionId + ": " + e.getMessage());
        }

        result.add(new SessionMeta(sessionId, preview, updatedAt));
    }

    result.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
    return result;
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -pl claude-code-4j-service -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add claude-code-4j-service/src/main/java/ai/claude/code/capability/SessionStore.java
git commit -m "feat: add SessionStore.listAll() for session list endpoint"
```

---

## Task 5: StreamService + StreamController (SSE endpoint)

**Files:**
- Create: `claude-code-4j-start/src/main/java/ai/claude/code/web/service/StreamService.java`
- Create: `claude-code-4j-start/src/main/java/ai/claude/code/web/controller/StreamController.java`
- Modify: `claude-code-4j-start/src/main/java/ai/claude/code/config/AgentBeans.java`

`StreamService` contains the `SseAgentEventListener` inner class. It assembles a fresh per-request `AgentLoop` (shared stateless beans + per-request `TodoManager`/`ContextCompactor`) and runs it on an executor thread.

`StreamController` accepts `POST /api/chat/stream`, creates `SseEmitter(Long.MAX_VALUE)`, submits to executor, returns immediately.

- [ ] **Step 1: Add `ExecutorService` bean to AgentBeans**

In `AgentBeans.java`, add only the executor bean. Do NOT expose `workDir` as a `String` bean (it causes `NoUniqueBeanDefinitionException`). Instead, `StreamService` will use `@Value` directly.

```java
// Add inside AgentBeans class:

@Bean
public java.util.concurrent.ExecutorService streamExecutor() {
    return java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-stream");
        t.setDaemon(true);
        return t;
    });
}
```

- [ ] **Step 2: Create StreamService.java**

```java
package ai.claude.code.web.service;

import ai.claude.code.agent.AgentAssembler;
import ai.claude.code.agent.AgentLoop;
import ai.claude.code.capability.BackgroundRunner;
import ai.claude.code.capability.ContextCompactor;
import ai.claude.code.capability.MessageBus;
import ai.claude.code.capability.SessionStore;
import ai.claude.code.capability.SkillLoader;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.capability.TodoManager;
import ai.claude.code.capability.WorktreeManager;
import ai.claude.code.core.AgentEventListener;
import ai.claude.code.core.BaseTools;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.web.dto.ChatRequest;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * SSE streaming service.
 * Assembles a fresh per-request AgentLoop (shared stateless beans,
 * per-request stateful TodoManager + ContextCompactor) and runs it.
 */
@Service
public class StreamService {

    private static final Gson GSON = new GsonBuilder().create();

    @Value("${claude.workdir:${user.dir}}")
    private String workDir;

    @Autowired private OpenAiClient client;
    @Autowired private BackgroundRunner bgRunner;
    @Autowired private MessageBus messageBus;
    @Autowired private TeammateRunner teammateRunner;
    @Autowired private SkillLoader skillLoader;
    @Autowired private WorktreeManager worktreeManager;
    @Autowired private SessionStore sessionStore;

    /**
     * Runs the Agent loop for one SSE request.
     * Called from StreamController on an executor thread.
     */
    public void run(ChatRequest req, SseEmitter emitter) {
        String sessionId = (req.sessionId() == null || req.sessionId().isBlank())
                ? UUID.randomUUID().toString()
                : req.sessionId();

        JsonArray messages = sessionStore.load(sessionId);
        messages.add(OpenAiClient.userMessage(req.message()));

        SseAgentEventListener listener = new SseAgentEventListener(emitter);

        // Per-request stateful capabilities (avoid cross-request state pollution)
        TodoManager todoManager    = new TodoManager();
        ContextCompactor compactor = new ContextCompactor(client, workDir);

        AgentLoop loop = AgentAssembler.build(client, workDir,
                new BaseTools(workDir), todoManager, skillLoader,
                compactor, new ai.claude.code.capability.TaskStore(workDir + "/.tasks"),
                bgRunner, worktreeManager, messageBus, teammateRunner);

        try {
            // Send session_id as first event
            send(emitter, "session_id", "{\"sessionId\":\"" + sessionId + "\"}");

            loop.run(messages, listener);

            // Persist session after successful completion
            sessionStore.save(sessionId, messages);

            // Send done event with usage info
            JsonObject donePayload = new JsonObject();
            donePayload.addProperty("session_id", sessionId);
            send(emitter, "done", GSON.toJson(donePayload));
            emitter.complete();

        } catch (Exception e) {
            if (!listener.isCancelled()) {
                sendError(emitter, e.getMessage());
            }
            emitter.completeWithError(e);
        }
    }

    private void send(SseEmitter emitter, String eventName, String json) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            // Client disconnected — ignore, isCancelled() will catch it next round
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("message", message != null ? message : "Unknown error");
            emitter.send(SseEmitter.event().name("error").data(GSON.toJson(payload)));
        } catch (IOException ignored) {}
    }

    /**
     * SseEmitter-backed AgentEventListener.
     * Translates AgentLoop events into SSE event protocol.
     */
    class SseAgentEventListener implements AgentEventListener {
        private final SseEmitter emitter;
        private volatile boolean cancelled = false;
        private long thinkingStartMs = 0;

        SseAgentEventListener(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onCompletion(() -> cancelled = true);
            emitter.onTimeout(()    -> cancelled = true);
            emitter.onError(t      -> cancelled = true);
        }

        @Override public boolean isCancelled() { return cancelled; }

        @Override
        public void onThinkingStart() {
            if (cancelled) return;
            thinkingStartMs = System.currentTimeMillis();
            send(emitter, "thinking_start", "{}");
        }

        @Override
        public void onThinkingText(String text) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("text", text);
            send(emitter, "thinking_text", GSON.toJson(p));
        }

        @Override
        public void onThinkingEnd(long durationMs) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("duration_ms", durationMs);
            send(emitter, "thinking_end", GSON.toJson(p));
        }

        @Override
        public void onToolStart(String id, String name, JsonObject input) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            p.addProperty("name", name);
            p.add("input", input);
            send(emitter, "tool_start", GSON.toJson(p));
        }

        @Override
        public void onToolEnd(String id, boolean success, String result) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            p.addProperty("success", success);
            p.addProperty("result", result);
            send(emitter, success ? "tool_end" : "tool_error", GSON.toJson(p));
        }

        @Override
        public void onTextDelta(String text) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("text", text);
            send(emitter, "text_delta", GSON.toJson(p));
        }

        @Override public void onDone() { /* done event sent in StreamService.run() */ }

        @Override
        public void onError(String message) {
            if (cancelled) return;
            sendError(emitter, message);
        }
    }
}
```

- [ ] **Step 3: Create StreamController.java**

```java
package ai.claude.code.web.controller;

import ai.claude.code.web.dto.ChatRequest;
import ai.claude.code.web.service.StreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;

/**
 * SSE streaming endpoint.
 * POST /api/chat/stream — returns text/event-stream
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    @Autowired private StreamService streamService;
    @Autowired private ExecutorService streamExecutor;

    @PostMapping("/chat/stream")
    public SseEmitter stream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        streamExecutor.submit(() -> streamService.run(req, emitter));
        return emitter;
    }
}
```

- [ ] **Step 4: Compile both modules**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add claude-code-4j-start/src/main/java/ai/claude/code/web/service/StreamService.java
git add claude-code-4j-start/src/main/java/ai/claude/code/web/controller/StreamController.java
git add claude-code-4j-start/src/main/java/ai/claude/code/config/AgentBeans.java
git commit -m "feat: add SSE streaming endpoint POST /api/chat/stream"
```

---

## Task 6: Session list endpoints

**Files:**
- Create: `claude-code-4j-start/src/main/java/ai/claude/code/web/dto/SessionMeta.java`
- Modify: `claude-code-4j-start/src/main/java/ai/claude/code/web/controller/ChatController.java`

Note: `SessionStore.SessionMeta` is an inner record in the service module. The start module's `SessionMeta` DTO is a separate record for the REST layer — simpler, serializable by Jackson.

- [ ] **Step 1: Create SessionMeta DTO**

```java
package ai.claude.code.web.dto;

/**
 * Session metadata for GET /api/sessions response.
 */
public record SessionMeta(String sessionId, String preview, String updatedAt) {}
```

- [ ] **Step 2: Add session list endpoints to ChatController**

Add these imports to ChatController:
```java
import ai.claude.code.capability.SessionStore;
import ai.claude.code.web.dto.SessionMeta;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
```

Add these beans and methods to the class (note the `produces` attribute on `getSessionMessages` — required to prevent Spring from returning `text/plain`):
```java
@Autowired
private SessionStore sessionStore;

/**
 * List all sessions, newest first.
 * GET /api/sessions
 */
@GetMapping("/sessions")
public List<SessionMeta> listSessions() {
    return sessionStore.listAll().stream()
            .map(m -> new SessionMeta(
                    m.sessionId(),
                    m.preview(),
                    m.updatedAt().toString()))
            .collect(Collectors.toList());
}

/**
 * Get full message history for a session.
 * GET /api/sessions/{sessionId}/messages
 * produces = "application/json" is required — method returns a raw String.
 */
@GetMapping(value = "/sessions/{sessionId}/messages", produces = "application/json")
public String getSessionMessages(@PathVariable String sessionId) {
    JsonArray messages = sessionStore.load(sessionId);
    return "{\"sessionId\":\"" + sessionId + "\",\"messages\":" + messages.toString() + "}";
}
```

Also add imports needed at the top of `ChatController.java`:
```java
import ai.claude.code.capability.SessionStore;
import ai.claude.code.web.dto.SessionMeta;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add claude-code-4j-start/src/main/java/ai/claude/code/web/dto/SessionMeta.java
git add claude-code-4j-start/src/main/java/ai/claude/code/web/controller/ChatController.java
git commit -m "feat: add GET /api/sessions and GET /api/sessions/{id}/messages endpoints"
```

---

## Task 7: Frontend — index.html (base structure + SSE client)

**Files:**
- Create: `claude-code-4j-start/src/main/resources/static/index.html`

This task creates the full single-file frontend. Design tokens come from the approved `playground-v4.html` mockup (Dark OLED + Linear indigo). The SSE client uses `EventSource` (POST via fetch + ReadableStream, since `EventSource` is GET-only).

**Important:** Browser's native `EventSource` only supports GET requests. Since our endpoint is `POST /api/chat/stream`, we must use `fetch()` with a streaming response and manually parse SSE lines from the `ReadableStream`.

- [ ] **Step 1: Create index.html with full CSS design system**

Create `claude-code-4j-start/src/main/resources/static/index.html` using the design tokens from `playground-v4.html`:

```html
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>claude-code-4j · Playground</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
/* ── Design tokens (Dark OLED + Linear indigo) ── */
:root {
  --s0:#07091a; --s1:#0d1025; --s2:#111428; --s3:#181d35; --s4:#1f2540;
  --t1:#f1f5f9; --t2:#94a3b8; --t3:#475569; --t4:#2d3a52;
  --a:#6366f1; --a2:#818cf8; --a3:#4f46e5; --ag:rgba(99,102,241,0.12); --ab:rgba(99,102,241,0.25);
  --ok:#34d399; --ok2:rgba(52,211,153,0.12); --ok3:rgba(52,211,153,0.25);
  --run:#f59e0b; --run2:rgba(245,158,11,0.12); --err:#f87171;
  --b:rgba(255,255,255,0.06); --b2:rgba(255,255,255,0.10); --b3:rgba(255,255,255,0.15);
  --sans:'Plus Jakarta Sans',system-ui,sans-serif;
  --mono:'JetBrains Mono','Fira Code',monospace;
  --ease:cubic-bezier(0.16,1,0.3,1);
}
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%;overflow:hidden}
body{background:var(--s0);color:var(--t1);font-family:var(--sans);font-size:14px;line-height:1.6;-webkit-font-smoothing:antialiased}

/* ── Topbar ── */
.topbar{height:52px;background:var(--s1);border-bottom:1px solid var(--b);display:flex;align-items:center;padding:0 20px;gap:14px;flex-shrink:0;position:relative;z-index:10}
.wordmark{font-family:var(--mono);font-size:13px;font-weight:500;color:var(--t1);display:flex;align-items:center}
.wordmark-sep{color:var(--t3);margin:0 7px}
.wordmark-page{color:var(--a2);font-family:var(--sans);font-size:13px}
.tb-divider{width:1px;height:18px;background:var(--b)}
.model-badge{display:flex;align-items:center;gap:6px;padding:4px 10px;background:var(--s2);border:1px solid var(--b);border-radius:8px;font-family:var(--mono);font-size:11px;color:var(--t2);cursor:default}
.model-dot{width:6px;height:6px;border-radius:50%;background:var(--ok);flex-shrink:0}
.tb-right{margin-left:auto;display:flex;align-items:center;gap:8px}
.token-meter{display:flex;align-items:center;gap:6px;font-size:11px;color:var(--t3)}
.token-bar-wrap{width:60px;height:3px;background:var(--s3);border-radius:2px;overflow:hidden}
.token-bar{height:100%;width:0%;background:var(--a);border-radius:2px;transition:width 0.4s var(--ease)}
#tokenText{font-family:var(--mono);font-size:11px}
.btn-new{padding:5px 12px;background:var(--ag);border:1px solid var(--ab);border-radius:8px;font-size:12px;font-weight:500;color:var(--a2);cursor:pointer;font-family:var(--sans);transition:all 0.15s;display:flex;align-items:center;gap:6px}
.btn-new:hover{background:var(--ab);border-color:var(--a)}
.btn-new svg{width:12px;height:12px}

/* ── Layout ── */
.layout{display:flex;flex:1;overflow:hidden;height:calc(100vh - 52px)}

/* ── Sidebar ── */
.sidebar{width:232px;background:var(--s1);border-right:1px solid var(--b);display:flex;flex-direction:column;flex-shrink:0;overflow:hidden}
.sidebar-head{padding:16px 12px 8px;font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:1.2px;color:var(--t3)}
.session-list{flex:1;overflow-y:auto;padding:0 8px 12px}
.session-list::-webkit-scrollbar{width:3px}
.session-list::-webkit-scrollbar-thumb{background:var(--s3);border-radius:2px}
.session-item{padding:8px 10px;border-radius:8px;cursor:pointer;transition:background 0.1s;margin-bottom:1px}
.session-item:hover{background:var(--s2)}
.session-item.active{background:var(--ag)}
.si-title{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.session-item.active .si-title{color:var(--a2)}
.si-meta{font-size:11px;color:var(--t4);margin-top:2px}
.session-item.active .si-meta{color:rgba(129,140,248,0.5)}

/* ── Main ── */
.main{flex:1;display:flex;flex-direction:column;overflow:hidden}

/* ── Error banner ── */
#errBanner{display:none;background:rgba(248,113,113,0.12);border-bottom:1px solid rgba(248,113,113,0.2);padding:8px 32px;font-size:12px;color:var(--err);text-align:center}

/* ── Messages ── */
.messages{flex:1;overflow-y:auto;padding:28px 0 8px;scroll-behavior:smooth}
.messages::-webkit-scrollbar{width:4px}
.messages::-webkit-scrollbar-thumb{background:var(--s2);border-radius:2px}
.msg-wrap{max-width:740px;margin:0 auto;padding:0 28px}

/* Day separator */
.day-sep{text-align:center;margin:16px 0 8px;font-size:11px;color:var(--t3);display:flex;align-items:center;gap:8px}
.day-sep::before,.day-sep::after{content:'';flex:1;height:1px;background:var(--b)}

/* User bubble */
.msg-user{display:flex;justify-content:flex-end;margin-bottom:20px}
.user-bubble{background:var(--s2);border:1px solid var(--b);border-radius:14px 14px 3px 14px;padding:10px 15px;font-size:14px;max-width:72%;line-height:1.65;white-space:pre-wrap;word-break:break-word}

/* AI row */
.msg-ai{display:flex;gap:11px;margin-bottom:24px;align-items:flex-start}
.ai-avatar{width:28px;height:28px;background:linear-gradient(135deg,var(--a),var(--a2));border-radius:8px;display:flex;align-items:center;justify-content:center;flex-shrink:0;margin-top:2px}
.ai-avatar svg{width:14px;height:14px}
.ai-body{flex:1;min-width:0}

/* Thinking block */
.think-block{border:1px solid rgba(129,140,248,0.2);border-radius:10px;margin-bottom:8px;overflow:hidden;background:rgba(99,102,241,0.04);transition:border-color 0.2s}
.think-block:hover{border-color:rgba(129,140,248,0.3)}
.think-header{display:flex;align-items:center;gap:8px;padding:8px 13px;cursor:pointer;user-select:none}
.think-spinner{width:13px;height:13px;border:1.5px solid rgba(129,140,248,0.2);border-top-color:var(--a2);border-radius:50%;animation:spin 0.8s linear infinite;flex-shrink:0}
@keyframes spin{to{transform:rotate(360deg)}}
.think-check{width:13px;height:13px;border-radius:50%;background:var(--ok2);border:1px solid var(--ok3);display:flex;align-items:center;justify-content:center;flex-shrink:0}
.think-check svg{width:7px;height:7px;color:var(--ok)}
.think-label{font-size:12px;font-weight:500;color:var(--a2)}
.think-label.done{color:var(--t3);font-weight:400}
.think-dur{font-size:11px;color:var(--t3);font-family:var(--mono)}
.think-toggle{margin-left:auto;color:var(--t3);transition:transform 0.2s}
.think-block.open .think-toggle{transform:rotate(180deg)}
.think-body{padding:0 13px 10px;font-size:12.5px;color:var(--t2);line-height:1.75;font-style:italic;border-top:1px solid rgba(99,102,241,0.1);display:none}
.think-block.open .think-body{display:block;padding-top:9px}

/* Tool block */
.tool-block{border:1px solid var(--b);border-radius:10px;margin-bottom:8px;overflow:hidden;background:var(--s1);transition:border-color 0.2s}
.tool-block:hover{border-color:var(--b2)}
.tool-block.running{border-color:rgba(245,158,11,0.25);background:var(--run2)}
.tool-block.done{border-color:rgba(52,211,153,0.2)}
.tool-block.failed{border-color:rgba(248,113,113,0.2)}
.tool-header{display:flex;align-items:center;gap:8px;padding:8px 13px;cursor:pointer;user-select:none}
.tool-dot{width:6px;height:6px;border-radius:50%;flex-shrink:0}
.tool-dot.running{background:var(--run);box-shadow:0 0 6px var(--run);animation:pulse 1.2s ease infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:0.35}}
.tool-dot.done{background:var(--ok)}
.tool-dot.failed{background:var(--err)}
.tool-dot.pending{background:var(--t4)}
.tool-fn{font-family:var(--mono);font-size:12px;font-weight:500;color:var(--t1)}
.tool-block.running .tool-fn{color:var(--run)}
.tool-block.done .tool-fn{color:var(--ok)}
.tool-block.failed .tool-fn{color:var(--err)}
.tool-status{font-size:11px;color:var(--t3)}
.tool-toggle{margin-left:auto;color:var(--t3);transition:transform 0.2s}
.tool-block.open .tool-toggle{transform:rotate(180deg)}
.tool-body{border-top:1px solid var(--b);padding:8px 13px 10px;display:none}
.tool-block.open .tool-body{display:block}
.tool-label{font-size:10px;font-weight:600;letter-spacing:0.8px;text-transform:uppercase;margin-bottom:5px}
.tool-label.in{color:rgba(245,158,11,0.7)}
.tool-label.out{color:rgba(52,211,153,0.7)}
.tool-label.err{color:rgba(248,113,113,0.7)}
.tool-code{background:var(--s0);border:1px solid var(--b);border-radius:6px;padding:6px 9px;font-family:var(--mono);font-size:11.5px;color:var(--t2);white-space:pre-wrap;word-break:break-all;line-height:1.6;margin-bottom:6px;max-height:200px;overflow-y:auto}

/* AI text */
.ai-text{font-size:14px;line-height:1.75;margin-top:4px}
.ai-text code{font-family:var(--mono);font-size:12.5px;background:var(--s2);border:1px solid var(--b);padding:1px 5px;border-radius:4px;color:var(--a2)}
.cursor{display:inline-block;width:2px;height:14px;background:var(--a);border-radius:1px;animation:blink 1s step-end infinite;vertical-align:text-bottom;margin-left:1px}
@keyframes blink{50%{opacity:0}}

/* ── Input area ── */
.input-wrap{max-width:740px;margin:0 auto;width:100%;padding:8px 28px 20px}
.conn-status{display:flex;align-items:center;gap:6px;margin-bottom:8px}
.conn-pip{width:6px;height:6px;border-radius:50%;background:var(--ok);transition:background 0.3s}
.conn-pip.busy{background:var(--run);animation:pulse 1s ease infinite}
.conn-pip.err{background:var(--err)}
.conn-text{font-size:11px;color:var(--t3)}
.input-box{background:var(--s2);border:1px solid var(--b);border-radius:14px;transition:border-color 0.2s,box-shadow 0.2s;overflow:hidden}
.input-box:focus-within{border-color:var(--ab);box-shadow:0 0 0 3px rgba(99,102,241,0.08)}
.input-row{display:flex;align-items:flex-end;padding:10px 10px 8px 15px;gap:8px}
textarea#msgInput{flex:1;background:transparent;border:none;outline:none;resize:none;font-family:var(--sans);font-size:14px;color:var(--t1);line-height:1.55;min-height:24px;max-height:140px}
textarea#msgInput::placeholder{color:var(--t4)}
.send-btn{width:32px;height:32px;background:var(--a);border:none;border-radius:9px;cursor:pointer;display:flex;align-items:center;justify-content:center;flex-shrink:0;transition:all 0.15s;color:#fff}
.send-btn:hover{background:var(--a2);box-shadow:0 0 14px rgba(99,102,241,0.4)}
.send-btn:disabled{background:var(--s3);cursor:not-allowed;box-shadow:none}
.send-btn svg{width:14px;height:14px}
.input-footer{display:flex;align-items:center;gap:6px;padding:0 13px 8px}
.kbd{font-size:11px;color:var(--t3);padding:2px 7px;background:var(--s3);border-radius:4px;border:1px solid var(--b);font-family:var(--mono)}
.input-hint{font-size:11px;color:var(--t4);margin-left:auto}
</style>
</head>
<body>

<!-- Topbar -->
<div class="topbar">
  <div class="wordmark">
    claude-code-4j<span class="wordmark-sep">/</span><span class="wordmark-page">Playground</span>
  </div>
  <div class="tb-divider"></div>
  <div class="model-badge">
    <div class="model-dot"></div>
    <span id="modelName">—</span>
  </div>
  <div class="tb-right">
    <div class="token-meter">
      <div class="token-bar-wrap"><div class="token-bar" id="tokenBar"></div></div>
      <span id="tokenText">—</span>
    </div>
    <button class="btn-new" id="btnNew">
      <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
        <path d="M6 2v8M2 6h8"/>
      </svg>
      新对话
    </button>
  </div>
</div>

<div class="layout">
  <!-- Sidebar -->
  <div class="sidebar">
    <div class="sidebar-head">对话历史</div>
    <div class="session-list" id="sessionList"></div>
  </div>

  <!-- Main -->
  <div class="main">
    <div id="errBanner">连接已断开，请刷新重试</div>

    <div class="messages" id="messages">
      <div class="msg-wrap" id="msgWrap"></div>
    </div>

    <div class="input-wrap">
      <div class="conn-status">
        <div class="conn-pip" id="connPip"></div>
        <span class="conn-text" id="connText">就绪</span>
      </div>
      <div class="input-box">
        <div class="input-row">
          <textarea id="msgInput" placeholder="发消息给 Agent…" rows="1"></textarea>
          <button class="send-btn" id="sendBtn">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M8 13V3M4 7l4-4 4 4"/>
            </svg>
          </button>
        </div>
        <div class="input-footer">
          <span class="kbd">Enter</span><span style="font-size:11px;color:var(--t4)">发送</span>
          <span class="kbd">Shift+Enter</span><span style="font-size:11px;color:var(--t4)">换行</span>
          <span class="input-hint">Agent 可读写文件 · 执行命令</span>
        </div>
      </div>
    </div>
  </div>
</div>

<script>
/* ══ State ══ */
let sessionId = null;
let streaming  = false;
let retries    = 0;
const MAX_RETRY = 3;

/* ══ DOM refs ══ */
const msgWrap   = document.getElementById('msgWrap');
const messages  = document.getElementById('messages');
const msgInput  = document.getElementById('msgInput');
const sendBtn   = document.getElementById('sendBtn');
const connPip   = document.getElementById('connPip');
const connText  = document.getElementById('connText');
const sessionList = document.getElementById('sessionList');
const errBanner = document.getElementById('errBanner');

/* ══ Auto-resize textarea ══ */
msgInput.addEventListener('input', () => {
  msgInput.style.height = 'auto';
  msgInput.style.height = Math.min(msgInput.scrollHeight, 140) + 'px';
});

/* ══ Send on Enter ══ */
msgInput.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendMessage();
  }
});
sendBtn.addEventListener('click', sendMessage);
document.getElementById('btnNew').addEventListener('click', newConversation);

/* ══ Helpers ══ */
function setStatus(state, text) {
  connPip.className = 'conn-pip' + (state === 'busy' ? ' busy' : state === 'err' ? ' err' : '');
  connText.textContent = text;
}

function setInputDisabled(disabled) {
  msgInput.disabled = disabled;
  sendBtn.disabled  = disabled;
}

function scrollBottom() {
  messages.scrollTop = messages.scrollHeight;
}

function appendDaySep(label) {
  const el = document.createElement('div');
  el.className = 'day-sep';
  el.textContent = label;
  msgWrap.appendChild(el);
}

function appendUserBubble(text) {
  const row = document.createElement('div');
  row.className = 'msg-user';
  const bubble = document.createElement('div');
  bubble.className = 'user-bubble';
  bubble.textContent = text;
  row.appendChild(bubble);
  msgWrap.appendChild(row);
  scrollBottom();
}

/* Returns the ai-body div for the current AI turn */
function appendAiRow() {
  const row = document.createElement('div');
  row.className = 'msg-ai';
  row.innerHTML = `
    <div class="ai-avatar">
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M8 2l1.8 3.6L14 6.5l-3 2.9.7 4.1L8 11.5l-3.7 1.9.7-4.1L2 6.5l4.2-.9z"/>
      </svg>
    </div>
    <div class="ai-body"></div>`;
  msgWrap.appendChild(row);
  scrollBottom();
  return row.querySelector('.ai-body');
}

/* ══ Thinking block ══ */
function createThinkBlock(aiBody) {
  const block = document.createElement('div');
  block.className = 'think-block open';
  block.innerHTML = `
    <div class="think-header" onclick="this.parentElement.classList.toggle('open')">
      <div class="think-spinner"></div>
      <span class="think-label">正在思考...</span>
      <span class="think-toggle">
        <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M2 4l3 3 3-3"/></svg>
      </span>
    </div>
    <div class="think-body"></div>`;
  aiBody.appendChild(block);
  scrollBottom();
  return {
    block,
    body: block.querySelector('.think-body'),
    header: block.querySelector('.think-header'),
    label: block.querySelector('.think-label'),
    spinnerWrap: block.querySelector('.think-spinner')
  };
}

function finishThinkBlock(refs, durationMs) {
  const secs = (durationMs / 1000).toFixed(1);
  refs.spinnerWrap.outerHTML = `
    <div class="think-check">
      <svg viewBox="0 0 8 8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M1.5 4l2 2 3-3"/></svg>
    </div>`;
  refs.label.textContent = `已思考 ${secs}s`;
  refs.label.classList.add('done');
  refs.block.classList.remove('open');
}

/* ══ Tool block ══ */
function createToolBlock(aiBody, id, name, inputJson) {
  const block = document.createElement('div');
  block.className = 'tool-block running open';
  block.dataset.id = id;
  block.innerHTML = `
    <div class="tool-header" onclick="this.parentElement.classList.toggle('open')">
      <div class="tool-dot running"></div>
      <span class="tool-fn">${escHtml(name)}</span>
      <span class="tool-status">· 执行中</span>
      <span class="tool-toggle">
        <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M2 4l3 3 3-3"/></svg>
      </span>
    </div>
    <div class="tool-body">
      <div class="tool-label in">Input</div>
      <div class="tool-code">${escHtml(JSON.stringify(inputJson, null, 2))}</div>
    </div>`;
  aiBody.appendChild(block);
  scrollBottom();
  return block;
}

function finishToolBlock(id, success, result) {
  const block = document.querySelector(`.tool-block[data-id="${id}"]`);
  if (!block) return;
  const state = success ? 'done' : 'failed';
  block.className = `tool-block ${state} open`;
  block.querySelector('.tool-dot').className = `tool-dot ${state}`;
  block.querySelector('.tool-status').textContent = success ? '· 完成' : '· 失败';

  const body = block.querySelector('.tool-body');
  const outLabel = document.createElement('div');
  outLabel.className = `tool-label ${success ? 'out' : 'err'}`;
  outLabel.textContent = success ? 'Output' : 'Error';
  const outCode = document.createElement('div');
  outCode.className = 'tool-code';
  outCode.textContent = result;
  body.appendChild(outLabel);
  body.appendChild(outCode);
  scrollBottom();
}

/* ══ Escape HTML ══ */
function escHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

/* ══ Send message ══ */
async function sendMessage() {
  const text = msgInput.value.trim();
  if (!text || streaming) return;

  msgInput.value = '';
  msgInput.style.height = 'auto';
  setInputDisabled(true);
  streaming = true;
  retries   = 0;
  errBanner.style.display = 'none';

  appendUserBubble(text);
  const aiBody = appendAiRow();

  setStatus('busy', '正在连接...');

  let thinkRefs  = null;
  let textEl     = null;
  let cursor     = null;

  function getOrCreateTextEl() {
    if (!textEl) {
      textEl = document.createElement('div');
      textEl.className = 'ai-text';
      cursor = document.createElement('span');
      cursor.className = 'cursor';
      textEl.appendChild(cursor);
      aiBody.appendChild(textEl);
    }
    return { textEl, cursor };
  }

  // Parses SSE lines with proper event-name tracking (fetch ReadableStream)
  async function doStreamV2() {
    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: text })
      });

      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setStatus('busy', '接收数据...');

      const reader  = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer    = '';
      let eventName = 'message';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        const lines = buffer.split('\n');
        buffer = lines.pop();

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.substring(5).trim();
            handleSseEvent(eventName, data);
            eventName = 'message';
          } else if (line === '') {
            // blank line separates events, eventName already reset above
          }
        }
      }

      // Stream ended normally
      finishStreaming();
    } catch (e) {
      if (retries < MAX_RETRY) {
        retries++;
        const delay = Math.pow(2, retries - 1) * 1000;
        setStatus('err', `连接断开，${delay/1000}s 后重试 (${retries}/${MAX_RETRY})...`);
        await new Promise(r => setTimeout(r, delay));
        retries < MAX_RETRY ? await doStreamV2() : finalFail();
      } else {
        finalFail();
      }
    }
  }

  function finalFail() {
    setStatus('err', '连接失败');
    errBanner.style.display = 'block';
    finishStreaming();
  }

  function handleSseEvent(eventName, data) {
    if (!data || data === '[DONE]') return;
    let payload;
    try { payload = JSON.parse(data); } catch { return; }

    switch (eventName) {
      case 'session_id':
        sessionId = payload.sessionId;
        setStatus('busy', `会话 ${sessionId.substring(0,8)}…`);
        break;
      case 'thinking_start':
        thinkRefs = createThinkBlock(aiBody);
        break;
      case 'thinking_text':
        if (thinkRefs) thinkRefs.body.textContent += payload.text;
        scrollBottom();
        break;
      case 'thinking_end':
        if (thinkRefs) finishThinkBlock(thinkRefs, payload.duration_ms || 0);
        break;
      case 'tool_start':
        createToolBlock(aiBody, payload.id, payload.name, payload.input || {});
        break;
      case 'tool_end':
        finishToolBlock(payload.id, true, payload.result || '');
        break;
      case 'tool_error':
        finishToolBlock(payload.id, false, payload.result || '');
        break;
      case 'text_delta': {
        const { textEl: tel, cursor: cur } = getOrCreateTextEl();
        tel.insertBefore(document.createTextNode(payload.text), cur);
        scrollBottom();
        break;
      }
      case 'done':
        if (payload && payload.usage) updateTokenMeter(payload.usage);
        if (cursor) cursor.remove();
        finishStreaming();
        loadSessionList();
        break;
      case 'error':
        setStatus('err', payload.message || '未知错误');
        if (cursor) cursor.remove();
        finishStreaming();
        break;
    }
  }

  function finishStreaming() {
    streaming = false;
    setInputDisabled(false);
    setStatus('', '就绪');
    msgInput.focus();
  }

  await doStreamV2();
}  // end sendMessage()

/* ══ Token meter ══ */
function updateTokenMeter(usage) {
  const total = (usage.prompt_tokens || usage.input_tokens || 0)
              + (usage.completion_tokens || usage.output_tokens || 0);
  const pct   = Math.min(100, (total / 128000) * 100);
  document.getElementById('tokenBar').style.width = pct + '%';
  document.getElementById('tokenText').textContent =
      total >= 1000 ? (total / 1000).toFixed(1) + 'k tok' : total + ' tok';
}

/* ══ Session sidebar ══ */
async function loadSessionList() {
  try {
    const res = await fetch('/api/sessions');
    if (!res.ok) return;
    const sessions = await res.json();
    sessionList.innerHTML = '';
    if (sessions.length === 0) {
      sessionList.innerHTML = '<div style="padding:10px 10px;font-size:12px;color:var(--t4)">暂无历史对话</div>';
      return;
    }
    for (const s of sessions) {
      const item = document.createElement('div');
      item.className = 'session-item' + (s.sessionId === sessionId ? ' active' : '');
      item.dataset.id = s.sessionId;
      const updatedAt = s.updatedAt ? new Date(s.updatedAt) : null;
      const timeStr   = updatedAt ? formatRelativeTime(updatedAt) : '';
      item.innerHTML = `
        <div class="si-title">${escHtml(s.preview || '（空会话）')}</div>
        <div class="si-meta">${escHtml(timeStr)}</div>`;
      item.addEventListener('click', () => switchSession(s.sessionId));
      sessionList.appendChild(item);
    }
  } catch (e) {
    console.warn('loadSessionList failed', e);
  }
}

async function switchSession(id) {
  if (streaming) return;
  sessionId = id;
  msgWrap.innerHTML = '';
  setStatus('', '加载历史...');

  try {
    const res = await fetch(`/api/sessions/${id}/messages`);
    if (!res.ok) throw new Error('Failed');
    const json = await res.json();
    const msgs = json.messages || [];

    for (const msg of msgs) {
      if (msg.role === 'user' && typeof msg.content === 'string') {
        appendUserBubble(msg.content);
      } else if (msg.role === 'assistant') {
        const content = (typeof msg.content === 'string') ? msg.content : '';
        if (content) {
          const aiBody = appendAiRow();
          const textEl = document.createElement('div');
          textEl.className = 'ai-text';
          textEl.textContent = content;
          aiBody.appendChild(textEl);
        }
      }
    }
  } catch (e) {
    console.warn('switchSession failed', e);
  }

  setStatus('', '就绪');
  document.querySelectorAll('.session-item').forEach(el => {
    el.classList.toggle('active', el.dataset.id === id);
  });
  scrollBottom();
}

function newConversation() {
  if (streaming) return;
  sessionId = null;
  msgWrap.innerHTML = '';
  setStatus('', '就绪');
  document.querySelectorAll('.session-item').forEach(el => el.classList.remove('active'));
  msgInput.focus();
}

function formatRelativeTime(date) {
  const diff = Date.now() - date.getTime();
  if (diff < 60000)  return '刚刚';
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
  return Math.floor(diff / 86400000) + ' 天前';
}

/* ══ Init ══ */
loadSessionList();
</script>
</body>
</html>
```

- [ ] **Step 2: Start the application and verify the page loads**

```bash
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application" &
sleep 5
curl -s http://localhost:8080/ | head -5
```

Expected: HTML content starting with `<!DOCTYPE html>`

- [ ] **Step 3: Commit**

```bash
git add claude-code-4j-start/src/main/resources/static/index.html
git commit -m "feat: add Web Playground frontend (index.html)"
```

---

## Task 8: Integration smoke test

**Goal:** Verify the full SSE pipeline works end-to-end before final polish.

- [ ] **Step 1: Build the full project**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Start the application**

```bash
mvn exec:java -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application" 2>&1 &
sleep 8
```

- [ ] **Step 3: Test SSE endpoint with curl**

```bash
curl -s -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好，用一句话介绍自己"}' \
  --max-time 60
```

Expected output (event lines in any order):
```
event: session_id
data: {"sessionId":"..."}

event: text_delta
data: {"text":"我是"}

... (more text_delta events)

event: done
data: {"session_id":"..."}
```

- [ ] **Step 4: Test session list endpoints**

```bash
curl -s http://localhost:8080/api/sessions | python3 -m json.tool
```

Expected: JSON array with at least one session.

- [ ] **Step 5: Test open the frontend in browser**

Open `http://localhost:8080/` in a browser. Verify:
- Page loads without console errors
- Session sidebar shows the test session
- Can type a message and press Enter to send
- SSE events render as text appears in real-time

- [ ] **Step 6: Kill the test server**

```bash
pkill -f "claude-code-4j-start" 2>/dev/null; true
```

- [ ] **Step 7: Commit any fixes found during testing**

```bash
git add -u
git commit -m "fix: integration issues found during smoke test"
```

---

## Task 9: Final polish — error handling and responsive layout

**Files:**
- Modify: `claude-code-4j-start/src/main/resources/static/index.html`

Verify and finalize:
- Error banner shows correctly on 3 failed retries
- Model name in topbar reflects actual configured model (fetch from `/actuator/info` or hardcode from response)
- Sidebar hides gracefully on narrow viewports (≤640px)

- [ ] **Step 1: Add responsive CSS for narrow screens**

Add inside the `<style>` block:
```css
@media (max-width: 640px) {
  .sidebar { display: none; }
  .wordmark-page { display: none; }
}
```

- [ ] **Step 2: Fetch model name on init**

In the `<script>` init section, add:
```js
// Try to get model name from actuator or session test
async function initModelName() {
  try {
    const res = await fetch('/actuator/health');
    if (res.ok) {
      document.getElementById('modelName').textContent = 'claude-code-4j';
    }
  } catch { /* ignore */ }
}
initModelName();
```

- [ ] **Step 3: Verify CORS is not an issue for local dev**

The frontend and API share the same origin (Spring Boot serves both), so CORS is not needed. Confirm by checking network tab — all requests go to same host.

- [ ] **Step 4: Final compile + commit**

```bash
mvn compile -q
git add claude-code-4j-start/src/main/resources/static/index.html
git commit -m "polish: responsive layout and model name display for Web Playground"
```

---

## Summary

| Task | Files touched | Key output |
|------|--------------|-----------|
| 1 | `AgentEventListener.java` (new) | Streaming callback interface |
| 2 | `OpenAiClient.java` | `createMessageStream()` with SSE chunk parsing |
| 3 | `AgentLoop.java` | Streaming `run(messages, listener)` overload |
| 4 | `SessionStore.java` | `listAll()` for session list endpoint |
| 5 | `StreamService.java` (new), `StreamController.java` (new), `AgentBeans.java` | POST /api/chat/stream SSE endpoint |
| 6 | `SessionMeta.java` (new), `ChatController.java` | GET /api/sessions, GET /api/sessions/{id}/messages |
| 7 | `index.html` (new) | Full Web Playground frontend |
| 8 | — | Integration smoke test |
| 9 | `index.html` | Responsive polish |
