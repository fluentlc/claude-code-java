package ai.claude.code.tool;

import ai.claude.code.capability.MessageBus;
import ai.claude.code.tool.ToolUtils;
import ai.claude.code.capability.TaskPoller;
import ai.claude.code.capability.TaskStore;
import ai.claude.code.capability.TeamProtocol;
import ai.claude.code.capability.TeammateRunner;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 团队协作工具集 — 提供多 Agent 消息通信、任务认领、Teammate 生命周期管理能力。
 * Team collaboration tools — multi-agent messaging, task polling, teammate lifecycle management.
 *
 * 包含 6 个工具 / Contains 6 tools:
 *   - msg_send:        向指定 Agent 发送消息 / Send a message to a specific agent
 *   - msg_read:        读取收件箱消息（drain 语义）/ Read inbox messages (drain semantics)
 *   - task_poll:       轮询并认领一个未分配任务 / Poll and claim an unclaimed task
 *   - team_request_id: 生成协议关联用的唯一 request_id / Generate a unique protocol request_id
 *   - spawn_teammate:  启动一个新的 Teammate Agent / Spawn a new Teammate Agent
 *   - list_teammates:  列出当前所有 Teammate 及状态 / List all teammates and their status
 */
public class TeamTools implements ToolProvider {

    private final MessageBus messageBus;
    private final TaskStore taskStore;
    private final TeammateRunner teammateRunner;
    /** 当前 Lead Agent 的 ID，作为消息发送者或默认收件人 */
    /** Current Lead Agent ID, used as sender or default recipient */
    private final String leadId;

    /**
     * 构造团队协作工具集。
     * Constructor for team collaboration tools.
     *
     * @param messageBus      消息总线实例 / MessageBus instance
     * @param taskStore       任务存储实例 / TaskStore instance
     * @param leadId          当前 Lead Agent 的名称 / Lead agent's name
     * @param teammateRunner  Teammate 生命周期管理器 / Teammate lifecycle manager
     */
    public TeamTools(MessageBus messageBus, TaskStore taskStore, String leadId,
                     TeammateRunner teammateRunner) {
        this.messageBus      = messageBus;
        this.taskStore       = taskStore;
        this.leadId          = leadId;
        this.teammateRunner  = teammateRunner;
    }

    @Override
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new LinkedHashMap<String, ToolHandler>();

        // msg_send: 向指定 Agent 发送一条消息
        // msg_send: send a message to a specified agent
        map.put("msg_send", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String to = input.get("to").getAsString();
                String content = input.get("content").getAsString();
                messageBus.send(leadId, to, content);
                return "Message sent to " + to;
            }
        });

        // msg_read: 读取指定 Agent（默认当前 lead）的收件箱，drain 语义
        // msg_read: read the inbox of the specified agent (default: current lead), drain semantics
        map.put("msg_read", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String agentId = input.has("agent_id")
                        ? input.get("agent_id").getAsString()
                        : leadId;
                List<JsonObject> messages = messageBus.readInbox(agentId);
                if (messages.isEmpty()) {
                    return "No messages.";
                }
                StringBuilder sb = new StringBuilder();
                for (JsonObject msg : messages) {
                    String from = msg.has("from") ? msg.get("from").getAsString() : "unknown";
                    String content = msg.has("content") ? msg.get("content").getAsString() : "";
                    sb.append("[").append(from).append("] ").append(content).append("\n");
                }
                return sb.toString().trim();
            }
        });

        // task_poll: 轮询未认领任务并认领第一个匹配项
        // task_poll: poll for an unclaimed task and claim the first match
        map.put("task_poll", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String agentId = input.has("agent_id")
                        ? input.get("agent_id").getAsString()
                        : leadId;
                Optional<JsonObject> task = TaskPoller.pollForTask(taskStore, agentId);
                if (task.isPresent()) {
                    return task.get().toString();
                }
                return "No unclaimed tasks.";
            }
        });

        // team_request_id: 生成一个唯一的协议关联 request_id
        // team_request_id: generate a unique protocol correlation request_id
        map.put("team_request_id", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return TeamProtocol.generateRequestId();
            }
        });

        // spawn_teammate: 启动一个新的 Teammate Agent 线程
        // spawn_teammate: start a new Teammate Agent thread
        map.put("spawn_teammate", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String name         = input.get("name").getAsString();
                String role         = input.get("role").getAsString();
                String instructions = input.has("instructions")
                        ? input.get("instructions").getAsString()
                        : "Help the team. Claim tasks or wait for messages from lead.";
                return teammateRunner.spawn(name, role, instructions);
            }
        });

        // list_teammates: 列出所有已 spawn 的 Teammate 及其当前状态
        // list_teammates: list all spawned teammates and their current status
        map.put("list_teammates", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return teammateRunner.list();
            }
        });

        return map;
    }

    @Override
    public List<JsonObject> definitions() {
        List<JsonObject> defs = new ArrayList<JsonObject>();

        // msg_send: 需要 to 和 content 两个必填参数
        // msg_send: requires to and content (both required)
        defs.add(OpenAiClient.toolDef(
                "msg_send",
                "Send a message to another agent via the message bus.",
                OpenAiClient.schema(
                        "to", "string", "true",
                        "content", "string", "true"
                )
        ));

        // msg_read: agent_id 可选，不填则读取当前 lead 的收件箱
        // msg_read: agent_id is optional, defaults to current lead's inbox
        defs.add(OpenAiClient.toolDef(
                "msg_read",
                "Read and drain all messages from an agent's inbox. " +
                "Defaults to the current lead's inbox if agent_id is not specified.",
                OpenAiClient.schema(
                        "agent_id", "string", "false"
                )
        ));

        // task_poll: agent_id 可选，不填则用 leadId 认领
        // task_poll: agent_id is optional, defaults to leadId for claiming
        defs.add(OpenAiClient.toolDef(
                "task_poll",
                "Poll for and claim an unclaimed pending task from the task store. " +
                "Defaults to the current lead agent if agent_id is not specified.",
                OpenAiClient.schema(
                        "agent_id", "string", "false"
                )
        ));

        // team_request_id: 无参数，生成协议唯一 ID
        // team_request_id: no parameters, generates a unique protocol ID
        defs.add(OpenAiClient.toolDef(
                "team_request_id",
                "Generate a unique request_id for protocol correlation (shutdown/approval).",
                ToolUtils.emptySchema()
        ));

        // spawn_teammate: 需要 name 和 role，instructions 可选
        // spawn_teammate: requires name and role, instructions is optional
        defs.add(OpenAiClient.toolDef(
                "spawn_teammate",
                "Spawn a new Teammate Agent that runs its own LLM loop in a background thread. " +
                "The teammate communicates via the message bus and can claim tasks autonomously.",
                OpenAiClient.schema(
                        "name",         "string", "true",
                        "role",         "string", "true",
                        "instructions", "string", "false"
                )
        ));

        // list_teammates: 无参数，返回所有 Teammate 状态
        // list_teammates: no parameters, returns all teammate statuses
        defs.add(OpenAiClient.toolDef(
                "list_teammates",
                "List all spawned teammates and their current status (working/idle/done).",
                ToolUtils.emptySchema()
        ));

        return defs;
    }

}
