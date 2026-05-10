package ai.claude.code.web.controller;

import ai.claude.code.capability.ModelStore;
import ai.claude.code.capability.SessionStore;
import ai.claude.code.web.dto.ChatRequest;
import ai.claude.code.web.dto.ChatResponse;
import ai.claude.code.web.dto.SessionMeta;
import ai.claude.code.web.service.ChatService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API 控制器。
 * REST API Controller.
 *
 * POST   /api/chat              — 发送消息给 Agent / Send a message to the Agent
 * DELETE /api/sessions/{id}    — 清除会话历史 / Clear session history
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Gson GSON = new GsonBuilder().create();

    @Value("${claude.workdir:${user.dir}}")
    private String workDir;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private ModelStore modelStore;

    /**
     * 发送消息给 Agent。
     * Send a message to the Agent.
     *
     * Request:  { "sessionId": "(optional)", "message": "Hello" }
     * Response: { "sessionId": "uuid", "reply": "..." }
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String[] result = chatService.chat(request.sessionId(), request.message());
        return new ChatResponse(result[0], result[1]);
    }

    /**
     * 清除指定会话的历史记录。
     * Clear history for a specific session.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable("sessionId") String sessionId) {
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }

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
                        m.updatedAt().toString(),
                        sessionStore.listTeammates(m.sessionId()).size()))
                .collect(Collectors.toList());
    }

    /**
     * Get full message history for a session.
     * GET /api/sessions/{sessionId}/messages
     * produces = "application/json" is required — method returns a raw String.
     */
    @GetMapping(value = "/sessions/{sessionId}/messages", produces = "application/json")
    public String getSessionMessages(@PathVariable("sessionId") String sessionId) {
        JsonArray messages = sessionStore.load(sessionId);
        return "{\"sessionId\":\"" + sessionId + "\",\"messages\":" + messages.toString() + "}";
    }

    /**
     * List teammate sessions for a given lead session.
     * GET /api/sessions/{sessionId}/teammates
     * Returns: [{ "name": "alice", "sessionId": "{leadId}-tm-alice" }, ...]
     */
    @GetMapping(value = "/sessions/{sessionId}/teammates", produces = "application/json")
    public String getTeammates(@PathVariable("sessionId") String sessionId) {
        List<SessionStore.TeammateInfo> teammates = sessionStore.listTeammates(sessionId);
        return GSON.toJson(teammates);
    }

    /**
     * Serve a transcript file from the .transcripts/ directory.
     * GET /api/transcripts/{filename}
     * Basename-only check prevents path traversal.
     */
    @GetMapping(value = "/transcripts/{filename}", produces = "application/json")
    public ResponseEntity<String> getTranscript(@PathVariable("filename") String filename) {
        // Security: only allow alphanumeric/underscore/hyphen/dot, must end with .json
        if (!filename.matches("[\\w\\-]+\\.json")) {
            return ResponseEntity.badRequest().build();
        }
        String safeBasename = new File(filename).getName();
        java.nio.file.Path path = Paths.get(workDir, ".transcripts", safeBasename);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return ResponseEntity.ok(content);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get model config from .models/models.json.
     * GET /api/model-config
     */
    @GetMapping(value = "/model-config", produces = "application/json")
    public String getModelConfig() {
        JsonObject cfg = modelStore.load();
        // Mask apiKey for security: only return last 4 chars
        if (cfg.has("apiKey") && !cfg.get("apiKey").isJsonNull()) {
            String key = cfg.get("apiKey").getAsString();
            if (key.length() > 4) {
                cfg.addProperty("apiKey", "***" + key.substring(key.length() - 4));
            } else if (!key.isEmpty()) {
                cfg.addProperty("apiKey", "***");
            }
        }
        return cfg.toString();
    }

    /**
     * Save model config to .models/models.json.
     * POST /api/model-config
     */
    @PostMapping(value = "/model-config", consumes = "application/json")
    public ResponseEntity<Void> saveModelConfig(@RequestBody java.util.Map<String, String> config) {
        JsonObject obj = new JsonObject();
        if (config.containsKey("apiKey"))  obj.addProperty("apiKey",  config.get("apiKey"));
        if (config.containsKey("baseUrl")) obj.addProperty("baseUrl", config.get("baseUrl"));
        if (config.containsKey("modelId")) obj.addProperty("modelId", config.get("modelId"));
        modelStore.save(obj);
        return ResponseEntity.ok().build();
    }
}
