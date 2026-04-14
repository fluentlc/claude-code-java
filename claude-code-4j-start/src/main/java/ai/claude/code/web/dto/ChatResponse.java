package ai.claude.code.web.dto;

/**
 * POST /api/chat 响应体。
 * Response body for POST /api/chat.
 */
public record ChatResponse(String sessionId, String reply) {}
