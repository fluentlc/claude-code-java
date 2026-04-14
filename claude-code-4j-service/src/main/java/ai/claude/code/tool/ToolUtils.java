package ai.claude.code.tool;

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
     * 截断字符串至指定最大长度，超出时追加 "..."。
     * Truncate string to max length, appending "..." if exceeded.
     */
    public static String brief(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
