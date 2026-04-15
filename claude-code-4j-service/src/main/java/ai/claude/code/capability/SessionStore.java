package ai.claude.code.capability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * REST 会话持久化存储 — 每个会话对应 .sessions/{sessionId}.json。
 * REST session persistence — each session maps to .sessions/{sessionId}.json.
 *
 * 设计参考 TaskStore 的文件持久化模式：
 * Design follows TaskStore's file-persistence pattern:
 *   - 每个 sessionId 一个独立 JSON 文件（保存消息历史数组）
 *   - Each sessionId has its own JSON file (stores message history array)
 *   - 进程重启后会话历史自动恢复
 *   - Session history survives process restarts
 */
public class SessionStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 会话文件存储目录 / Session file storage directory */
    private final String sessionsDir;

    /**
     * @param sessionsDir 存储目录路径，如 workDir + "/.sessions" / storage directory path
     */
    public SessionStore(String sessionsDir) {
        this.sessionsDir = sessionsDir;
        new File(sessionsDir).mkdirs();
    }

    /**
     * 加载会话的消息历史；若文件不存在返回空数组。
     * Load session message history; returns empty array if file doesn't exist.
     *
     * @param sessionId 会话 ID / session ID
     * @return 消息历史 JsonArray / message history JsonArray
     */
    public JsonArray load(String sessionId) {
        Path path = sessionPath(sessionId);
        if (!Files.exists(path)) return new JsonArray();
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return JsonParser.parseString(content).getAsJsonArray();
        } catch (Exception e) {
            System.err.println("[SessionStore] Failed to load " + sessionId + ": " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * 持久化会话的消息历史。
     * Persist session message history.
     *
     * @param sessionId 会话 ID / session ID
     * @param messages  消息历史 / message history
     */
    public void save(String sessionId, JsonArray messages) {
        try {
            Files.write(sessionPath(sessionId),
                    GSON.toJson(messages).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[SessionStore] Failed to save " + sessionId + ": " + e.getMessage());
        }
    }

    /**
     * 删除会话文件（用于 DELETE /api/sessions/{id}）。
     * Delete session file (used by DELETE /api/sessions/{id}).
     *
     * @param sessionId 会话 ID / session ID
     */
    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(sessionPath(sessionId));
        } catch (IOException e) {
            System.err.println("[SessionStore] Failed to delete " + sessionId + ": " + e.getMessage());
        }
    }

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
                String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
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

    private Path sessionPath(String sessionId) {
        // 防目录遍历：只取文件名部分 / Prevent path traversal: use basename only
        String safe = new File(sessionId).getName();
        return Paths.get(sessionsDir, safe + ".json");
    }
}
