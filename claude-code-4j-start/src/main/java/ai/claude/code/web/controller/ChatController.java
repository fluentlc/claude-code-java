package ai.claude.code.web.controller;

import ai.claude.code.capability.SessionStore;
import ai.claude.code.web.dto.ChatRequest;
import ai.claude.code.web.dto.ChatResponse;
import ai.claude.code.web.dto.SessionMeta;
import ai.claude.code.web.service.ChatService;
import com.google.gson.JsonArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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

    @Autowired
    private ChatService chatService;

    @Autowired
    private SessionStore sessionStore;

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
                        m.updatedAt().toString()))
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
}
