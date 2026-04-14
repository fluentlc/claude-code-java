package ai.claude.code.capability;

import ai.claude.code.core.SecurityUtils;
import ai.claude.code.core.ShellUtils;
import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Worktree 管理器 - 基于 Git Worktree 的目录级隔离。
 * Worktree Manager - directory-level isolation via Git Worktree.
 *
 * 【核心职责 / Core Responsibilities】
 * 1. 创建 Git Worktree（隔离的工作目录 + 独立的 Git 分支）
 *    Create Git Worktree (isolated working directory + independent Git branch)
 * 2. 在 Worktree 中执行命令（目录级隔离，互不干扰）
 *    Execute commands in Worktree (directory-level isolation, no interference)
 * 3. 管理 Worktree 生命周期（create -> active -> kept/removed）
 *    Manage Worktree lifecycle (create -> active -> kept/removed)
 *
 * 【为什么用 Git Worktree / Why Git Worktree】
 * - 每个 Worktree 是独立的工作目录，有自己的文件系统视图
 *   Each Worktree is an independent working directory with its own filesystem view
 * - 多个任务可以并行执行，互相不影响
 *   Multiple tasks can execute in parallel without affecting each other
 * - 每个 Worktree 在独立分支上工作，Git 历史清晰
 *   Each Worktree works on an independent branch, keeping Git history clean
 *
 * 【持久化 / Persistence】
 * Worktree 索引存储在 .worktrees/index.json:
 * Worktree index is stored at .worktrees/index.json:
 *   {
 *     "wt-abc": {
 *       "id": "wt-abc",
 *       "path": "/absolute/path/to/.worktrees/wt-abc",
 *       "branch": "wt/wt-abc",
 *       "status": "active|kept|removed",
 *       "created": "2024-01-01T12:00:00"
 *     }
 *   }
 *
 * 【安全机制 / Safety】
 * - 危险命令黑名单：rm -rf /、sudo、shutdown 等
 *   Dangerous command blocklist: rm -rf /, sudo, shutdown, etc.
 * - Worktree 名称正则校验：只允许字母、数字、点、下划线、连字符
 *   Worktree name regex validation: only letters, digits, dots, underscores, hyphens
 */
public class WorktreeManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 统一的时间戳格式 / Unified timestamp format */
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    /** Worktree 名称正则: 只允许字母、数字、点、下划线、连字符，最长 40 字符 */
    /** Worktree name pattern: letters, digits, dots, underscores, hyphens, max 40 chars */
    private static final String NAME_PATTERN = "[A-Za-z0-9._-]{1,40}";

    private final String workDir;
    private final String repoRoot;
    private final boolean gitAvailable;
    private final Path indexPath;
    private final Map<String, JsonObject> worktrees = new LinkedHashMap<String, JsonObject>();

    public WorktreeManager(String workDir) {
        this.workDir = workDir;
        // 检测 git 仓库根目录: Worktree 操作需要在 git 仓库中
        // Detect git repo root: Worktree operations require a git repository
        this.repoRoot = detectRepoRoot(workDir);
        this.gitAvailable = (this.repoRoot != null);
        String baseDir = gitAvailable ? repoRoot : workDir;
        this.indexPath = Paths.get(baseDir, ".worktrees", "index.json");
        try {
            Files.createDirectories(indexPath.getParent());
        } catch (IOException ignore) {
        }
        loadIndex();
    }

    // ========================= 公开接口 / Public API =========================

    /**
     * 创建 Git Worktree
     * Create a Git Worktree
     *
     * 流程 / Flow:
     *   1. 生成或校验 worktree 名称 / Generate or validate worktree name
     *   2. 执行 git worktree add -b wt/<name> <path> <baseRef>
     *   3. 注册到 worktree 索引 (index.json) / Register in index
     *
     * @param name    可选的 worktree 名称，为 null 时自动生成 "wt-xxxxxxxx"
     *                Optional worktree name, auto-generated "wt-xxxxxxxx" when null
     * @param baseRef 基准 git ref (默认 "HEAD")，新分支从此处创建
     *                Base git ref (default "HEAD"), new branch created from here
     * @return 创建结果描述 / Creation result description
     */
    public synchronized String create(String name, String baseRef) {
        if (!gitAvailable) {
            return "Error: not in a git repository. Worktree operations require git.";
        }
        if (name == null || name.isEmpty()) {
            name = "wt-" + UUID.randomUUID().toString().substring(0, 8);
        }
        // 名称验证 / Name validation
        if (!name.matches(NAME_PATTERN)) {
            return "Error: invalid worktree name '" + name + "'. Must match " + NAME_PATTERN;
        }
        if (worktrees.containsKey(name)) {
            return "Worktree '" + name + "' already exists.";
        }

        String baseDir = repoRoot != null ? repoRoot : workDir;
        Path wtPath = Paths.get(baseDir, ".worktrees", name);
        String branch = "wt/" + name;
        if (baseRef == null || baseRef.isEmpty()) {
            baseRef = "HEAD";
        }

        // 执行 git worktree add / Execute git worktree add
        String cmd = "git worktree add -b " + branch + " " + wtPath.toAbsolutePath().toString() + " " + baseRef;
        String output = runCmd(cmd, baseDir);
        if (output.contains("Error") || output.contains("fatal")) {
            return "Failed to create worktree: " + output;
        }

        JsonObject wt = new JsonObject();
        wt.addProperty("id", name);
        wt.addProperty("path", wtPath.toAbsolutePath().toString());
        wt.addProperty("branch", branch);
        wt.addProperty("status", "active");
        wt.addProperty("created", DATE_FMT.format(new Date()));
        worktrees.put(name, wt);
        saveIndex();

        return "Created worktree '" + name + "' at " + wtPath.toAbsolutePath().toString();
    }

    /**
     * 在 Worktree 目录中执行命令（目录级隔离的核心）
     * Execute a command inside a Worktree directory (core of directory-level isolation)
     *
     * - 命令在 worktree 的工作目录中执行 (ProcessBuilder.directory)
     *   Command runs in the worktree's working directory (ProcessBuilder.directory)
     * - 包含危险命令检查 (rm -rf /, sudo 等)
     *   Includes dangerous command check (rm -rf /, sudo, etc.)
     *
     * @param name    worktree 名称 / worktree name
     * @param command 要执行的 shell 命令 / shell command to execute
     * @return 命令输出 / command output
     */
    public String run(String name, String command) {
        JsonObject wt = worktrees.get(name);
        if (wt == null) {
            return "Worktree not found: " + name;
        }
        String path = wt.get("path").getAsString();
        if (!Files.isDirectory(Paths.get(path))) {
            return "Worktree directory missing: " + path;
        }

        if (SecurityUtils.isDangerous(command)) return "Error: Dangerous command blocked";

        String output = runCmd(command, path);
        return "=== worktree_run [" + name + "] ===\n" + output;
    }

    /**
     * 列出所有 worktree
     * List all worktrees
     *
     * @return JSON 格式的 worktree 列表 / JSON formatted worktree list
     */
    public String list() {
        if (worktrees.isEmpty()) {
            return "No worktrees.";
        }
        JsonArray arr = new JsonArray();
        for (JsonObject wt : worktrees.values()) {
            arr.add(wt);
        }
        return GSON.toJson(arr);
    }

    /**
     * 移除 Worktree
     * Remove a Worktree
     *
     * 流程 / Flow:
     *   1. 执行 git worktree remove (清理目录) / Execute git worktree remove
     *   2. 在索引中标记为 removed / Mark as removed in index
     *
     * 注意: 分支不会被删除（git worktree remove 不删分支），方便后续查看历史或合并
     * Note: branch is NOT deleted (git worktree remove doesn't delete branches)
     *
     * @param name  worktree 名称 / worktree name
     * @param force 强制移除（即使有未提交的更改） / Force remove (even with uncommitted changes)
     * @return 移除结果描述 / Removal result description
     */
    public synchronized String remove(String name, boolean force) {
        JsonObject wt = worktrees.get(name);
        if (wt == null) {
            return "Worktree not found: " + name;
        }

        String path = wt.get("path").getAsString();

        // git worktree remove (不删除分支 / Do NOT delete the branch)
        String forceFlag = force ? " --force" : "";
        runCmd("git worktree remove " + path + forceFlag, repoRoot != null ? repoRoot : workDir);

        // 标记为 removed 而非从索引删除 / Mark as removed instead of deleting from index
        wt.addProperty("status", "removed");
        wt.addProperty("removed_at", DATE_FMT.format(new Date()));
        saveIndex();

        return "Removed worktree '" + name + "'.";
    }

    /**
     * 查看 worktree 的 git 状态
     * View worktree git status
     *
     * @param name worktree 名称 / worktree name
     * @return git status 输出 / git status output
     */
    public String status(String name) {
        JsonObject wt = worktrees.get(name);
        if (wt == null) {
            return "Worktree not found: " + name;
        }
        if (!gitAvailable) {
            return "Error: not in a git repository.";
        }
        String path = wt.get("path").getAsString();
        if (!Files.isDirectory(Paths.get(path))) {
            return "Worktree directory missing: " + path;
        }
        // 运行实际 git status / Run actual git status
        String gitOutput = runCmd("git status --short --branch", path);
        return "=== worktree_status [" + name + "] ===\n" + gitOutput;
    }

    // ========================= 内部方法 / Internal Methods =========================

    /** 检测 git 仓库根目录 / Detect git repo root via git rev-parse */
    private static String detectRepoRoot(String dir) {
        if (ShellUtils.exitCode("git rev-parse --show-toplevel", dir) != 0) return null;
        String root = ShellUtils.run("git rev-parse --show-toplevel", dir, 10);
        return (root.isEmpty() || root.startsWith("Error:")) ? null : root;
    }

    /** 加载 worktree 索引 / Load worktree index from index.json */
    private void loadIndex() {
        if (Files.exists(indexPath)) {
            try {
                String json = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
                JsonObject idx = JsonParser.parseString(json).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : idx.entrySet()) {
                    worktrees.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            } catch (IOException e) {
                /* ignore */
            }
        }
    }

    /** 保存 worktree 索引 / Save worktree index to index.json */
    private void saveIndex() {
        try {
            Files.createDirectories(indexPath.getParent());
            JsonObject idx = new JsonObject();
            for (Map.Entry<String, JsonObject> e : worktrees.entrySet()) {
                idx.add(e.getKey(), e.getValue());
            }
            Files.write(indexPath, GSON.toJson(idx).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[WorktreeManager] Index save error: " + e.getMessage());
        }
    }

    /** 执行 shell 命令 / Run shell command in specified directory */
    private String runCmd(String command, String dir) {
        return ShellUtils.run(command, dir, ShellUtils.DEFAULT_TIMEOUT);
    }
}
