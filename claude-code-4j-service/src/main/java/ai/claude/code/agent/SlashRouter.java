package ai.claude.code.agent;

/**
 * 解析用户输入的 slash 命令，返回路由结果。
 * 命令格式：/command [args...]
 */
public class SlashRouter {

    public enum Command {
        TEAM,       // /team — 多 Agent 团队模式
        WORKTREE,   // /worktree [name] — worktree 隔离模式
        SKILL,      // /skill [name] — 列出或加载 skill
        TASKS,      // /tasks — 查看任务列表
        COMPACT,    // /compact — 手动触发上下文压缩
        HELP,       // /help — 显示帮助信息
        NONE        // 非 slash 命令，普通用户输入
    }

    public static class RouteResult {
        public final Command command;
        public final String args;

        public RouteResult(Command command, String args) {
            this.command = command;
            this.args = args;
        }
    }

    public static RouteResult route(String input) {
        if (input == null || !input.startsWith("/")) {
            return new RouteResult(Command.NONE, input);
        }
        String[] parts = input.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        if ("team".equals(cmd))     return new RouteResult(Command.TEAM, args);
        if ("worktree".equals(cmd)) return new RouteResult(Command.WORKTREE, args);
        if ("skill".equals(cmd))    return new RouteResult(Command.SKILL, args);
        if ("tasks".equals(cmd))    return new RouteResult(Command.TASKS, args);
        if ("compact".equals(cmd))  return new RouteResult(Command.COMPACT, args);
        if ("help".equals(cmd))     return new RouteResult(Command.HELP, args);

        // 未知 slash 命令，作为普通输入处理
        return new RouteResult(Command.NONE, input);
    }

    public static String helpText() {
        return "可用命令：\n"
                + "  /tasks    — 查看当前 todo 列表\n"
                + "  /skill    — 列出可用 skills\n"
                + "  /compact  — 手动压缩对话上下文\n"
                + "  /team     — 启动多 Agent 团队模式\n"
                + "  /worktree [name] — 创建 git worktree 隔离环境\n"
                + "  /help     — 显示此帮助\n"
                + "  quit      — 退出";
    }
}
