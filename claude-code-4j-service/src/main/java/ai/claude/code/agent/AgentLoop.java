package ai.claude.code.agent;

import ai.claude.code.capability.BackgroundRunner;
import ai.claude.code.capability.ContextCompactor;
import ai.claude.code.capability.MessageBus;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.capability.TodoManager;
import ai.claude.code.core.AgentEventListener;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ToolHandler;
import ai.claude.code.tool.ToolUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * 核心 Agent 循环 — 可独立嵌入任何 Java 应用。
 * Core Agent loop — embeddable in any Java application.
 *
 * 从 Assistant.agentLoop() 提取，改为构造器注入，便于 Spring 管理和单元测试。
 * Extracted from Assistant.agentLoop(), uses constructor injection for Spring/testing.
 *
 * 用法 / Usage:
 *   AgentLoop loop = AgentAssembler.build(client, workDir);
 *   String reply = loop.run(messages); // messages 在 REPL 外层跨轮次共享
 */
public class AgentLoop {

    /** compact 工具名，与 ContextCompactor.getCompactToolDef() 保持一致 */
    private static final String COMPACT_TOOL = "compact";
    /** todo 工具名，与 TodoTool 保持一致 */
    private static final String TODO_TOOL = "todo";
    /** lead agent 收件箱 ID */
    private static final String LEAD_ID = "lead";
    /** 单次对话最大工具调用轮次 / Max tool-call rounds per conversation turn */
    private static final int MAX_TURNS = 30;

    private final OpenAiClient client;
    private final Map<String, ToolHandler> dispatch;
    private final JsonArray toolDefs;
    private final String systemPrompt;
    private final TodoManager todoManager;
    private final ContextCompactor compactor;
    private final BackgroundRunner bgRunner;
    private final MessageBus messageBus;
    private final TeammateRunner teammateRunner;

    public AgentLoop(OpenAiClient client,
                     Map<String, ToolHandler> dispatch,
                     JsonArray toolDefs,
                     String systemPrompt,
                     TodoManager todoManager,
                     ContextCompactor compactor,
                     BackgroundRunner bgRunner,
                     MessageBus messageBus,
                     TeammateRunner teammateRunner) {
        this.client          = client;
        this.dispatch        = dispatch;
        this.toolDefs        = toolDefs;
        this.systemPrompt    = systemPrompt;
        this.todoManager     = todoManager;
        this.compactor       = compactor;
        this.bgRunner        = bgRunner;
        this.messageBus      = messageBus;
        this.teammateRunner  = teammateRunner;
    }

    /**
     * 运行一次 Agent 循环，直到模型返回 end_turn 或达到最大轮次。
     * Run one Agent loop until model returns end_turn or max turns reached.
     *
     * @param messages 对话历史（调用方持有引用，循环内直接追加） / conversation history (appended in-place)
     * @return 最后一条文本回复 / last text reply from the model
     */
    public String run(JsonArray messages) {
        String lastText = "";

        for (int i = 0; i < MAX_TURNS; i++) {
            System.out.println("[Agent] 第 " + (i + 1) + " 轮...");
            JsonObject response     = client.createMessage(systemPrompt, messages, toolDefs, 8000);
            JsonObject assistantMsg = OpenAiClient.getAssistantMessage(response);
            String stopReason       = OpenAiClient.getStopReason(response);

            messages.add(OpenAiClient.assistantMessage(assistantMsg));

            String text = OpenAiClient.extractText(assistantMsg);
            if (!text.isEmpty()) {
                lastText = text;
                System.out.println("\n[Assistant] " + text);
            }

            if (!"tool_calls".equals(stopReason)) break;

            // 执行工具调用 / Execute tool calls
            boolean usedTodo    = false;
            boolean usedCompact = false;
            JsonArray toolCalls = OpenAiClient.getToolCalls(assistantMsg);

            for (JsonElement el : toolCalls) {
                JsonObject tc        = el.getAsJsonObject();
                String toolId        = tc.get("id").getAsString();
                JsonObject fn        = tc.getAsJsonObject("function");
                String toolName      = fn.get("name").getAsString();
                JsonObject toolInput = JsonParser.parseString(
                        fn.get("arguments").getAsString()).getAsJsonObject();

                System.out.println("[Tool] " + toolName + " <- " + ToolUtils.brief(toolInput.toString(), 100));
                ToolHandler handler = dispatch.get(toolName);
                String result = handler != null
                        ? handler.execute(toolInput)
                        : "Error: unknown tool '" + toolName + "'";
                System.out.println("[Tool] " + toolName + " -> " + ToolUtils.brief(result, 150));

                if (TODO_TOOL.equals(toolName))    usedTodo    = true;
                if (COMPACT_TOOL.equals(toolName)) usedCompact = true;
                JsonObject toolMsg = OpenAiClient.toolResultMessage(toolId, result);
                // Preserve full content for session replay — microCompact may compress 'content'
                // but _display_content is kept intact (stripped before API calls by stripPrivateFields)
                toolMsg.addProperty("_display_content", result);
                messages.add(toolMsg);
            }

            // 压缩第三层：模型主动调用 compact 时执行 / Layer 3: model-triggered compact
            if (usedCompact) {
                JsonArray compacted = compactor.compact(messages, systemPrompt);
                String summary = extractCompactSummary(compacted);
                markCompactSummaryInMessages(messages, summary);
                inplaceReplace(messages, compacted);
            }

            // 压缩第一层：微压缩（每轮替换旧 tool result）/ Layer 1: micro-compact old tool results
            inplaceReplace(messages, compactor.microCompact(messages));

            // 压缩第二层：消息数超阈值时自动全量压缩 / Layer 2: auto-compact on threshold
            if (compactor.shouldAutoCompact(messages))
                inplaceReplace(messages, compactor.compact(messages, systemPrompt));

            // Todo nag：若连续多轮未更新 todo 则注入提醒 / Inject reminder if todo not updated
            if (todoManager.needsReminder())
                messages.add(OpenAiClient.userMessage(
                        "<system-reminder>Please update your todos to reflect current progress.</system-reminder>"));
            if (!usedTodo) todoManager.tick();

            // 后台任务完成通知注入 / Inject background task completion notifications
            for (String n : bgRunner.drainNotifications())
                messages.add(OpenAiClient.userMessage("<system-reminder>" + n + "</system-reminder>"));

            // 收件箱消息注入（来自 worker agent）/ Inject inbox messages from worker agents
            for (JsonObject msg : messageBus.readInbox(LEAD_ID)) {
                String from    = msg.has("from")    ? msg.get("from").getAsString()    : "unknown";
                String content = msg.has("content") ? msg.get("content").getAsString() : msg.toString();
                messages.add(OpenAiClient.userMessage(
                        "<system-reminder>[Message from " + from + "] " + content + "</system-reminder>"));
            }
        }

        return lastText;
    }

    /**
     * Streaming variant of run(). Fires AgentEventListener callbacks for real-time output.
     * Checks listener.isCancelled() before each tool-execution round to support disconnect abort.
     *
     * @param messages conversation history (appended in-place)
     * @param listener event callbacks
     * @return last text reply (may be empty if run ends on a tool call)
     */
    public String run(JsonArray messages, AgentEventListener listener) {
        String lastText = "";

        try {
            for (int i = 0; i < MAX_TURNS; i++) {
                if (listener.isCancelled()) break;

                System.out.println("[Agent/SSE] 第 " + (i + 1) + " 轮...");
                JsonObject response     = client.createMessageStream(systemPrompt, messages, toolDefs, 8000, listener);
                JsonObject assistantMsg = OpenAiClient.getAssistantMessage(response);
                String stopReason       = OpenAiClient.getStopReason(response);

                messages.add(OpenAiClient.assistantMessage(assistantMsg));

                String text = OpenAiClient.extractText(assistantMsg);
                if (!text.isEmpty()) lastText = text;

                if (!"tool_calls".equals(stopReason)) break;

                if (listener.isCancelled()) break;

                boolean usedTodo    = false;
                boolean usedCompact = false;
                JsonArray toolCalls = OpenAiClient.getToolCalls(assistantMsg);

                for (JsonElement el : toolCalls) {
                    if (listener.isCancelled()) break;

                    JsonObject tc   = el.getAsJsonObject();
                    String toolId   = tc.get("id").getAsString();
                    JsonObject fn   = tc.getAsJsonObject("function");
                    String toolName = fn.get("name").getAsString();
                    JsonObject toolInput = JsonParser.parseString(
                            fn.get("arguments").getAsString()).getAsJsonObject();

                    listener.onToolStart(toolId, toolName, toolInput);
                    System.out.println("[Tool/SSE] " + toolName + " <- " + ToolUtils.brief(toolInput.toString(), 100));

                    ToolHandler handler = dispatch.get(toolName);
                    boolean success = true;
                    String result;
                    try {
                        result = handler != null
                                ? handler.execute(toolInput)
                                : "Error: unknown tool '" + toolName + "'";
                        if (handler == null) success = false;
                    } catch (Exception ex) {
                        result = "Error: " + ex.getMessage();
                        success = false;
                    }

                    System.out.println("[Tool/SSE] " + toolName + " -> " + ToolUtils.brief(result, 150));
                    listener.onToolEnd(toolId, success, result);

                    if (TODO_TOOL.equals(toolName))    usedTodo    = true;
                    if (COMPACT_TOOL.equals(toolName)) usedCompact = true;
                    JsonObject toolMsg = OpenAiClient.toolResultMessage(toolId, result);
                    toolMsg.addProperty("_display_content", result);
                    messages.add(toolMsg);
                }

                if (usedCompact) {
                    JsonArray compacted = compactor.compact(messages, systemPrompt);
                    String summary = extractCompactSummary(compacted);
                    markCompactSummaryInMessages(messages, summary);
                    listener.onCompactDone(summary);
                    inplaceReplace(messages, compacted);
                }
                inplaceReplace(messages, compactor.microCompact(messages));
                if (compactor.shouldAutoCompact(messages))
                    inplaceReplace(messages, compactor.compact(messages, systemPrompt));

                if (todoManager.needsReminder())
                    messages.add(OpenAiClient.userMessage(
                            "<system-reminder>Please update your todos to reflect current progress.</system-reminder>"));
                if (!usedTodo) todoManager.tick();

                for (String n : bgRunner.drainNotifications())
                    messages.add(OpenAiClient.userMessage("<system-reminder>" + n + "</system-reminder>"));

                for (JsonObject msg : messageBus.readInbox(LEAD_ID)) {
                    String from    = msg.has("from")    ? msg.get("from").getAsString()    : "unknown";
                    String content = msg.has("content") ? msg.get("content").getAsString() : msg.toString();
                    messages.add(OpenAiClient.userMessage(
                            "<system-reminder>[Message from " + from + "] " + content + "</system-reminder>"));
                }
            }
        } finally {
            listener.onDone();
        }
        return lastText;
    }

    /**
     * 从压缩后的消息数组中提取摘要文本（第一条非 system-reminder 的 content）。
     * Extract compact summary from the first meaningful message in the compacted array.
     */
    private String extractCompactSummary(JsonArray compacted) {
        for (JsonElement el : compacted) {
            if (!el.isJsonObject()) continue;
            JsonObject msg = el.getAsJsonObject();
            if (!msg.has("content") || msg.get("content").isJsonNull()) continue;
            if (!msg.get("content").isJsonPrimitive()) continue;
            String c = msg.get("content").getAsString().trim();
            if (!c.isEmpty() && !c.startsWith("<system-reminder>")) return c;
        }
        return "Context compressed.";
    }

    /**
     * 在 compact 执行前，给 messages 中的 compact tool result 消息打上 _compact_summary 标记。
     * 该标记随 session 持久化，供历史恢复时渲染 Compact Card。
     * Mark the compact tool result message with _compact_summary before replacing messages.
     */
    private void markCompactSummaryInMessages(JsonArray messages, String summary) {
        for (JsonElement el : messages) {
            if (!el.isJsonObject()) continue;
            JsonObject msg = el.getAsJsonObject();
            if (!"tool".equals(msg.has("role") ? msg.get("role").getAsString() : "")) continue;
            if (!msg.has("_display_content")) continue;
            String dc = msg.get("_display_content").getAsString();
            if (dc.startsWith("Compact triggered")) {
                msg.addProperty("_compact_summary", summary);
            }
        }
    }

    /** in-place 用 src 替换 dst 的内容（保持 dst 引用不变）/ Replace dst content with src in-place */
    private void inplaceReplace(JsonArray dst, JsonArray src) {
        if (dst == src) return; // same reference — compactor returned unchanged, nothing to do
        while (dst.size() > 0) dst.remove(0);
        for (JsonElement el : src) dst.add(el);
    }

    /** 返回系统提示词，供 CliRunner 渲染 /compact 时使用 */
    public String getSystemPrompt() { return systemPrompt; }

    /** 返回 ContextCompactor，供 CliRunner 处理 /compact 命令 */
    public ContextCompactor getCompactor() { return compactor; }

    /** 关闭后台线程池和所有 Teammate / Shutdown background thread pool and all teammates */
    public void shutdown() {
        bgRunner.shutdown();
        if (teammateRunner != null) teammateRunner.shutdownAll();
    }
}
