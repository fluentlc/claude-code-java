package ai.claude.code.web.service;

import ai.claude.code.agent.AgentAssembler;
import ai.claude.code.agent.AgentLoop;
import ai.claude.code.capability.BackgroundRunner;
import ai.claude.code.capability.ContextCompactor;
import ai.claude.code.capability.MessageBus;
import ai.claude.code.capability.SessionStore;
import ai.claude.code.capability.SkillLoader;
import ai.claude.code.capability.TaskStore;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.capability.TodoManager;
import ai.claude.code.capability.WorktreeManager;
import ai.claude.code.core.AgentEventListener;
import ai.claude.code.core.BaseTools;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.web.dto.ChatRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
                ? java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        + "-" + UUID.randomUUID().toString().substring(0, 8)
                : req.sessionId();

        JsonArray messages = sessionStore.load(sessionId);
        messages.add(OpenAiClient.userMessage(req.message()));

        SseAgentEventListener listener = new SseAgentEventListener(emitter);

        // Per-request stateful capabilities (avoid cross-request state pollution)
        TodoManager todoManager    = new TodoManager();
        ContextCompactor compactor = new ContextCompactor(client, workDir);

        AgentLoop loop = AgentAssembler.build(client, workDir,
                new BaseTools(workDir), todoManager, skillLoader,
                compactor, new TaskStore(workDir + "/.tasks"),
                bgRunner, worktreeManager, messageBus, teammateRunner);

        // Register listener so TeammateLoop threads can forward events to the main SSE stream
        teammateRunner.setMainListener(listener);

        try {
            // Send session_id as first event
            send(emitter, "session_id", "{\"sessionId\":\"" + sessionId + "\"}");

            loop.run(messages, listener);

            // Persist session after successful completion
            sessionStore.save(sessionId, messages);

            // Send done event
            JsonObject donePayload = new JsonObject();
            donePayload.addProperty("session_id", sessionId);
            send(emitter, "done", GSON.toJson(donePayload));
            emitter.complete();

        } catch (Exception e) {
            if (!listener.isCancelled()) {
                sendError(emitter, e.getMessage());
            }
            emitter.completeWithError(e);
        } finally {
            // Clear listener so orphaned teammate threads don't write to a stale emitter
            teammateRunner.setMainListener(null);
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

        @Override public void onDone() { /* done event sent in StreamService.run() after loop */ }

        @Override
        public void onError(String message) {
            if (cancelled) return;
            sendError(emitter, message);
        }

        // ── Teammate events ──

        @Override
        public void onTeamToolStart(String agentId, String id, String name, JsonObject input) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("agentId", agentId);
            p.addProperty("id", id);
            p.addProperty("name", name);
            p.add("input", input);
            send(emitter, "team_tool_start", GSON.toJson(p));
        }

        @Override
        public void onTeamToolEnd(String agentId, String id, boolean success, String result) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("agentId", agentId);
            p.addProperty("id", id);
            p.addProperty("success", success);
            p.addProperty("result", result);
            send(emitter, "team_tool_end", GSON.toJson(p));
        }

        @Override
        public void onTeamText(String agentId, String text) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("agentId", agentId);
            p.addProperty("text", text);
            send(emitter, "team_text", GSON.toJson(p));
        }

        @Override
        public void onTeamDone(String agentId) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("agentId", agentId);
            send(emitter, "team_done", GSON.toJson(p));
        }

        // ── Compact event ──

        @Override
        public void onCompactDone(String summary) {
            if (cancelled) return;
            JsonObject p = new JsonObject();
            p.addProperty("summary", summary);
            send(emitter, "compact_done", GSON.toJson(p));
        }
    }
}
