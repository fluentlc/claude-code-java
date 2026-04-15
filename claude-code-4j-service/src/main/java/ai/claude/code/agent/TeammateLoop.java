package ai.claude.code.agent;

import ai.claude.code.capability.MessageBus;
import ai.claude.code.capability.TaskPoller;
import ai.claude.code.capability.TaskStore;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.core.BaseTools;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ToolHandler;
import ai.claude.code.tool.ToolUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Teammate Agent 循环 — 在独立线程中运行的 LLM 对话循环。
 * Teammate Agent Loop — an LLM conversation loop running in an independent thread.
 *
 * 【生命周期 / Lifecycle】
 *   WORKING (LLM 推理) → end_turn → IDLE 轮询 → 收到消息/任务 → WORKING
 *                                              → 60s 无工作 → DONE (线程退出)
 *
 * 【与 Lead 的通信 / Communication with Lead】
 *   - 接收: MessageBus.readInbox(name)  — Lead 发来的消息注入对话
 *   - 发送: msg_send 工具 → MessageBus.send(name, to, content)
 *
 * 【工具集 / Tool Set (8 tools)】
 *   bash, read_file, write_file, edit_file   — 文件与 Shell 操作
 *   msg_send, msg_read                       — 与其他 Agent 通信
 *   idle                                     — 主动进入空闲状态
 *   claim_task                               — 认领未分配任务
 *
 * 【防止身份遗忘 / Identity Re-injection】
 *   当对话消息数 < IDENTITY_REINJECT_THRESHOLD 时，在消息列表开头插入身份信息，
 *   防止 LLM 在上下文较短时忘记自己的角色。
 *   When message count < threshold, prepend identity messages to prevent role forgetting.
 */
public class TeammateLoop implements Runnable {

    /** 消息数低于此值时触发身份再注入 / Trigger identity re-injection below this message count */
    private static final int IDENTITY_REINJECT_THRESHOLD = 6;

    /** 空闲轮询最大次数（每次 5s，共 60s）/ Max idle polls (5s each, 60s total) */
    private static final int MAX_IDLE_POLLS = 12;

    /** 空闲轮询间隔（毫秒）/ Idle poll interval (ms) */
    private static final long IDLE_POLL_INTERVAL_MS = 5_000;

    /** 单次对话最大工具调用轮次 / Max tool-call rounds per working session */
    private static final int MAX_TURNS = 20;

    private static final Gson GSON = new Gson();

    // ===== 身份信息 / Identity =====
    private final String name;
    private final String role;
    private final String instructions;

    // ===== 依赖 / Dependencies =====
    private final OpenAiClient client;
    private final MessageBus messageBus;
    private final TaskStore taskStore;
    private final TeammateRunner runner;
    private final BaseTools baseTools;

    // ===== LLM 参数 / LLM Params =====
    private final String systemPrompt;
    private final JsonArray toolDefs;
    private final Map<String, ToolHandler> dispatch;

    /** 控制主循环退出的标志 / Flag to exit the main loop */
    private volatile boolean running = true;

    /**
     * 构造 TeammateLoop。
     * Constructor for TeammateLoop.
     *
     * @param name         Teammate 名称（收件箱 ID）/ name (inbox ID)
     * @param role         角色描述 / role description
     * @param instructions 初始任务指令 / initial instructions
     * @param client       LLM 客户端 / LLM client
     * @param workDir      工作目录 / working directory
     * @param messageBus   消息总线 / message bus
     * @param taskStore    任务存储 / task store
     * @param runner       生命周期管理器（用于回调状态变更）/ runner for status callbacks
     */
    public TeammateLoop(String name, String role, String instructions,
                        OpenAiClient client, String workDir,
                        MessageBus messageBus, TaskStore taskStore,
                        TeammateRunner runner) {
        this.name         = name;
        this.role         = role;
        this.instructions = instructions;
        this.client       = client;
        this.messageBus   = messageBus;
        this.taskStore    = taskStore;
        this.runner       = runner;
        this.baseTools    = new BaseTools(workDir);

        this.systemPrompt = buildSystemPrompt();
        this.dispatch     = buildDispatch();
        this.toolDefs     = buildToolDefs();
    }

    // ===================================================================
    // 主循环 / Main Loop
    // ===================================================================

    @Override
    public void run() {
        System.out.println("[Teammate " + name + "] Started. Role: " + role);

        JsonArray messages = new JsonArray();
        // 注入初始任务指令 / Inject initial instructions
        messages.add(OpenAiClient.userMessage(instructions));

        while (running) {
            // 身份再注入 / Identity re-injection
            messages = reinjectIdentityIfNeeded(messages);

            boolean continueWorking = runWorkingSession(messages);

            if (!continueWorking || !running) break;

            // 进入空闲轮询 / Enter idle polling
            runner.updateStatus(name, "idle");
            boolean foundWork = idlePoll(messages);

            if (!foundWork) {
                System.out.println("[Teammate " + name + "] No work for 60s, shutting down.");
                break;
            }
            runner.updateStatus(name, "working");
        }

        runner.updateStatus(name, "done");
        System.out.println("[Teammate " + name + "] Stopped.");
    }

    /**
     * 运行一次 Working 会话（LLM 循环，直到 end_turn 或 MAX_TURNS）。
     * Run one working session (LLM loop until end_turn or MAX_TURNS).
     *
     * @param messages 对话历史（in-place 追加）/ conversation history (appended in-place)
     * @return true 表示正常结束（end_turn），false 表示应退出 / true=end_turn, false=exit
     */
    private boolean runWorkingSession(JsonArray messages) {
        for (int i = 0; i < MAX_TURNS && running; i++) {
            System.out.println("[Teammate " + name + "] Turn " + (i + 1));
            JsonObject response     = client.createMessage(systemPrompt, messages, toolDefs, 4000);
            JsonObject assistantMsg = OpenAiClient.getAssistantMessage(response);
            String stopReason       = OpenAiClient.getStopReason(response);

            messages.add(OpenAiClient.assistantMessage(assistantMsg));

            String text = OpenAiClient.extractText(assistantMsg);
            if (!text.isEmpty()) {
                System.out.println("[Teammate " + name + "] " + ToolUtils.brief(text, 200));
                runner.notifyTeamText(name, text);
            }

            if (!"tool_calls".equals(stopReason)) {
                // end_turn：进入空闲轮询
                return true;
            }

            // 执行工具调用 / Execute tool calls
            JsonArray toolCalls = OpenAiClient.getToolCalls(assistantMsg);
            for (JsonElement el : toolCalls) {
                JsonObject tc    = el.getAsJsonObject();
                String toolId    = tc.get("id").getAsString();
                JsonObject fn    = tc.getAsJsonObject("function");
                String toolName  = fn.get("name").getAsString();
                JsonObject input = JsonParser.parseString(
                        fn.get("arguments").getAsString()).getAsJsonObject();

                System.out.println("[Teammate " + name + "] Tool: " + toolName
                        + " <- " + ToolUtils.brief(input.toString(), 80));
                runner.notifyTeamToolStart(name, toolId, toolName, input);

                ToolHandler handler = dispatch.get(toolName);
                boolean success = true;
                String result;
                try {
                    result = handler != null
                            ? handler.execute(input)
                            : "Error: unknown tool '" + toolName + "'";
                    if (handler == null) success = false;
                } catch (Exception ex) {
                    result  = "Error: " + ex.getMessage();
                    success = false;
                }

                System.out.println("[Teammate " + name + "] Tool: " + toolName
                        + " -> " + ToolUtils.brief(result, 120));
                runner.notifyTeamToolEnd(name, toolId, success, result);

                messages.add(OpenAiClient.toolResultMessage(toolId, result));

                // idle 工具触发空闲轮询（让 LLM 决定进入 idle 的时机）
                // idle tool triggers polling (LLM decides when to go idle)
                if ("idle".equals(toolName) && running) {
                    return true; // 结束当前 working session，进入 idlePoll
                }
            }
        }
        return running; // MAX_TURNS 用完但仍在运行，继续 idlePoll
    }

    /**
     * 空闲轮询：每 5s 检查收件箱和未认领任务，最多 60s。
     * Idle polling: check inbox and unclaimed tasks every 5s for up to 60s.
     *
     * @param messages 对话历史（将新工作注入其中）/ conversation history
     * @return true 表示找到新工作，false 表示超时无工作 / true=found work, false=timeout
     */
    private boolean idlePoll(JsonArray messages) {
        for (int poll = 0; poll < MAX_IDLE_POLLS && running; poll++) {
            try {
                Thread.sleep(IDLE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            // 检查收件箱 / Check inbox
            List<JsonObject> inbox = messageBus.readInbox(name);
            for (JsonObject msg : inbox) {
                // 检测 shutdown_request（从 content 字段解析）
                // Detect shutdown_request (parsed from content field)
                String content = msg.has("content") ? msg.get("content").getAsString() : "";
                if (isShutdownRequest(content)) {
                    handleShutdown(content);
                    return false;
                }

                // 普通消息注入对话 / Inject normal messages into conversation
                String from = msg.has("from") ? msg.get("from").getAsString() : "unknown";
                messages.add(OpenAiClient.userMessage(
                        "<message from=\"" + from + "\">" + content + "</message>"));
                System.out.println("[Teammate " + name + "] Inbox message from " + from);
                return true; // 有新消息，回到 working
            }

            // 检查未认领任务 / Check unclaimed tasks
            Optional<JsonObject> task = TaskPoller.pollForTask(taskStore, name);
            if (task.isPresent()) {
                JsonObject t      = task.get();
                String taskId     = t.get("id").getAsString();
                String taskSubj   = t.has("subject") ? t.get("subject").getAsString() : "";
                String taskDesc   = t.has("description") ? t.get("description").getAsString() : "";
                messages.add(OpenAiClient.userMessage(
                        "Auto-claimed task #" + taskId + ": " + taskSubj
                        + (taskDesc.isEmpty() ? "" : "\nDescription: " + taskDesc)));
                System.out.println("[Teammate " + name + "] Auto-claimed task #" + taskId);
                return true; // 有新任务，回到 working
            }
        }
        return false; // 超时，无工作
    }

    // ===================================================================
    // 身份再注入 / Identity Re-injection
    // ===================================================================

    /**
     * 当消息数 < 阈值时，在列表开头插入身份信息，防止 LLM 遗忘角色。
     * Prepend identity messages when count is below threshold to prevent role forgetting.
     */
    private JsonArray reinjectIdentityIfNeeded(JsonArray messages) {
        if (messages.size() >= IDENTITY_REINJECT_THRESHOLD) {
            return messages;
        }
        JsonArray newMessages = new JsonArray();
        newMessages.add(OpenAiClient.userMessage(
                "<identity>You are '" + name + "', role: " + role + "</identity>"));
        newMessages.add(OpenAiClient.assistantMessage(
                "I am " + name + " (" + role + "). Ready to work."));
        for (JsonElement el : messages) {
            newMessages.add(el);
        }
        return newMessages;
    }

    // ===================================================================
    // Shutdown 协议 / Shutdown Protocol
    // ===================================================================

    /**
     * 检测 content 是否为 shutdown_request JSON。
     * Detect if content is a shutdown_request JSON.
     */
    private boolean isShutdownRequest(String content) {
        try {
            JsonObject obj = JsonParser.parseString(content.trim()).getAsJsonObject();
            return "shutdown_request".equals(
                    obj.has("type") ? obj.get("type").getAsString() : "");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 处理 shutdown_request：回复 shutdown_response，停止循环。
     * Handle shutdown_request: reply with shutdown_response and stop loop.
     */
    private void handleShutdown(String requestContent) {
        try {
            JsonObject req       = JsonParser.parseString(requestContent.trim()).getAsJsonObject();
            String requestId     = req.has("request_id") ? req.get("request_id").getAsString() : "unknown";
            JsonObject response  = new JsonObject();
            response.addProperty("type",       "shutdown_response");
            response.addProperty("request_id", requestId);
            response.addProperty("approve",    true);
            messageBus.send(name, "lead", GSON.toJson(response));
            System.out.println("[Teammate " + name + "] Shutdown approved (request_id=" + requestId + ")");
        } catch (Exception e) {
            System.out.println("[Teammate " + name + "] Shutdown signal received.");
        }
        running = false;
    }

    // ===================================================================
    // 工具构建 / Tool Construction
    // ===================================================================

    /**
     * 构建 Teammate 的系统提示词。
     * Build the Teammate's system prompt.
     */
    private String buildSystemPrompt() {
        return "You are '" + name + "', a teammate agent in an AI agent team.\n"
                + "Role: " + role + "\n\n"
                + "## Rules\n"
                + "- Use tools to accomplish tasks. Be concise.\n"
                + "- When a task is complete, use msg_send to report results to 'lead'.\n"
                + "- When you have no immediate work, use the idle tool to enter idle state.\n"
                + "- Use claim_task to pick up pending tasks from the task board.\n"
                + "- Avoid long prose. Prefer tool calls over explanation.\n";
    }

    /**
     * 内联构建 Teammate 的工具分发表（8 个工具）。
     * Inline-build the Teammate's tool dispatch map (8 tools).
     */
    private Map<String, ToolHandler> buildDispatch() {
        Map<String, ToolHandler> map = new LinkedHashMap<String, ToolHandler>();

        // bash
        map.put("bash", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                return baseTools.runBash(input.get("command").getAsString());
            }
        });

        // read_file
        map.put("read_file", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String path  = input.get("path").getAsString();
                int    limit = input.has("limit") ? input.get("limit").getAsInt() : 0;
                return baseTools.runRead(path, limit);
            }
        });

        // write_file
        map.put("write_file", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String path    = input.get("path").getAsString();
                String content = input.get("content").getAsString();
                return baseTools.runWrite(path, content);
            }
        });

        // edit_file
        map.put("edit_file", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String path    = input.get("path").getAsString();
                String oldText = input.get("old_text").getAsString();
                String newText = input.get("new_text").getAsString();
                return baseTools.runEdit(path, oldText, newText);
            }
        });

        // msg_send: 向指定 Agent 发送消息
        map.put("msg_send", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                String to      = input.get("to").getAsString();
                String content = input.get("content").getAsString();
                messageBus.send(name, to, content);
                return "Message sent to " + to;
            }
        });

        // msg_read: 读取自己的收件箱（drain 语义）
        map.put("msg_read", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                List<JsonObject> msgs = messageBus.readInbox(name);
                if (msgs.isEmpty()) return "No messages.";
                StringBuilder sb = new StringBuilder();
                for (JsonObject msg : msgs) {
                    String from    = msg.has("from")    ? msg.get("from").getAsString()    : "unknown";
                    String content = msg.has("content") ? msg.get("content").getAsString() : "";
                    // 检测 shutdown_request
                    if (isShutdownRequest(content)) {
                        handleShutdown(content);
                        return "Shutdown request received. Stopping.";
                    }
                    sb.append("[").append(from).append("] ").append(content).append("\n");
                }
                return sb.toString().trim();
            }
        });

        // idle: 主动声明进入空闲状态，触发空闲轮询
        map.put("idle", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                runner.updateStatus(name, "idle");
                return "Entered idle state. Will poll for work.";
            }
        });

        // claim_task: 认领一个未分配的任务
        map.put("claim_task", new ToolHandler() {
            @Override public String execute(JsonObject input) {
                Optional<JsonObject> task = TaskPoller.pollForTask(taskStore, name);
                if (!task.isPresent()) return "No unclaimed tasks available.";
                JsonObject t    = task.get();
                String taskId   = t.get("id").getAsString();
                String taskSubj = t.has("subject") ? t.get("subject").getAsString() : "";
                return "Claimed task #" + taskId + ": " + taskSubj
                        + "\n" + GSON.toJson(t);
            }
        });

        return map;
    }

    /**
     * 构建 Teammate 的工具定义列表（供 LLM 调用）。
     * Build the Teammate's tool definition list (for LLM function calling).
     */
    private JsonArray buildToolDefs() {
        JsonArray defs = new JsonArray();

        defs.add(OpenAiClient.toolDef("bash",
                "Execute a shell command.",
                OpenAiClient.schema("command", "string", "true")));

        defs.add(OpenAiClient.toolDef("read_file",
                "Read the contents of a file.",
                OpenAiClient.schema("path", "string", "true")));

        defs.add(OpenAiClient.toolDef("write_file",
                "Write content to a file.",
                OpenAiClient.schema(
                        "path", "string", "true",
                        "content", "string", "true")));

        defs.add(OpenAiClient.toolDef("edit_file",
                "Edit a file by replacing old_text with new_text.",
                OpenAiClient.schema(
                        "path", "string", "true",
                        "old_text", "string", "true",
                        "new_text", "string", "true")));

        defs.add(OpenAiClient.toolDef("msg_send",
                "Send a message to another agent (e.g. 'lead').",
                OpenAiClient.schema(
                        "to", "string", "true",
                        "content", "string", "true")));

        defs.add(OpenAiClient.toolDef("msg_read",
                "Read and drain all messages from your inbox.",
                ToolUtils.emptySchema()));

        defs.add(OpenAiClient.toolDef("idle",
                "Declare yourself idle. The system will poll for new messages and tasks.",
                ToolUtils.emptySchema()));

        defs.add(OpenAiClient.toolDef("claim_task",
                "Claim an unclaimed pending task from the task board and return its details.",
                ToolUtils.emptySchema()));

        return defs;
    }
}
