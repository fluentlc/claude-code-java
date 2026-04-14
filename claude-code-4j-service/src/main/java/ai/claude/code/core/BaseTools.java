package ai.claude.code.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 基础工具实现：bash、read_file、write_file、edit_file。
 *
 * ===== 在 Claude Code 真实实现中的对应 =====
 * Claude Code 的工具集包含：Bash、Read、Write、Edit、Glob、Grep 等。
 * 本类实现了其中最核心的 4 个。这些工具是 AI 编程代理的"手和脚"——
 * LLM（大脑）通过调用这些工具来与文件系统和操作系统交互。
 *
 * ===== 关键安全设计 =====
 * - safePath(): 路径安全检查，防止目录遍历攻击（如 ../../etc/passwd）
 *   这是所有文件操作工具的基础防线，确保 AI 不会访问工作目录之外的文件。
 * - DANGEROUS_COMMANDS: 危险命令黑名单，阻止 rm -rf /、sudo 等破坏性操作。
 *   Claude Code 真实实现中有更复杂的权限控制系统（permission system），
 *   本类用简单的黑名单模拟了这一概念。
 *
 * ===== 数据流 =====
 * LLM 发出工具调用请求 → Agent Loop 解析请求 → 调用 BaseTools 对应方法 → 返回结果字符串
 * → Agent Loop 将结果包装成 tool_result → 传回给 LLM
 *
 * ===== 为什么返回值统一为 String？ =====
 * 因为 tool_result 的 content 就是字符串。无论工具执行成功还是失败，
 * 都返回人类可读的文本，让 LLM 能够理解执行结果并据此决定下一步行动。
 * 错误信息以 "Error: " 开头，LLM 可以识别并尝试修正。
 */
public class BaseTools {

    /**
     * 危险命令黑名单 —— 简单但有效的安全防线。
     *
     * 为什么用黑名单而不是白名单？
     * 白名单更安全（只允许已知安全的命令），但会严重限制 AI 代理的能力。
     * AI 编程代理需要执行各种命令（git、npm、python、javac 等），
     * 不可能穷举所有安全命令，所以采用黑名单模式，只拦截最危险的操作。
     *
     * Claude Code 真实实现中的做法更精细：
     * 它有一个权限系统，某些命令（如写文件、执行脚本）需要用户确认，
     * 而读取类操作则自动允许。这是安全性和便利性的平衡。
     */
    /** 命令执行超时秒数 */
    private static final int BASH_TIMEOUT_SECONDS = 120;
    /** 输出截断字符数（约 12500 token） */
    private static final int OUTPUT_CHAR_LIMIT = 50000;

    /**
     * 工作目录 —— 所有文件操作的根目录，也是命令执行的 cwd。
     * 所有相对路径都相对于此目录解析，且不允许逃逸到此目录之外。
     */
    private final String workDir;

    /** 默认构造器：使用 JVM 启动时的工作目录 */
    public BaseTools() {
        this.workDir = System.getProperty("user.dir");
    }

    /** 指定工作目录的构造器 —— 方便测试和隔离不同的执行环境 */
    public BaseTools(String workDir) {
        this.workDir = workDir;
    }

    public String getWorkDir() {
        return workDir;
    }

    /**
     * 路径安全检查：确保路径不逃逸工作目录 —— 所有文件操作的安全基石。
     *
     * ===== 为什么需要这个方法？ =====
     * AI 代理会根据 LLM 的指令操作文件。如果 LLM 被恶意 prompt 注入，
     * 可能会尝试读取 /etc/passwd 或 ~/.ssh/id_rsa 等敏感文件。
     * safePath 通过 normalize() 消除 ".." 和 "." 后，检查解析后的路径
     * 是否仍然在工作目录内，从而防止目录遍历攻击。
     *
     * ===== 攻击示例 =====
     * 输入: "../../etc/passwd"
     * normalize 后: "/etc/passwd"
     * 不以 workDir 开头 → 抛出 SecurityException
     *
     * ===== 在 Claude Code 真实实现中 =====
     * Claude Code 也有类似的路径检查逻辑，确保工具只能在项目目录内操作。
     * 此外还有文件大小限制、二进制文件检测等额外的安全措施。
     */
    public Path safePath(String p) {
        // resolve: 将相对路径拼接到 workDir 下
        // normalize: 消除 ".." 和 "."，得到规范化路径
        // toAbsolutePath: 转为绝对路径，方便比较
        Path resolved = Paths.get(workDir).resolve(p).normalize().toAbsolutePath();
        Path base = Paths.get(workDir).normalize().toAbsolutePath();
        // startsWith 检查：确保解析后的路径确实在工作目录内
        if (!resolved.startsWith(base)) {
            throw new SecurityException("Path escapes workspace: " + p);
        }
        return resolved;
    }

    /**
     * 执行 shell 命令 —— AI 代理最强大也最危险的工具。
     *
     * ===== 为什么 bash 是最重要的工具？ =====
     * 理论上，只要有 bash 工具，AI 代理就能完成几乎所有编程任务：
     * 读文件（cat）、写文件（echo >）、搜索（grep/find）、编译运行（javac/java）等。
     * 其他工具（read_file、write_file、edit_file）只是为了给 LLM 提供更结构化、
     * 更安全的操作方式，减少 bash 的使用频率。
     *
     * ===== 安全措施 =====
     * 1. 危险命令黑名单检查 —— 第一道防线
     * 2. 工作目录限制 —— 命令在 workDir 下执行
     * 3. 超时机制 —— 防止无限循环或挂起的命令
     * 4. 输出截断 —— 防止超大输出消耗过多 token
     *
     * ===== 在 Claude Code 真实实现中 =====
     * Claude Code 的 Bash 工具有更完善的安全控制：
     * - 用户确认机制：执行写操作前会要求用户确认
     * - 超时可配置
     * - 输出截断更智能（保留头尾，中间省略）
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr 合并），或错误信息
     */
    public String runBash(String command) {
        if (SecurityUtils.isDangerous(command)) return "Error: Dangerous command blocked";
        String result = ShellUtils.run(command, workDir, BASH_TIMEOUT_SECONDS);
        // 截断过长的输出，防止占用过多 token
        if (!result.startsWith("Error:") && result.length() > OUTPUT_CHAR_LIMIT) {
            result = result.substring(0, OUTPUT_CHAR_LIMIT);
        }
        return result;
    }

    /**
     * 读取文件内容 —— AI 代理了解代码库的主要方式。
     *
     * ===== 为什么需要单独的 read_file 工具？ =====
     * 虽然 bash 的 cat 命令也能读文件，但 read_file 有几个优势：
     * 1. 路径安全检查 —— 防止读取工作目录外的文件
     * 2. 行数限制 —— 避免读取超大文件导致上下文溢出
     * 3. 输出截断 —— 双重保护，确保返回内容不会过长
     * 4. 语义更清晰 —— LLM 更容易理解 "read_file" 的含义
     *
     * ===== 在 Claude Code 真实实现中 =====
     * Claude Code 的 Read 工具更智能：
     * - 支持行号范围（offset + limit）
     * - 能读取图片、PDF、Jupyter Notebook
     * - 输出带行号（方便后续 edit 时定位）
     *
     * @param path  文件路径（相对于 workDir）
     * @param limit 最大读取行数，0 表示不限制
     * @return 文件内容文本，或错误信息
     */
    public String runRead(String path, int limit) {
        try {
            Path fp = safePath(path); // 路径安全检查
            List<String> lines = Files.readAllLines(fp, StandardCharsets.UTF_8);
            // 如果文件过长，只返回前 limit 行，并告知 LLM 还有多少行未读
            // 这让 LLM 知道文件的完整大小，必要时可以分段读取
            if (limit > 0 && limit < lines.size()) {
                int totalLines = lines.size();
                lines = new ArrayList<String>(lines.subList(0, limit));
                lines.add("... (" + (totalLines - limit) + " more lines)");
            }
            String result = String.join("\n", lines);
            return result.length() > OUTPUT_CHAR_LIMIT ? result.substring(0, OUTPUT_CHAR_LIMIT) : result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写文件 —— 创建新文件或完全覆盖已有文件。
     *
     * ===== 与 edit_file 的区别 =====
     * - write_file：整体写入，适合创建新文件或完全重写
     * - edit_file：精确替换，适合修改已有文件中的特定片段
     *
     * LLM 会根据场景自动选择合适的工具：
     * - 创建新文件 → write_file
     * - 修改已有文件的某几行 → edit_file
     * - 完全重写小文件 → write_file
     *
     * ===== 为什么要 createDirectories？ =====
     * LLM 可能会写入一个不存在的目录下的文件（如 src/main/java/com/xxx/Test.java），
     * 自动创建父目录可以减少一次额外的 mkdir 工具调用，提升效率。
     *
     * @param path    文件路径（相对于 workDir）
     * @param content 要写入的完整内容
     * @return 成功信息（包含写入的字节数），或错误信息
     */
    public String runWrite(String path, String content) {
        try {
            Path fp = safePath(path); // 路径安全检查
            // 自动创建父目录 —— 避免因目录不存在而失败
            Files.createDirectories(fp.getParent());
            Files.write(fp, content.getBytes(StandardCharsets.UTF_8));
            // 返回写入字节数，让 LLM 确认写入成功
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 编辑文件：精确文本替换 —— AI 代理最常用的修改文件方式。
     *
     * ===== 为什么用"查找替换"而不是"行号定位"？ =====
     * 因为 LLM 在多轮对话中，之前读到的行号可能已经过时（文件被修改过）。
     * 而文本内容匹配更可靠 —— 只要 old_text 存在于文件中，就能精确定位。
     * Claude Code 真实实现中的 Edit 工具也是基于精确文本匹配的。
     *
     * ===== 只替换第一次出现的设计理由 =====
     * 这是防御性设计。如果 old_text 在文件中出现多次（如常见的代码模式），
     * 全部替换可能造成意外修改。只替换第一次出现更安全。
     * 如果 LLM 需要替换所有出现，它可以多次调用此工具。
     *
     * ===== 错误反馈 =====
     * 如果 old_text 找不到，返回错误信息让 LLM 知道。
     * LLM 通常会重新读取文件，获取最新内容后重试。
     *
     * @param path    文件路径（相对于 workDir）
     * @param oldText 要被替换的文本（必须精确匹配）
     * @param newText 替换后的新文本
     * @return 成功信息或错误信息
     */
    public String runEdit(String path, String oldText, String newText) {
        try {
            Path fp = safePath(path); // 路径安全检查
            String content = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
            // 先检查 old_text 是否存在 —— 提前失败，给出明确错误信息
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }
            // 只替换第一次出现 —— 防御性设计，避免意外的全局替换
            int idx = content.indexOf(oldText);
            String newContent = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
            Files.write(fp, newContent.getBytes(StandardCharsets.UTF_8));
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================================================================================
    // 工具定义 JSON —— 告诉 LLM 有哪些工具可用
    // ==================================================================================
    //
    // 每个工具定义包含三部分：
    // 1. name: 工具名称 —— LLM 通过这个名字来调用工具
    // 2. description: 工具描述 —— LLM 通过这个描述来理解工具的用途和使用场景
    // 3. input_schema: 输入参数的 JSON Schema —— 定义参数名称、类型、是否必需
    //
    // 这些定义在每次 API 调用时都会传给 LLM，相当于告诉它"你手里有这些工具可以用"。
    // LLM 会根据任务需求、工具描述来决定使用哪个工具以及传什么参数。
    // ==================================================================================

    /**
     * 返回 bash 工具定义。
     * bash 是最通用的工具，能执行任意 shell 命令。
     * 参数只有一个：command（字符串，必需）。
     */
    public static JsonObject bashToolDef() {
        return AnthropicClient.toolDef("bash", "Run a shell command.",
                AnthropicClient.schema("command", "string", "true"));
    }

    /**
     * 返回 read_file 工具定义。
     * 参数：path（必需）+ limit（可选，限制读取行数）。
     * limit 设为非必需参数，默认读取全部内容。
     */
    public static JsonObject readFileToolDef() {
        return AnthropicClient.toolDef("read_file", "Read file contents.",
                AnthropicClient.schema("path", "string", "true", "limit", "integer", "false"));
    }

    /**
     * 返回 write_file 工具定义。
     * 参数：path（必需）+ content（必需）。
     * 两个参数都是必需的，因为写文件必须知道写到哪里、写什么内容。
     */
    public static JsonObject writeFileToolDef() {
        return AnthropicClient.toolDef("write_file", "Write content to file.",
                AnthropicClient.schema("path", "string", "true", "content", "string", "true"));
    }

    /**
     * 返回 edit_file 工具定义。
     * 参数：path（必需）+ old_text（必需）+ new_text（必需）。
     * 三个参数缺一不可：需要知道编辑哪个文件、替换什么、替换成什么。
     */
    public static JsonObject editFileToolDef() {
        return AnthropicClient.toolDef("edit_file", "Replace exact text in file.",
                AnthropicClient.schema("path", "string", "true",
                        "old_text", "string", "true",
                        "new_text", "string", "true"));
    }

    /**
     * 返回所有基础工具定义 —— 方便一次性注册所有工具。
     *
     * 为什么要提供这个便捷方法？
     * 统一提供 allToolDefs()，确保 4 个基础工具定义的一致性。
     */
    public static JsonArray allToolDefs() {
        JsonArray tools = new JsonArray();
        tools.add(bashToolDef());
        tools.add(readFileToolDef());
        tools.add(writeFileToolDef());
        tools.add(editFileToolDef());
        return tools;
    }
}
