package ai.claude.code.tool;

import ai.claude.code.core.BaseTools;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件系统工具提供者 — 封装 bash、read_file、write_file、edit_file 四个基础工具。
 * File system tool provider — wraps the four base tools: bash, read_file, write_file, edit_file.
 *
 * 将 BaseTools 的工具实现适配成 ToolProvider 接口，供 Assistant 统一注册和分派。
 * Adapts BaseTools implementations to the ToolProvider interface for unified registration
 * and dispatch in Assistant.
 *
 * 每个工具的 handler 从 JsonObject input 中取出参数，委托给 BaseTools 对应方法执行，
 * 返回字符串结果作为 tool_result 传回给模型。
 * Each handler extracts parameters from the JsonObject input, delegates to the
 * corresponding BaseTools method, and returns a String result as tool_result to the model.
 */
public class FileTools implements ToolProvider {

    /** BaseTools 实例，提供工具的底层实现 / BaseTools instance providing underlying implementations */
    private final BaseTools bt;

    public FileTools(BaseTools bt) {
        this.bt = bt;
    }

    /**
     * 返回工具名称到处理器的映射，供 agentLoop 分派使用。
     * Returns the tool name to handler mapping for agentLoop dispatch.
     */
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new LinkedHashMap<String, ToolHandler>();

        // bash: 执行 shell 命令 / Execute shell command
        map.put("bash", new ToolHandler() {
            public String execute(JsonObject input) {
                return bt.runBash(input.get("command").getAsString());
            }
        });

        // read_file: 读取文件，支持可选的 limit 参数 / Read file with optional limit
        map.put("read_file", new ToolHandler() {
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                int limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return bt.runRead(path, limit);
            }
        });

        // write_file: 写入文件（覆盖）/ Write (overwrite) file
        map.put("write_file", new ToolHandler() {
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                String content = input.get("content").getAsString();
                return bt.runWrite(path, content);
            }
        });

        // edit_file: 精确文本替换 / Exact text replacement
        map.put("edit_file", new ToolHandler() {
            public String execute(JsonObject input) {
                String path = input.get("path").getAsString();
                String oldText = input.get("old_text").getAsString();
                String newText = input.get("new_text").getAsString();
                return bt.runEdit(path, oldText, newText);
            }
        });

        return map;
    }

    /**
     * 返回 OpenAI 格式的工具定义列表，发送给模型。
     * Returns OpenAI-format tool definitions list sent to the model.
     */
    public List<JsonObject> definitions() {
        List<JsonObject> defs = new ArrayList<JsonObject>();

        // bash: 只需 command 参数（必填）/ Only requires command (required)
        defs.add(OpenAiClient.toolDef("bash", "Run a shell command.",
                OpenAiClient.schema("command", "string", "true")));

        // read_file: path 必填，limit 可选 / path required, limit optional
        defs.add(OpenAiClient.toolDef("read_file", "Read file contents.",
                OpenAiClient.schema("path", "string", "true", "limit", "number", "false")));

        // write_file: path 和 content 均必填 / path and content both required
        defs.add(OpenAiClient.toolDef("write_file", "Write content to file.",
                OpenAiClient.schema("path", "string", "true", "content", "string", "true")));

        // edit_file: path、old_text、new_text 均必填 / all three params required
        defs.add(OpenAiClient.toolDef("edit_file", "Replace exact text in file.",
                OpenAiClient.schema("path", "string", "true",
                        "old_text", "string", "true",
                        "new_text", "string", "true")));

        return defs;
    }
}
