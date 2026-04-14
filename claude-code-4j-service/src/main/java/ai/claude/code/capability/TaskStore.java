package ai.claude.code.capability;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 任务持久化存储 (Task Persistent Store)
 *
 * 核心洞察 / Key Insight:
 *   "State that survives compression — because it's outside the conversation."
 *   "状态存活于压缩之外——因为它在对话之外。"
 *
 * 任务存储在 .tasks/ 目录下，每个任务一个 JSON 文件（task_N.json）。
 * Tasks are stored in .tasks/ directory, one JSON file per task (task_N.json).
 *
 * 任务 JSON 结构示例 / Task JSON structure example:
 * {
 *   "id": 1,
 *   "subject": "Implement login API",
 *   "description": "Create REST ...",
 *   "status": "in_progress",
 *   "blockedBy": [2],
 *   "blocks": [3],
 *   "owner": "agent",
 *   "createdAt": "2026-03-19T10:00:00",
 *   "updatedAt": "2026-03-19T10:30:00"
 * }
 *
 * 依赖图 / Dependency graph:
 *   blockedBy 和 blocks 构成双向依赖关系。当任务完成时，自动清除阻塞。
 *   blockedBy and blocks form bidirectional dependencies. Blocks are auto-cleared on completion.
 */
public class TaskStore {

    /** JSON 序列化工具，启用格式化输出 / JSON serializer with pretty printing */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 任务文件存储目录路径 / Task file storage directory path */
    private final String tasksDir;

    /** 下一个可用的任务 ID（自增） / Next available task ID (auto-increment) */
    private int nextId;

    /**
     * 构造任务存储。
     * Constructor for TaskStore.
     *
     * 初始化时扫描 .tasks/ 目录中已有的任务文件，找到最大 ID，
     * 确保新创建的任务 ID 不会与已有任务冲突。
     * Scans existing task files in .tasks/ to find max ID on init,
     * ensuring new task IDs don't conflict with existing ones.
     *
     * @param workDir 工作目录路径 / working directory path
     */
    public TaskStore(String workDir) {
        this.tasksDir = workDir + "/.tasks";
        // 确保任务存储目录存在 / Ensure task storage directory exists
        new File(tasksDir).mkdirs();
        // 扫描已有任务文件，新任务从 maxId + 1 开始 / Scan existing tasks, start from maxId + 1
        this.nextId = scanMaxId() + 1;
    }

    /**
     * 扫描 .tasks/ 目录中所有 task_N.json 文件，返回最大的 N 值。
     * Scan all task_N.json files in .tasks/ directory, return max N value.
     *
     * @return 当前最大的任务 ID，未找到任务时返回 0 / max task ID, 0 if none found
     */
    private int scanMaxId() {
        int maxId = 0;
        File dir = new File(tasksDir);
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File f : files) {
            String name = f.getName();
            if (name.startsWith("task_") && name.endsWith(".json")) {
                try {
                    int id = Integer.parseInt(
                            name.substring(5, name.length() - 5)); // task_N.json
                    if (id > maxId) maxId = id;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return maxId;
    }

    /**
     * 创建一个新任务并持久化到文件系统。
     * Create a new task and persist it to the file system.
     *
     * @param title       任务标题 / task title
     * @param description 任务详细描述 / task description
     * @param blockedBy   阻塞源任务 ID 列表（可为 null 或空） / blocker task IDs (nullable)
     * @return 创建成功的提示信息 / success message with task ID
     */
    public String create(String title, String description, List<String> blockedBy) {
        int taskId = nextId++;
        String taskFileName = "task_" + taskId;
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());

        JsonObject task = new JsonObject();
        task.addProperty("id", taskId);
        task.addProperty("subject", title);
        task.addProperty("description", description != null ? description : "");
        task.addProperty("status", "pending");

        // 构建 blockedBy 数组 / Build blockedBy array
        JsonArray blockedByArr = new JsonArray();
        if (blockedBy != null) {
            for (String bid : blockedBy) {
                try {
                    int blockerId = Integer.parseInt(bid.trim());
                    blockedByArr.add(blockerId);

                    // 双向维护：在阻塞源任务的 blocks 列表中添加本任务 ID
                    // Bidirectional: add this task to blocker's blocks list
                    String blockerFileName = "task_" + blockerId;
                    JsonObject blocker = loadTask(blockerFileName);
                    if (blocker != null) {
                        JsonArray blocks = blocker.getAsJsonArray("blocks");
                        if (!jsonArrayContainsInt(blocks, taskId)) {
                            blocks.add(taskId);
                            blocker.addProperty("updatedAt", now);
                            saveTask(blockerFileName, blocker);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        task.add("blockedBy", blockedByArr);
        task.add("blocks", new JsonArray());
        task.addProperty("owner", "agent");
        task.addProperty("createdAt", now);
        task.addProperty("updatedAt", now);

        saveTask(taskFileName, task);
        return "Created task #" + taskId + ": " + title;
    }

    /**
     * 列出所有任务的摘要信息。
     * List summary of all tasks.
     *
     * @return 所有任务的格式化列表 / formatted task list, "No tasks found." if empty
     */
    public String list() {
        File dir = new File(tasksDir);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "No tasks found.";
        }

        // 按文件名排序，保证任务按 ID 顺序显示 / Sort by filename for ID order
        Arrays.sort(files);

        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(content).getAsJsonObject();
                int id = task.get("id").getAsInt();
                String subject = task.get("subject").getAsString();
                String status = task.get("status").getAsString();
                JsonArray blockedByArr = task.getAsJsonArray("blockedBy");

                // 状态标记：已完成用 [x]，未完成用 [ ] / Marker: [x] for completed, [ ] otherwise
                String marker = "completed".equals(status) ? "[x]" : "[ ]";

                sb.append(marker).append(" #").append(id).append(": ").append(subject);

                // 如果有阻塞依赖，追加显示 / Append blockedBy if present
                if (blockedByArr != null && blockedByArr.size() > 0) {
                    sb.append(" (blocked by: [");
                    for (int i = 0; i < blockedByArr.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(blockedByArr.get(i).getAsInt());
                    }
                    sb.append("])");
                }

                sb.append("\n");
            } catch (Exception e) {
                System.err.println("[TaskStore] Failed to read: " + f + " - " + e.getMessage());
            }
        }

        return sb.toString().trim();
    }

    /**
     * 根据任务 ID 获取任务的完整 JSON 详情。
     * Get full JSON details of a task by ID.
     *
     * @param id 任务 ID（字符串形式） / task ID (as string)
     * @return 任务的 JSON 字符串 / task JSON string, or error message if not found
     */
    public String get(String id) {
        try {
            int taskId = Integer.parseInt(id.trim());
            String taskFileName = "task_" + taskId;
            JsonObject task = loadTask(taskFileName);
            if (task == null) {
                return "Error: Task not found: #" + taskId;
            }
            return GSON.toJson(task);
        } catch (NumberFormatException e) {
            return "Error: Invalid task ID: " + id;
        }
    }

    /**
     * 更新任务的指定字段。
     * Update a specific field of a task.
     *
     * 支持的字段 / Supported fields:
     * - status: 更新状态（pending/in_progress/completed），completed 时自动清除依赖
     * - subject: 更新任务标题
     * - description: 更新任务描述
     * - owner: 更新任务负责人
     * - add_blocked_by: 添加阻塞源（value 为逗号分隔的 ID 列表）
     * - add_blocks: 添加被阻塞任务（value 为逗号分隔的 ID 列表）
     *
     * @param id    任务 ID（字符串形式） / task ID (as string)
     * @param field 要更新的字段名 / field name to update
     * @param value 新的字段值 / new field value
     * @return 更新结果描述 / update result description
     */
    public String update(String id, String field, String value) {
        int taskId;
        try {
            taskId = Integer.parseInt(id.trim());
        } catch (NumberFormatException e) {
            return "Error: Invalid task ID: " + id;
        }

        String taskFileName = "task_" + taskId;
        JsonObject task = loadTask(taskFileName);
        if (task == null) {
            return "Error: Task not found: #" + taskId;
        }

        StringBuilder changes = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());

        if ("status".equals(field)) {
            String oldStatus = task.get("status").getAsString();
            task.addProperty("status", value);
            changes.append("Status: ").append(oldStatus).append(" -> ").append(value).append(". ");

            // 当任务标记为 completed 时，自动清除依赖图中的阻塞关系
            // Auto-clear dependency blocks when task is marked as completed
            if ("completed".equals(value)) {
                clearDependency(taskId);
                changes.append("Cleared dependency blocks. ");
            }
        } else if ("subject".equals(field)) {
            task.addProperty("subject", value);
            changes.append("Subject updated. ");
        } else if ("description".equals(field)) {
            task.addProperty("description", value);
            changes.append("Description updated. ");
        } else if ("owner".equals(field)) {
            task.addProperty("owner", value);
            changes.append("Owner updated. ");
        } else if ("add_blocked_by".equals(field)) {
            JsonArray blockedBy = task.getAsJsonArray("blockedBy");
            String[] ids = value.split(",");
            for (String bid : ids) {
                try {
                    int blockerId = Integer.parseInt(bid.trim());
                    if (!jsonArrayContainsInt(blockedBy, blockerId)) {
                        blockedBy.add(blockerId);
                        changes.append("Added blockedBy: #").append(blockerId).append(". ");

                        // 双向维护 / Bidirectional maintenance
                        String blockerFileName = "task_" + blockerId;
                        JsonObject blocker = loadTask(blockerFileName);
                        if (blocker != null) {
                            JsonArray blocks = blocker.getAsJsonArray("blocks");
                            if (!jsonArrayContainsInt(blocks, taskId)) {
                                blocks.add(taskId);
                                blocker.addProperty("updatedAt", now);
                                saveTask(blockerFileName, blocker);
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } else if ("add_blocks".equals(field)) {
            JsonArray blocks = task.getAsJsonArray("blocks");
            String[] ids = value.split(",");
            for (String bid : ids) {
                try {
                    int blockedId = Integer.parseInt(bid.trim());
                    if (!jsonArrayContainsInt(blocks, blockedId)) {
                        blocks.add(blockedId);
                        changes.append("Added blocks: #").append(blockedId).append(". ");

                        // 双向维护 / Bidirectional maintenance
                        String blockedFileName = "task_" + blockedId;
                        JsonObject blocked = loadTask(blockedFileName);
                        if (blocked != null) {
                            JsonArray blockedByOther = blocked.getAsJsonArray("blockedBy");
                            if (!jsonArrayContainsInt(blockedByOther, taskId)) {
                                blockedByOther.add(taskId);
                                blocked.addProperty("updatedAt", now);
                                saveTask(blockedFileName, blocked);
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } else {
            return "Error: Unknown field '" + field + "'. Supported: status, subject, description, owner, add_blocked_by, add_blocks";
        }

        task.addProperty("updatedAt", now);
        saveTask(taskFileName, task);
        return "Updated #" + taskId + ": " + changes.toString();
    }

    /**
     * 依赖图核心操作：当任务完成时，从所有其他任务的 blockedBy 列表中移除该任务 ID。
     * Core dependency graph operation: remove completed task ID from all other tasks' blockedBy lists.
     *
     * @param completedId 刚刚完成的任务 ID / ID of the just-completed task
     */
    private void clearDependency(int completedId) {
        File dir = new File(tasksDir);
        File[] files = dir.listFiles();
        if (files == null) return;

        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());

        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(content).getAsJsonObject();
                JsonArray blockedBy = task.getAsJsonArray("blockedBy");

                if (jsonArrayContainsInt(blockedBy, completedId)) {
                    // 构建新的 blockedBy 列表，排除已完成的任务 ID
                    // Build new blockedBy list excluding the completed task ID
                    JsonArray newBlockedBy = new JsonArray();
                    for (JsonElement el : blockedBy) {
                        if (el.getAsInt() != completedId) {
                            newBlockedBy.add(el);
                        }
                    }
                    task.add("blockedBy", newBlockedBy);
                    task.addProperty("updatedAt", now);

                    // 如果所有阻塞依赖都清除了，打印解锁日志
                    // Log when all blocking dependencies are cleared
                    if (newBlockedBy.size() == 0) {
                        System.out.println("[TaskStore] Unblocked: #" + task.get("id").getAsInt());
                    }

                    saveTask("task_" + task.get("id").getAsInt(), task);
                }
            } catch (Exception e) {
                // 忽略解析错误 / Ignore parse errors
            }
        }
    }

    /**
     * 扫描所有未认领的 pending 任务（owner 为空或 "agent" 且 blockedBy 为空）。
     * Scan all unclaimed pending tasks (empty/default owner, not blocked).
     *
     * @return JSON 数组字符串，包含所有未认领任务；无任务时返回 "No unclaimed tasks."
     *         JSON array string of unclaimed tasks, or "No unclaimed tasks." if none
     */
    public String scanUnclaimed() {
        File dir = new File(tasksDir);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "No unclaimed tasks.";
        }

        Arrays.sort(files);
        JsonArray result = new JsonArray();

        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JsonObject task = JsonParser.parseString(content).getAsJsonObject();
                String status = task.get("status").getAsString();
                String owner = task.has("owner") && !task.get("owner").isJsonNull()
                        ? task.get("owner").getAsString() : "";
                JsonArray blockedBy = task.getAsJsonArray("blockedBy");

                // 只返回 pending 状态、无特定 owner、未被阻塞的任务
                // Only return pending tasks with no specific owner and not blocked
                boolean isPending = "pending".equals(status);
                boolean isUnclaimed = owner.isEmpty() || "agent".equals(owner);
                boolean isUnblocked = blockedBy == null || blockedBy.size() == 0;

                if (isPending && isUnclaimed && isUnblocked) {
                    result.add(task);
                }
            } catch (Exception e) {
                // 忽略解析错误 / Ignore parse errors
            }
        }

        if (result.size() == 0) {
            return "No unclaimed tasks.";
        }
        return GSON.toJson(result);
    }

    /**
     * 认领一个任务：设置 owner 并将状态更新为 in_progress。
     * Claim a task: set owner and update status to in_progress.
     *
     * @param id      任务 ID（字符串形式） / task ID (as string)
     * @param agentId 认领者的 Agent 名称 / claiming agent's name
     * @return 认领结果描述 / claim result description
     */
    public String claimTask(String id, String agentId) {
        int taskId;
        try {
            taskId = Integer.parseInt(id.trim());
        } catch (NumberFormatException e) {
            return "Error: Invalid task ID: " + id;
        }

        String taskFileName = "task_" + taskId;
        JsonObject task = loadTask(taskFileName);
        if (task == null) {
            return "Error: Task not found: #" + taskId;
        }

        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
        task.addProperty("owner", agentId);
        task.addProperty("status", "in_progress");
        task.addProperty("updatedAt", now);
        saveTask(taskFileName, task);

        String subject = task.has("subject") ? task.get("subject").getAsString() : "";
        return "Claimed task #" + taskId + " (" + subject + ") by " + agentId;
    }

    // ===== 辅助方法 / Helper methods =====

    /**
     * 从文件系统加载指定的任务 JSON 对象。
     * Load a task JSON object from the file system.
     *
     * @param taskFileName 任务文件名前缀（如 "task_1"） / task file name prefix (e.g. "task_1")
     * @return 任务的 JsonObject，文件不存在或解析失败时返回 null / JsonObject or null
     */
    private JsonObject loadTask(String taskFileName) {
        try {
            Path path = Paths.get(tasksDir, taskFileName + ".json");
            if (!Files.exists(path)) return null;
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将任务 JSON 对象持久化到文件系统。
     * Persist a task JSON object to the file system.
     *
     * @param taskFileName 任务文件名前缀（如 "task_1"） / task file name prefix (e.g. "task_1")
     * @param task         任务的 JsonObject / task JsonObject
     */
    private void saveTask(String taskFileName, JsonObject task) {
        try {
            Path path = Paths.get(tasksDir, taskFileName + ".json");
            Files.write(path, GSON.toJson(task).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[TaskStore] Failed to save: " + taskFileName + " - " + e.getMessage());
        }
    }

    /**
     * 检查 JsonArray 中是否包含指定的整数值。
     * Check if a JsonArray contains a specific integer value.
     *
     * @param arr   要检查的数组 / array to check
     * @param value 要查找的值 / value to find
     * @return 是否包含 / whether contained
     */
    private boolean jsonArrayContainsInt(JsonArray arr, int value) {
        for (JsonElement el : arr) {
            if (el.getAsInt() == value) return true;
        }
        return false;
    }
}
