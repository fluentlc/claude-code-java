package ai.claude.code.core;

import java.util.Arrays;
import java.util.List;

/**
 * 安全工具：危险命令检测。
 * Security utilities: dangerous command detection.
 *
 * 采用黑名单模式拦截最具破坏性的操作；读取类命令自动放行。
 * Blocklist approach: intercepts most destructive operations; read-only commands pass through.
 */
public final class SecurityUtils {

    /** 危险命令模式列表，包含任一子串的命令将被拒绝执行 */
    public static final List<String> DANGEROUS_PATTERNS = Arrays.asList(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    private SecurityUtils() {}

    /**
     * 检查命令是否包含危险模式。
     * Check whether the command contains any dangerous pattern.
     */
    public static boolean isDangerous(String command) {
        for (String pattern : DANGEROUS_PATTERNS) {
            if (command.contains(pattern)) return true;
        }
        return false;
    }
}
