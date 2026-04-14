package ai.claude.code.tool;

import ai.claude.code.capability.ContextCompactor;
import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩工具提供者 (Context Compact Tool Provider)
 *
 * 暴露 compact 工具给 LLM，让模型在觉得上下文过长时可以主动触发压缩。
 * Exposes the compact tool to the LLM, allowing the model to trigger context
 * compression when it considers the context too long.
 *
 * ===== 工具实现说明 =====
 * 这里的 handler 只是一个占位实现，返回一条固定提示信息。
 * 实际的压缩操作由 agentLoop 在下一轮循环开始时检测 usedCompact 标志来触发。
 *
 * ===== Implementation Note =====
 * The handler here is a placeholder. Actual compaction is performed by agentLoop
 * on the next cycle when it detects the usedCompact flag.
 *
 * 工具名称从 ContextCompactor.getCompactToolDef() 动态获取，不写死，
 * 保证工具定义与处理器注册的名称始终一致。
 * Tool name is retrieved dynamically from ContextCompactor.getCompactToolDef()
 * to guarantee the definition and handler key are always in sync.
 */
public class CompactTool implements ToolProvider {

    /**
     * 工具名称，从 compact 工具定义中动态读取。
     * Tool name, read dynamically from the compact tool definition.
     */
    private static final String TOOL_NAME = ContextCompactor.getCompactToolDef()
            .getAsJsonObject("function")
            .get("name")
            .getAsString();

    /**
     * 返回工具名称到处理器的映射。
     * Returns tool name to handler mapping.
     *
     * compact 处理器只返回一条提示信息，告知调用方压缩将在下一轮循环执行。
     * The compact handler just returns a notice; actual compression runs on the next loop cycle.
     */
    @Override
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new HashMap<String, ToolHandler>();
        map.put(TOOL_NAME, new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return "Compact triggered. Context will be compacted on next cycle.";
            }
        });
        return map;
    }

    /**
     * 返回 OpenAI 格式的工具定义列表。
     * Returns the OpenAI-format tool definition list.
     *
     * 直接调用 ContextCompactor.getCompactToolDef() 获取定义，保持单一数据来源。
     * Delegates to ContextCompactor.getCompactToolDef() to keep a single source of truth.
     */
    @Override
    public List<JsonObject> definitions() {
        return Arrays.asList(ContextCompactor.getCompactToolDef());
    }
}
