package ai.claude.code.web.dto;

/**
 * Session metadata for GET /api/sessions response.
 */
public record SessionMeta(String sessionId, String preview, String updatedAt) {}
