package ai.claude.code.capability;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 技能加载器 - 扫描 skills/ 目录，解析 SKILL.md 文件。
 * Skill loader - scans the skills/ directory and parses SKILL.md files.
 *
 * 实现两层注入架构：
 * Implements a two-layer injection architecture:
 *   Layer 1: getDescriptions() — 轻量目录，注入 system prompt
 *            Lightweight catalog injected into system prompt
 *   Layer 2: getContent(name) — 完整正文，按需通过 load_skill 工具返回
 *            Full body returned on demand via load_skill tool
 */
public class SkillLoader {

    /**
     * 技能元数据 - 存储从 SKILL.md frontmatter 解析出的信息。
     * Skill metadata - stores information parsed from SKILL.md frontmatter.
     */
    public static class SkillMeta {
        String name;
        String description;
        String tags;
        String body;

        SkillMeta(String name, String description, String tags, String body) {
            this.name = name;
            this.description = description;
            this.tags = tags;
            this.body = body;
        }
    }

    private final Map<String, SkillMeta> skills = new LinkedHashMap<String, SkillMeta>();
    private final String skillsDir;

    public SkillLoader(String skillsDir) {
        this.skillsDir = skillsDir;
        scan();
    }

    private void scan() {
        File dir = new File(skillsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("[SkillLoader] Skills directory not found: " + skillsDir);
            return;
        }
        scanRecursive(dir);
    }

    private void scanRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File skillFile = new File(child, "SKILL.md");
            if (skillFile.exists()) {
                try {
                    String content = new String(
                            Files.readAllBytes(skillFile.toPath()), StandardCharsets.UTF_8);
                    SkillMeta meta = parseFrontmatter(content, child.getName());
                    if (meta != null) {
                        skills.put(meta.name, meta);
                        System.out.println("[SkillLoader] Loaded skill: " + meta.name);
                    }
                } catch (IOException e) {
                    System.err.println("[SkillLoader] Failed to read: " + skillFile + " - " + e.getMessage());
                }
            }
            scanRecursive(child);
        }
    }

    private SkillMeta parseFrontmatter(String content, String dirName) {
        if (!content.startsWith("---")) return null;
        int secondDelim = content.indexOf("---", 3);
        if (secondDelim < 0) return null;

        String frontmatter = content.substring(3, secondDelim).trim();
        String body = content.substring(secondDelim + 3).trim();

        Map<String, String> meta = new HashMap<String, String>();
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                meta.put(line.substring(0, colonIdx).trim(),
                         line.substring(colonIdx + 1).trim());
            }
        }

        String name = meta.get("name");
        if (name == null || name.isEmpty()) name = dirName;

        return new SkillMeta(
                name,
                meta.containsKey("description") ? meta.get("description") : "(no description)",
                meta.containsKey("tags") ? meta.get("tags") : "",
                body
        );
    }

    /** Layer 1：轻量目录，注入 system prompt / Lightweight catalog for system prompt */
    public String getDescriptions() {
        if (skills.isEmpty()) return "(No skills available)";
        StringBuilder sb = new StringBuilder();
        sb.append("Available skills (use load_skill to get full instructions):\n");
        for (SkillMeta meta : skills.values()) {
            sb.append("  - ").append(meta.name).append(": ").append(meta.description);
            if (meta.tags != null && !meta.tags.isEmpty()) {
                sb.append("  [tags: ").append(meta.tags).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Layer 2：完整正文，当模型调用 load_skill 时返回 / Full body returned via load_skill */
    public String getContent(String name) {
        SkillMeta meta = skills.get(name);
        if (meta == null) {
            return "Error: Unknown skill '" + name + "'. Available: " + skills.keySet();
        }
        return "<skill name=\"" + name + "\">\n" + meta.body + "\n</skill>";
    }

    public Set<String> getSkillNames() {
        return skills.keySet();
    }
}
