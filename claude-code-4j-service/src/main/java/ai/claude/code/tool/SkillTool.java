package ai.claude.code.tool;

import ai.claude.code.capability.SkillLoader;
import ai.claude.code.core.OpenAiClient;
import ai.claude.code.core.ToolHandler;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能加载工具提供者 — 封装 load_skill 工具，供模型按需加载技能详情。
 * Skill load tool provider — wraps the load_skill tool for on-demand skill loading.
 *
 * 实现两层注入架构中的 Layer 2：
 * Implements Layer 2 of the two-layer injection architecture:
 *   Layer 1: SkillLoader.getDescriptions() 在 system prompt 中注入轻量目录
 *            SkillLoader.getDescriptions() injects a lightweight catalog into the system prompt
 *   Layer 2: 本工具在模型需要某个技能的完整指令时，通过 load_skill 工具按需返回正文
 *            This tool returns the full skill body on demand when the model needs it
 *
 * 按需加载的好处：避免把所有技能的完整正文都塞进 system prompt，节省大量 token。
 * On-demand loading avoids stuffing all skill bodies into the system prompt, saving tokens.
 */
public class SkillTool implements ToolProvider {

    /** 技能加载器实例，负责扫描目录和返回技能内容 / SkillLoader instance for scanning and retrieval */
    private final SkillLoader sl;

    public SkillTool(SkillLoader sl) {
        this.sl = sl;
    }

    /**
     * 返回工具名称到处理器的映射，供 agentLoop 分派使用。
     * Returns the tool name to handler mapping for agentLoop dispatch.
     */
    public Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> map = new LinkedHashMap<String, ToolHandler>();

        // load_skill: 按名称返回技能的完整指令正文
        // load_skill: return the full skill instruction body by name
        map.put("load_skill", new ToolHandler() {
            public String execute(JsonObject input) {
                return sl.getContent(input.get("name").getAsString());
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

        // load_skill: 只需 name 参数（必填）/ Only requires name (required)
        defs.add(OpenAiClient.toolDef(
                "load_skill",
                "Load a skill by name to get its full instructions. Use the skill names from the catalog in the system prompt.",
                OpenAiClient.schema("name", "string", "true")));

        return defs;
    }
}
