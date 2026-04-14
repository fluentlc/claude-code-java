package ai.claude.code.tool;

import ai.claude.code.capability.WorktreeManager;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.tool.ToolUtils;
import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worktree 管理工具集 — 提供基于 Git Worktree 的目录级隔离创建、列举与移除能力。
 * Worktree management tools — directory-level isolation via Git Worktree: create, list, remove.
 *
 * 包含 3 个工具 / Contains 3 tools:
 *   - worktree_create: 创建一个新的 Git Worktree / Create a new Git Worktree
 *   - worktree_list:   列出所有已知的 Worktree / List all known worktrees
 *   - worktree_remove: 移除指定的 Worktree / Remove a specific worktree
 */
public class WorktreeTools implements ToolProvider {

    private final WorktreeManager wm;

    /**
     * 构造 Worktree 管理工具集。
     * Constructor for worktree management tools.
     *
     * @param wm WorktreeManager 实例 / WorktreeManager instance
     */
    public WorktreeTools(WorktreeManager wm) {
        this.wm = wm;
    }

    @Override
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new LinkedHashMap<String, ToolHandler>();

        // worktree_create: 创建新 Git Worktree，name 和 base_ref 均可选
        // worktree_create: create a new Git Worktree; name and base_ref are both optional
        map.put("worktree_create", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String name = (input.has("name") && !input.get("name").isJsonNull())
                        ? input.get("name").getAsString()
                        : null;
                String baseRef = (input.has("base_ref") && !input.get("base_ref").isJsonNull())
                        ? input.get("base_ref").getAsString()
                        : null;
                return wm.create(name, baseRef);
            }
        });

        // worktree_list: 列出所有 Worktree（JSON 格式）
        // worktree_list: list all worktrees in JSON format
        map.put("worktree_list", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                return wm.list();
            }
        });

        // worktree_remove: 移除指定名称的 Worktree，force 参数可选
        // worktree_remove: remove the specified worktree; force is optional
        map.put("worktree_remove", new ToolHandler() {
            @Override
            public String execute(JsonObject input) {
                String name = input.get("name").getAsString();
                boolean force = input.has("force") && input.get("force").getAsBoolean();
                return wm.remove(name, force);
            }
        });

        return map;
    }

    @Override
    public List<JsonObject> definitions() {
        List<JsonObject> defs = new ArrayList<JsonObject>();

        // worktree_create: name 和 base_ref 均可选
        // worktree_create: name and base_ref are both optional
        defs.add(OpenAiClient.toolDef(
                "worktree_create",
                "Create a new Git Worktree for isolated task execution. " +
                "name is auto-generated if not provided; base_ref defaults to HEAD.",
                OpenAiClient.schema(
                        "name", "string", "false",
                        "base_ref", "string", "false"
                )
        ));

        // worktree_list: 无参数
        // worktree_list: no parameters
        defs.add(OpenAiClient.toolDef(
                "worktree_list",
                "List all known Git Worktrees and their status (active/kept/removed).",
                ToolUtils.emptySchema()
        ));

        // worktree_remove: name 必填，force 可选
        // worktree_remove: name is required, force is optional
        defs.add(OpenAiClient.toolDef(
                "worktree_remove",
                "Remove a Git Worktree by name. " +
                "Set force=true to remove even if there are uncommitted changes.",
                OpenAiClient.schema(
                        "name", "string", "true",
                        "force", "boolean", "false"
                )
        ));

        return defs;
    }

}
