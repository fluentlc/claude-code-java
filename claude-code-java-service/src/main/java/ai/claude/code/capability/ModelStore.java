package ai.claude.code.capability;

import ai.claude.code.core.OpenAiClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * 模型配置持久化存储 — 读写 .models/models.json。
 * Model config persistence — read/write .models/models.json.
 *
 * 设计遵循 SessionStore / TaskStore 的文件持久化模式：
 * - 单个 JSON 文件保存模型配置（apiKey, baseUrl, modelId）
 * - 进程重启后配置自动恢复
 * - 不保存 apiKey 到版本控制，.models/ 应加入 .gitignore
 */
public class ModelStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "models.json";

    private final String modelsDir;

    public ModelStore(String modelsDir) {
        this.modelsDir = modelsDir;
        new File(modelsDir).mkdirs();
    }

    /**
     * 加载模型配置；若文件不存在或解析失败返回空 JsonObject。
     */
    public JsonObject load() {
        Path path = Paths.get(modelsDir, FILE_NAME);
        if (!Files.exists(path)) {
            return new JsonObject();
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[ModelStore] Failed to load config: " + e.getMessage());
            return new JsonObject();
        }
    }

    /**
     * 保存模型配置。
     */
    public void save(JsonObject config) {
        try {
            Files.write(Paths.get(modelsDir, FILE_NAME),
                    GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[ModelStore] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * 返回配置是否完整（apiKey + baseUrl + modelId 均非空）。
     */
    public boolean isConfigured() {
        JsonObject cfg = load();
        return hasNonBlank(cfg, "apiKey")
                && hasNonBlank(cfg, "baseUrl")
                && hasNonBlank(cfg, "modelId");
    }

    private boolean hasNonBlank(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                && !obj.get(key).getAsString().trim().isEmpty();
    }

    /**
     * 从已保存的配置创建 OpenAiClient；未配置时抛出 IllegalArgumentException。
     */
    public OpenAiClient createClient() {
        JsonObject cfg = load();
        String apiKey = cfg.has("apiKey") && !cfg.get("apiKey").isJsonNull() ? cfg.get("apiKey").getAsString().trim() : "";
        String baseUrl = cfg.has("baseUrl") && !cfg.get("baseUrl").isJsonNull() ? cfg.get("baseUrl").getAsString().trim() : "";
        String modelId = cfg.has("modelId") && !cfg.get("modelId").isJsonNull() ? cfg.get("modelId").getAsString().trim() : "";
        if (apiKey.isEmpty() || baseUrl.isEmpty() || modelId.isEmpty()) {
            throw new IllegalArgumentException("模型未配置：请在 Playground「设置」菜单中填写 API Key、Base URL 和 Model ID");
        }
        return new OpenAiClient(apiKey, baseUrl, modelId, Collections.emptyMap(), "/v1/chat/completions");
    }
}
