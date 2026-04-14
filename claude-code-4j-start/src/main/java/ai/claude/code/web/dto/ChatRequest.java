package ai.claude.code.web.dto;

/**
 * POST /api/chat 请求体。
 * Request body for POST /api/chat.
 *
 * sessionId 可选，首次对话省略后自动生成。
 * sessionId is optional; omit to auto-generate on first call.
 */
public record ChatRequest(String sessionId, String message) {}
