package ai.claude.code.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 工具类公共工具方法。
 * Shared utilities for tool classes.
 */
public final class ToolUtils {

    private ToolUtils() {}

    /**
     * 构建空参数 schema（无输入参数的工具使用）。
     * Build an empty parameter schema for tools that take no input.
     */
    public static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    /**
     * 截断字符串至指定最大长度，超出时追加 "..." 。
     * Truncate string to max length, appending "..." if exceeded.
     */
    public static String brief(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 构建工具定义 —— 告诉 LLM 有哪些工具可用。
     *
     * 工具定义的结构遵循 OpenAI / Anthropic 函数调用规范：
     * {
     *   "name": "bash",
     *   "description": "Run a command...",
     *   "input_schema": { ... }
     * }
     */
    public static JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("input_schema", inputSchema);
        return tool;
    }

    /**
     * 快速构建 JSON Schema —— 用变长参数简化工具参数定义。
     *
     * 参数以三元组的形式传入：(参数名, 类型, 是否必需)
     * 例如：schema("command", "string", "true", "timeout", "integer", "false")
     */
    public static JsonObject schema(String... nameTypePairs) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (int i = 0; i < nameTypePairs.length; i += 3) {
            String name = nameTypePairs[i];
            String type = nameTypePairs[i + 1];
            String req = nameTypePairs[i + 2];
            JsonObject prop = new JsonObject();
            prop.addProperty("type", type);
            props.add(name, prop);
            if ("true".equals(req)) {
                required.add(name);
            }
        }
        schema.add("properties", props);
        if (required.size() > 0) {
            schema.add("required", required);
        }
        return schema;
    }
}
