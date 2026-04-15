package ai.claude.code.tool;

import ai.claude.code.capability.TaskStore;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.tool.ToolUtils;
import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务管理工具提供者 (Task Management Tool Provider)
 *
 * 暴露 4 个任务管理工具给 LLM，用于创建、列出、查询和更新任务。
 * Exposes 4 task management tools to the LLM for creating, listing,
 * retrieving, and updating tasks.
 *
 * ===== 工具列表 =====
 * - task_create : 创建新任务，支持标题、描述、阻塞依赖 / Create a new task
 * - task_list   : 列出所有任务的摘要信息 / List all task summaries
 * - task_get    : 根据 ID 获取任务完整详情 / Get full task details by ID
 * - task_update : 更新任务的指定字段 / Update a specific field of a task
 *
 * ===== 设计说明 =====
 * 工具定义与 ContextCompactor 工具不同，此处需要手动构建 task_create 的 schema，
 * 因为它包含可选参数（description、blocked_by），OpenAiClient.schema() 辅助方法
 * 不支持描述字段，手动构建更灵活。
 *
 * ===== Design Notes =====
 * Unlike CompactTool, task_create's schema is built manually to support optional
 * parameters (description, blocked_by) with descriptions, which the schema() helper
 * does not cover.
 */
public class TaskTools implements ToolProvider {

    /** 任务存储实例，所有工具共享同一个存储对象 / Shared TaskStore instance for all tools */
    private final TaskStore ts;

    /**
     * 构造任务工具提供者。
     * Constructor for TaskTools.
     *
     * @param ts 任务存储实例 / TaskStore instance
     */
    public TaskTools(TaskStore ts) {
        this.ts = ts;
    }

    /**
     * 返回工具名称到处理器的映射。
     * Returns tool name to handler mapping.
     */
    @Override
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new HashMap<String, ToolHandler>();

        // task_create: 创建新任务 / Create a new task
        map.put("task_create", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String subject = input.get("subject").getAsString();
                String desc = input.has("description")
                        ? input.get("description").getAsString()
                        : "";
                List<String> blockedBy = null;
                if (input.has("blocked_by")) {
                    JsonArray arr = input.getAsJsonArray("blocked_by");
                    blockedBy = new ArrayList<String>();
                    for (JsonElement el : arr) {
                        blockedBy.add(el.getAsString());
                    }
                }
                return ts.create(subject, desc, blockedBy);
            }
        });

        // task_list: 列出所有任务 / List all tasks
        map.put("task_list", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return ts.list();
            }
        });

        // task_get: 根据 ID 获取任务详情 / Get task details by ID
        map.put("task_get", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return ts.get(input.get("id").getAsString());
            }
        });

        // task_update: 更新任务指定字段 / Update a specific field of a task
        map.put("task_update", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String id = input.get("id").getAsString();
                String field = input.get("field").getAsString();
                String value = input.get("value").getAsString();
                return ts.update(id, field, value);
            }
        });

        return map;
    }

    /**
     * 返回 OpenAI 格式的工具定义列表。
     * Returns the OpenAI-format tool definition list.
     */
    @Override
    public List<JsonObject> definitions() {
        return Arrays.asList(
                buildTaskCreateDef(),
                OpenAiClient.toolDef(
                        "task_list",
                        "List teammate tasks created via task_create. NOT for checking your own todo items — use the todo tool for that.",
                        ToolUtils.emptySchema()
                ),
                OpenAiClient.toolDef(
                        "task_get",
                        "Get full details of a task by its ID.",
                        OpenAiClient.schema("id", "string", "true")
                ),
                OpenAiClient.toolDef(
                        "task_update",
                        "Update a specific field of a task. "
                                + "Supported fields: status, subject, description, owner, "
                                + "add_blocked_by, add_blocks.",
                        OpenAiClient.schema(
                                "id",    "string", "true",
                                "field", "string", "true",
                                "value", "string", "true"
                        )
                )
        );
    }

    /**
     * 手动构建 task_create 的工具定义。
     * Manually build the tool definition for task_create.
     *
     * 需要手动构建是因为 blocked_by 是数组类型，且各参数需要描述说明，
     * OpenAiClient.schema() 辅助方法仅支持简单的 string/boolean 类型字段。
     *
     * Manual construction is needed because blocked_by is an array type and
     * parameters need description text, which OpenAiClient.schema() does not support.
     *
     * @return task_create 工具定义 JsonObject / tool definition JsonObject
     */
    private static JsonObject buildTaskCreateDef() {
        // 构建 subject 属性 / Build subject property
        JsonObject subjectProp = new JsonObject();
        subjectProp.addProperty("type", "string");
        subjectProp.addProperty("description", "Short title of the task");

        // 构建 description 属性（可选）/ Build description property (optional)
        JsonObject descProp = new JsonObject();
        descProp.addProperty("type", "string");
        descProp.addProperty("description", "Detailed description of the task");

        // 构建 blocked_by 属性（可选，数组类型）/ Build blocked_by property (optional, array)
        JsonObject blockedByItems = new JsonObject();
        blockedByItems.addProperty("type", "string");

        JsonObject blockedByProp = new JsonObject();
        blockedByProp.addProperty("type", "array");
        blockedByProp.add("items", blockedByItems);
        blockedByProp.addProperty("description",
                "List of task IDs that must be completed before this task can start");

        // 组装 properties / Assemble properties
        JsonObject properties = new JsonObject();
        properties.add("subject", subjectProp);
        properties.add("description", descProp);
        properties.add("blocked_by", blockedByProp);

        // subject 是唯一必填字段 / subject is the only required field
        JsonArray required = new JsonArray();
        required.add("subject");

        // 组装 schema / Assemble schema
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);

        return OpenAiClient.toolDef(
                "task_create",
                "Create a task for a teammate to work on. Use this ONLY when coordinating with spawned teammates, not for your own personal to-do items.",
                schema
        );
    }

}
