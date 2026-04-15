package ai.claude.code.web.service;

import ai.claude.code.agent.AgentLoop;
import ai.claude.code.capability.SessionStore;
import ai.claude.code.core.OpenAiClient;
import com.google.gson.JsonArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 会话管理服务 — 持久化消息历史并调用 AgentLoop。
 * Session management service — persists message history and invokes AgentLoop.
 */
@Service
public class ChatService {

    @Autowired
    private AgentLoop agentLoop;

    @Autowired
    private SessionStore sessionStore;

    /**
     * 处理一条用户消息，返回 Agent 回复。
     * Process a user message and return Agent reply.
     *
     * @param sessionId 会话 ID（null 时自动生成）/ session ID (auto-generated if null)
     * @param message   用户输入 / user input
     * @return [sessionId, reply] 元组 / [sessionId, reply] tuple
     */
    public String[] chat(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            sessionId = ts + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // 从文件加载历史消息，进程重启后自动恢复 / Load history from file (survives restarts)
        JsonArray messages = sessionStore.load(sessionId);
        messages.add(OpenAiClient.userMessage(message));

        String reply = agentLoop.run(messages);

        // 持久化本轮对话 / Persist this conversation turn
        sessionStore.save(sessionId, messages);

        return new String[]{sessionId, reply};
    }

    /**
     * 清除会话历史（对应 DELETE /api/sessions/{id}）。
     * Clear session history (DELETE /api/sessions/{id}).
     */
    public void clearSession(String sessionId) {
        sessionStore.delete(sessionId);
    }
}
