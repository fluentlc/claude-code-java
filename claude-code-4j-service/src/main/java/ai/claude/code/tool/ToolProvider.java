package ai.claude.code.tool;

import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 工具提供者接口 — 每个工具模块实现此接口，自包含地声明处理器和 JSON 定义。
 * Tool provider interface — each tool module self-contains its handlers and JSON definitions.
 *
 * 扩展新工具只需：
 *   1. 新建一个实现此接口的类
 *   2. 在 Assistant 的 providers 列表里加一行
 *
 * Adding a new tool only requires:
 *   1. Create a class implementing this interface
 *   2. Add one line to Assistant's providers list
 */
public interface ToolProvider {

    /**
     * 返回工具名称 → 处理器的映射，用于 agentLoop 的工具分派。
     * Returns tool name → handler mapping for agentLoop dispatch.
     */
    Map<String, ToolHandler> handlers();

    /**
     * 返回 OpenAI 格式的工具定义列表，发送给模型。
     * Returns OpenAI-format tool definitions list sent to the model.
     */
    List<JsonObject> definitions();
}
