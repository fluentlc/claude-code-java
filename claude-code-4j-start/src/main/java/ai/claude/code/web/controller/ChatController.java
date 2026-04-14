package ai.claude.code.web.controller;

import ai.claude.code.web.dto.ChatRequest;
import ai.claude.code.web.dto.ChatResponse;
import ai.claude.code.web.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
