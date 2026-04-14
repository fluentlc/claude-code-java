package ai.claude.code.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具，统一封装 ProcessBuilder 样板代码。
 * Shell command execution utility — centralizes ProcessBuilder boilerplate.
 *
 * 使用方式：
 *   ShellUtils.run("git status", workDir, 30)   // 带超时
 *   ShellUtils.run("npm install", workDir, 0)    // 无超时（0 = 阻塞到完成）
 */
public final class ShellUtils {

    /** 默认命令超时秒数 */
    public static final int DEFAULT_TIMEOUT = 120;

    private ShellUtils() {}

    /**
     * 在指定目录执行 shell 命令，返回合并后的 stdout+stderr 输出。
     * Run a shell command in the given directory; returns merged stdout+stderr.
     *
     * @param command        要执行的 shell 命令 / Shell command to execute
     * @param workDir        工作目录，null 表示继承当前进程目录 / Working dir, null = inherit
     * @param timeoutSeconds 超时秒数，0 表示无超时（阻塞直到完成）/ Timeout in seconds, 0 = no timeout
     * @return 命令输出（trimmed），空输出返回 "(no output)"，失败返回 "Error: ..."
     */
    public static String run(String command, String workDir, int timeoutSeconds) {
        try {
            Process proc = buildProcess(command, workDir);
            String output = drain(proc);

            if (timeoutSeconds > 0) {
                boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!done) {
                    proc.destroyForcibly();
                    return "Error: Timeout (" + timeoutSeconds + "s)";
                }
            } else {
                proc.waitFor();
            }

            return output.isEmpty() ? "(no output)" : output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 执行命令并返回退出码，忽略输出内容。
     * Run a command and return its exit code; output is discarded.
     *
     * @return 退出码，异常时返回 -1 / Exit code, -1 on exception
     */
    public static int exitCode(String command, String workDir) {
        try {
            Process proc = buildProcess(command, workDir);
            drain(proc); // 必须排空输出缓冲，否则进程可能阻塞 / Must drain to prevent deadlock
            return proc.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== 私有方法 ====================

    private static Process buildProcess(String command, String workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.command("cmd", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }
        if (workDir != null) pb.directory(new File(workDir));
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static String drain(Process proc) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        } finally {
            reader.close();
        }
        return sb.toString().trim();
    }
}
