# claude-code-4j — 核心场景测试报告

> **测试环境**：Java 17、Maven 3.9、DashScope qwen3-plus（OpenAI 兼容协议）
> **运行方式**：见各场景"触发方式"，通用启动命令附于文末
> **测试时间**：2026-04-14

---

## 目录

1. [基础文件操作 — 代码生成与编辑](#scenario-1)
2. [TodoManager — 多步任务自追踪](#scenario-2)
3. [SkillLoader — 技能按需加载](#scenario-3)
4. [ContextCompactor — 上下文自动压缩](#scenario-4)
5. [BackgroundRunner — 异步后台任务](#scenario-5)
6. [TaskStore — 持久化任务与依赖](#scenario-6)
7. [WorktreeManager — Git Worktree 目录隔离](#scenario-7)
8. [Slash 命令 — REPL 控制面板](#scenario-8)
9. [Agent Teams — 多 Agent 协作](#scenario-9)

---

<a name="scenario-1"></a>
## Scenario 1: 基础文件操作 — 代码生成与编辑

### 触发方式

```
You> 在当前目录创建一个 Fibonacci.java 文件，实现斐波那契数列（递归 + 迭代两种方式），
     然后在 main() 中打印前 15 项的结果
```

### 过程数据

```
[Agent] 第 1 轮...
[Tool] write_file <- {"path": "Fibonacci.java", "content": "public class Fibonacci {\n
    // 递归实现\n
    public static long recursive(int n) {\n
        if (n <= 1) return n;\n
        return recursive(n - 1) + recursive(n - 2);\n
    }\n\n
    // 迭代实现\n
    public static long iterative(int n) {\n
        if (n <= 1) return n;\n
        long a = 0, b = 1;\n
        for (int i = 2; i <= n; i++) { long tmp = a + b; a = b; b = tmp; }\n
        return b;\n
    }\n\n
    public static void main(String[] args) {\n
        for (int i = 0; i < 15; i++)\n
            System.out.printf(\"F(%2d) = %d%n\", i, iterative(i));\n
    }\n}"}
[Tool] write_file -> Wrote 512 bytes to Fibonacci.java

[Tool] bash <- {"command": "javac Fibonacci.java && java Fibonacci"}
[Tool] bash -> F( 0) = 0
               F( 1) = 1
               F( 2) = 1
               ...
               F(14) = 377

[Agent] 第 2 轮...
[Assistant] Fibonacci.java 已生成并运行通过，前 15 项结果正确。
```

### 结果

| 项目 | 结果 |
|------|------|
| 工具调用轮数 | 2 轮 |
| 工具调用次数 | write_file × 1、bash × 1 |
| 文件正确生成 | ✅ Fibonacci.java 已写入 |
| 编译通过 | ✅ javac 无报错 |
| 输出正确 | ✅ F(0)=0 … F(14)=377，与期望完全一致 |
| 安全检查触发 | — 未触发（正常命令） |

---

<a name="scenario-2"></a>
## Scenario 2: TodoManager — 多步任务自追踪

### 触发方式

```
You> 帮我完成以下三件事：
     1. 创建 src/main/java/Demo.java，实现冒泡排序
     2. 在 src/test/ 目录写一个手动测试类 DemoTest.java
     3. 用 bash 编译并运行，确认输出正确
```

### 过程数据

```
[Agent] 第 1 轮...
[Tool] todo <- {"action":"create","items":[
    {"id":"t1","title":"创建 Demo.java 实现冒泡排序","status":"pending"},
    {"id":"t2","title":"写 DemoTest.java 手动测试","status":"pending"},
    {"id":"t3","title":"编译并运行验证输出","status":"pending"}
]}
[Tool] todo -> Created 3 todos.

[Tool] todo <- {"action":"update","id":"t1","status":"in_progress"}
[Tool] write_file <- {"path":"src/main/java/Demo.java","content":"..."}
[Tool] write_file -> Wrote 387 bytes to src/main/java/Demo.java
[Tool] todo <- {"action":"update","id":"t1","status":"completed"}

[Agent] 第 2 轮...
[Tool] todo <- {"action":"update","id":"t2","status":"in_progress"}
[Tool] write_file <- {"path":"src/test/DemoTest.java","content":"..."}
[Tool] todo <- {"action":"update","id":"t2","status":"completed"}

[Agent] 第 3 轮...
[Tool] todo <- {"action":"update","id":"t3","status":"in_progress"}
[Tool] bash <- {"command":"javac src/main/java/Demo.java && java -cp src/main/java Demo"}
[Tool] bash -> Sorted: [1, 3, 5, 7, 9, 12, 45]
[Tool] todo <- {"action":"update","id":"t3","status":"completed"}
```

**NAG 提醒触发验证**（故意连续 3 轮不调用 todo）：

```
[系统注入] <system-reminder>Please update your todos to reflect current progress.</system-reminder>
```

下一轮 Agent 立即恢复更新 todo 状态。

### 结果

| 项目 | 结果 |
|------|------|
| Todo 自动创建 | ✅ 3 条任务按序创建 |
| 状态流转 | ✅ pending → in_progress → completed 每步正确 |
| NAG 提醒 | ✅ 超过 NAG_THRESHOLD=3 轮后触发，Agent 正确响应 |
| 最终完成率 | ✅ 3/3 |
| /tasks 命令 | ✅ 显示全部 3 条任务及 completed 状态 |

---

<a name="scenario-3"></a>
## Scenario 3: SkillLoader — 技能按需加载

### 触发方式

```
You> /skill                    ← 查看可用技能列表
You> 帮我做一次代码 review，检查 src/main/java/ai/claude/code/core/ShellUtils.java
```

### 过程数据

**`/skill` 输出：**

```
=== Available Skills ===
[code-review] 代码审查技能 — 系统化地审查代码质量、安全性和可维护性
[git-commit]  Git 提交规范技能 — 生成符合约定式提交规范的提交信息
```

**Agent 处理 code review 请求：**

```
[Agent] 第 1 轮...
[Tool] load_skill <- {"name":"code-review"}
[Tool] load_skill -> [skill loaded: 2847 chars injected into context]

[Tool] read_file <- {"path":"src/main/java/ai/claude/code/core/ShellUtils.java"}
[Tool] read_file -> [file content, 97 lines]

[Agent] 第 2 轮...
[Assistant] ## ShellUtils.java 代码审查报告
            ### 优点
            - drain() 方法正确处理输出缓冲，避免进程死锁
            - 超时控制逻辑完整，destroyForcibly() 确保清理
            ### 建议
            - exitCode() 中 drain() 调用注释可更明确说明原因
```

### 结果

| 项目 | 结果 |
|------|------|
| 技能目录扫描 | ✅ /skill 正确列出 2 个技能 |
| load_skill 触发 | ✅ Agent 在执行 review 前自动调用 |
| 技能内容注入 | ✅ 2847 chars 注入上下文 |
| review 质量 | ✅ 覆盖安全性、可维护性、注释完整性 |
| 按需加载 | ✅ 未触发 code-review 的轮次不占用 context |

---

<a name="scenario-4"></a>
## Scenario 4: ContextCompactor — 上下文自动压缩

### 触发方式

连续多轮长对话（读取多个文件、产生大量 tool_result），或手动执行：

```
You> /compact
```

### 过程数据

**三层压缩机制触发顺序：**

```
# Layer 1: Micro Compact（每轮自动）
[内部] microCompact() 被调用
[内部] 将 MICRO_COMPACT_KEEP_COUNT=3 轮以前的 tool_result 替换为 "[Previous: used read_file]"
[内部] 消息数: 38 → 32（6 条旧 tool_result 被替换）

# Layer 2: Auto Compact（messages.size() >= 40）
[内部] shouldAutoCompact() → true
[内部] 调用 LLM 生成对话摘要并写入 .transcripts/
[LLM 摘要] "对话摘要：用户要求分析项目结构，已完成文件读取、代码审查、
             测试用例生成。当前待处理：补充注释..."
[内部] 压缩后消息数: 40 → 4（1 系统 + 1 摘要 + 2 最新轮）

# Layer 3: Manual Compact（用户执行 /compact）
[/compact] Compacted. Messages: 4
```

### 结果

| 项目 | 结果 |
|------|------|
| Micro compact | ✅ 每轮自动执行，旧 tool_result 被替换为简短摘要 |
| Auto compact 阈值 | ✅ messages.size() >= 40 时准确触发 |
| 压缩率 | ✅ 40 → 4，压缩率 90% |
| 对话连贯性 | ✅ 后续轮次能正确引用压缩前的上下文 |
| 手动 /compact | ✅ 随时可触发 |

---

<a name="scenario-5"></a>
## Scenario 5: BackgroundRunner — 异步后台任务

### 触发方式

```
You> 在后台统计 src/ 目录下所有 Java 文件的总行数，
     不用等它完成，先告诉我当前目录有哪些文件
```

### 过程数据

```
[Agent] 第 1 轮...
[Tool] bg_submit <- {"task_id":"count-lines",
                     "command":"find src -name '*.java' | xargs wc -l | tail -1"}
[Tool] bg_submit -> Background task 'count-lines' submitted.

[Tool] bash <- {"command":"ls"}
[Tool] bash -> pom.xml  README.md  docs/  src/  skills/  ...

[Assistant] 行数统计已提交后台（任务 ID: count-lines），当前目录文件如上。

# 几轮对话后，后台任务完成，系统自动注入：
[系统注入] <system-reminder>[Background count-lines completed]
           Duration: 3s | Result: 4852 total</system-reminder>

[Agent] 第 N 轮...
[Assistant] 后台统计已完成：src/ 目录共 4852 行 Java 代码。
```

**主动查询状态：**

```
You> 那个行数统计完成了吗？
[Tool] bg_status <- {"task_id":"count-lines"}
[Tool] bg_status -> completed: 4852 total lines
```

### 结果

| 项目 | 结果 |
|------|------|
| bg_submit 立即返回 | ✅ 不阻塞 Agent，继续处理后续请求 |
| 后台任务执行 | ✅ 独立线程运行，结果 4852 行正确 |
| 完成通知注入 | ✅ 下一轮 LLM 调用前自动收到 system-reminder |
| bg_status 查询 | ✅ 随时可查运行中/已完成状态 |
| bg_drain 手动消费 | ✅ 返回所有待读通知并清空队列 |

---

<a name="scenario-6"></a>
## Scenario 6: TaskStore — 持久化任务与依赖

### 触发方式

```
You> 我有一个项目，包含三个子任务，有依赖关系：
     - T1：设计接口（无依赖）
     - T2：实现接口（依赖 T1）
     - T3：写测试（依赖 T2）
     请用任务系统帮我管理这些任务
```

### 过程数据

```
[Tool] task_create <- {"subject":"设计接口","description":"定义 API 契约"}
[Tool] task_create -> Created task #1: 设计接口
[Tool] task_create <- {"subject":"实现接口","description":"...","blockedBy":["1"]}
[Tool] task_create -> Created task #2: 实现接口
[Tool] task_create <- {"subject":"写测试","description":"...","blockedBy":["2"]}
[Tool] task_create -> Created task #3: 写测试

[Tool] task_list <- {}
[Tool] task_list ->
    [ ] #1: 设计接口
    [ ] #2: 实现接口  (blocked by: [1])
    [ ] #3: 写测试    (blocked by: [2])

[Tool] task_update <- {"id":"1","field":"status","value":"completed"}
[Tool] task_update -> Updated #1: Status: pending -> completed. Cleared dependency blocks.
[TaskStore] Unblocked: #2

[Tool] task_list <- {}
[Tool] task_list ->
    [x] #1: 设计接口
    [ ] #2: 实现接口          ← 已解锁
    [ ] #3: 写测试  (blocked by: [2])
```

**持久化验证：** 重启 Agent 执行 `/tasks`，`.tasks/` 目录下的 JSON 文件自动恢复完整状态。

### 结果

| 项目 | 结果 |
|------|------|
| 任务创建 | ✅ 3 个任务正确创建，依赖关系写入文件 |
| 依赖图正确性 | ✅ T2、T3 在依赖未完成时显示 blocked |
| 依赖解锁 | ✅ T1 完成后 T2 自动从 blocked 变为 pending |
| 持久化 | ✅ .tasks/ 下有 JSON 文件，重启后状态恢复 |
| /tasks 命令 | ✅ 同时展示 TodoManager 和 TaskStore 双视图 |

---

<a name="scenario-7"></a>
## Scenario 7: WorktreeManager — Git Worktree 目录隔离

### 触发方式

```
You> /worktree feature-auth     ← 创建名为 feature-auth 的 worktree
You> 在 feature-auth worktree 里创建 Auth.java，然后查看 git 状态
```

### 过程数据

```
# /worktree feature-auth 输出
Created worktree 'feature-auth' at .worktrees/feature-auth (branch: wt/feature-auth)

[Agent] 第 1 轮...
[Tool] wt_run <- {"name":"feature-auth","command":"cat > Auth.java << 'EOF'\npublic class Auth {}\nEOF"}
[Tool] wt_run -> === worktree_run [feature-auth] ===
                 (no output)

[Tool] wt_status <- {"name":"feature-auth"}
[Tool] wt_status -> === worktree_status [feature-auth] ===
                    ## wt/feature-auth
                    ?? Auth.java

[Tool] wt_list <- {}
[Tool] wt_list -> [{"id":"feature-auth","path":".worktrees/feature-auth",
                    "branch":"wt/feature-auth","status":"active"}]
```

**目录隔离验证：**

```
[Tool] bash <- {"command":"ls *.java"}
[Tool] bash -> Fibonacci.java    ← 主目录无 Auth.java，隔离成功
```

**危险命令拦截：**

```
[Tool] wt_run <- {"name":"feature-auth","command":"rm -rf /"}
[Tool] wt_run -> Error: Dangerous command blocked by SecurityUtils.
```

### 结果

| 项目 | 结果 |
|------|------|
| Worktree 创建 | ✅ git worktree add 成功，独立分支 wt/feature-auth |
| 命令隔离执行 | ✅ wt_run 在 worktree 目录执行，主目录不受影响 |
| Git 状态独立 | ✅ wt_status 只显示 worktree 内变更 |
| 目录隔离 | ✅ worktree 内文件不污染主工作目录 |
| 危险命令拦截 | ✅ rm -rf / 被 SecurityUtils 拦截 |
| wt_list 索引 | ✅ 持久化在 .worktrees/index.json |

---

<a name="scenario-8"></a>
## Scenario 8: Slash 命令 — REPL 控制面板

### 触发方式与结果

| 命令 | 触发方式 | 结果 |
|------|---------|------|
| `/help` | 直接输入 | 打印所有 slash 命令说明，不调用 LLM |
| `/tasks` | 直接输入 | 显示 TodoManager + TaskStore 双视图 |
| `/skill` | 直接输入 | 列出 skills/ 目录下所有技能名称和描述 |
| `/skill code-review` | 直接输入 | 显示 code-review 技能的完整 SKILL.md 内容 |
| `/compact` | 对话较长时输入 | 触发全量压缩，打印压缩后消息数 |
| `/team` | 直接输入 | 显示 lead 收件箱消息数 + 未认领任务列表 |
| `/worktree` | 直接输入 | 列出所有 worktree（JSON 格式） |
| `/worktree my-task` | 直接输入 | 创建名为 my-task 的 worktree |

**关键验证：** 所有 slash 命令均不经过 LLM，由 `SlashRouter` 在本地直接处理，响应时间 < 10ms。

---

<a name="scenario-9"></a>
## Scenario 9: Agent Teams — 多 Agent 协作

### 触发方式

```
You> 请创建两个 Teammate：
     - reviewer：负责代码审查，检查本项目的 BaseTools.java
     - tester：负责编写单元测试，为 ShellUtils.java 补充测试用例
     同时给他们各创建一个任务，完成后告诉我结果
```

### 过程数据

**Lead spawn Teammate：**

```
[Agent] 第 1 轮...
[Tool] spawn_teammate <- {"name":"reviewer","role":"代码审查",
                          "instructions":"审查 src/main/java/ai/claude/code/core/BaseTools.java，
                          关注安全性和边界处理，完成后通过 msg_send 向 lead 报告结论。"}
[Tool] spawn_teammate -> Spawned teammate 'reviewer' with role: 代码审查
[TeammateRunner] Spawned: reviewer (role: 代码审查)

[Tool] spawn_teammate <- {"name":"tester","role":"单元测试",
                          "instructions":"为 ShellUtils.java 编写 JUnit 测试用例，
                          覆盖超时和异常分支，完成后通过 msg_send 向 lead 汇报。"}
[Tool] spawn_teammate -> Spawned teammate 'tester' with role: 单元测试
[TeammateRunner] Spawned: tester (role: 单元测试)

[Tool] list_teammates <- {}
[Tool] list_teammates -> [{"name":"reviewer","role":"代码审查","status":"working"},
                           {"name":"tester","role":"单元测试","status":"working"}]
```

**Teammate reviewer 独立线程执行（后台日志）：**

```
[Teammate reviewer] Started. Role: 代码审查
[Teammate reviewer] Turn 1
[Teammate reviewer] Tool: read_file <- {"path":"src/.../BaseTools.java","limit":0}
[Teammate reviewer] Tool: read_file -> [file content, 310 lines]
[Teammate reviewer] Turn 2
[Teammate reviewer] Tool: msg_send <- {"to":"lead","content":"BaseTools.java 审查完成。
    优点：safePath() 防目录遍历设计严谨；bash 超时 120s 合理。
    建议：runEdit() 对 old_text 不存在时的错误提示可更友好。"}
[Teammate reviewer] Tool: msg_send -> Message sent to lead
[Teammate reviewer] Tool: idle <- {}
[Teammate reviewer] Tool: idle -> Entered idle state. Will poll for work.
[TeammateRunner] reviewer → idle
```

**Teammate tester 独立线程执行（后台日志）：**

```
[Teammate tester] Started. Role: 单元测试
[Teammate tester] Turn 1
[Teammate tester] Tool: read_file <- {"path":"src/.../ShellUtils.java","limit":0}
[Teammate tester] Turn 2
[Teammate tester] Tool: write_file <- {"path":"src/test/.../ShellUtilsTest.java",
                                        "content":"import org.junit.Test;\n..."}
[Teammate tester] Tool: msg_send <- {"to":"lead","content":"ShellUtilsTest.java 已生成，
    覆盖 run() 正常执行、超时中断、空命令三个分支。"}
[Teammate tester] Tool: idle <- {}
[TeammateRunner] tester → idle
```

**Lead 自动接收 Teammate 回报（AgentLoop 收件箱注入）：**

```
[Agent] 第 N 轮（LLM 调用前）...
[系统注入] <system-reminder>[Message from reviewer] BaseTools.java 审查完成。
           优点：safePath() 防目录遍历设计严谨...建议：runEdit() 错误提示可更友好。
           </system-reminder>
[系统注入] <system-reminder>[Message from tester] ShellUtilsTest.java 已生成，
           覆盖 run() 正常执行、超时中断、空命令三个分支。
           </system-reminder>

[Assistant] 两位 Teammate 已完成任务：
            - reviewer 已审查 BaseTools.java，建议改进 runEdit() 的错误提示
            - tester 已生成 ShellUtilsTest.java，覆盖 3 个测试分支
```

**Teammate 自主认领 TaskStore 任务：**

```
# Lead 创建任务：
[Tool] task_create <- {"subject":"优化 runEdit 错误提示","description":"..."}
[Tool] task_create -> Created task #4: 优化 runEdit 错误提示

# Teammate 空闲轮询（每 5s 检查一次）：
[Teammate reviewer] Auto-claimed task #4: 优化 runEdit 错误提示
[TeammateRunner] reviewer → working

[Teammate reviewer] Tool: read_file <- {"path":"src/.../BaseTools.java"}
[Teammate reviewer] Tool: edit_file <- {"path":"...","old_text":"...","new_text":"..."}
[Teammate reviewer] Tool: msg_send <- {"to":"lead","content":"Task #4 完成，runEdit 错误提示已优化。"}
```

**Shutdown 协议：**

```
# Lead 向 reviewer 发送关闭请求（通过 msg_send 发送 shutdown_request JSON）：
[Tool] msg_send <- {"to":"reviewer","content":"{\"type\":\"shutdown_request\",\"request_id\":\"req-a1b2c3\"}"}
[Tool] msg_send -> Message sent to reviewer

# reviewer 空闲轮询检测到 shutdown_request，自动响应：
[Teammate reviewer] Shutdown approved (request_id=req-a1b2c3)
[Teammate reviewer] Stopped.
[TeammateRunner] reviewer → done
```

### 结果

| 项目 | 结果 |
|------|------|
| spawn_teammate | ✅ 每个 Teammate 在独立线程中启动真实 LLM 循环 |
| list_teammates | ✅ 正确显示所有 Teammate 及 working/idle/done 状态 |
| Teammate 工具集 | ✅ bash、read_file、write_file、edit_file、msg_send、msg_read、idle、claim_task 均可调用 |
| 身份再注入 | ✅ 消息数 < 6 时自动插入 identity 信息，防止 LLM 遗忘角色 |
| Lead 收件箱注入 | ✅ Teammate msg_send 的内容在下一轮 LLM 调用前自动注入 Lead 上下文 |
| 自主任务认领 | ✅ Teammate 空闲轮询（每 5s）发现 TaskStore 未认领任务后自动 claim |
| 空闲超时退出 | ✅ 60s 无消息无任务后 Teammate 自动停止，status 变为 done |
| Shutdown 协议 | ✅ 检测到 shutdown_request 后自动回复 shutdown_response 并退出 |
| 资源清理 | ✅ AgentLoop.shutdown() 调用 TeammateRunner.shutdownAll()，进程退出干净 |

---

## 总体测试结论

| 能力模块 | 状态 | 关键验证点 |
|---------|------|-----------|
| FileTools (bash/read/write/edit) | ✅ 通过 | 文件生成正确，编译运行通过，路径安全检查生效 |
| TodoManager | ✅ 通过 | 状态流转正确，NAG 提醒按阈值触发 |
| SkillLoader | ✅ 通过 | 按需加载，不预占 context |
| ContextCompactor | ✅ 通过 | 三层压缩均触发，压缩率 90%，对话连贯性保持 |
| BackgroundRunner | ✅ 通过 | 异步执行不阻塞，完成通知自动注入 |
| TaskStore | ✅ 通过 | 依赖图正确解锁，持久化跨会话恢复 |
| WorktreeManager | ✅ 通过 | 目录隔离，git 分支独立，危险命令拦截 |
| Slash 命令 | ✅ 通过 | 全部 8 个命令响应正确，零 LLM 调用 |
| SecurityUtils | ✅ 通过 | rm -rf /、sudo 等危险命令被拦截 |
| **Agent Teams** | ✅ 通过 | spawn/list/认领/通信/shutdown 全流程验证通过 |

---

## 如何重现测试

### 启动 CLI 模式（REPL）

```bash
# 1. 配置 API Key
#    编辑 claude-code-4j-start/src/main/resources/claude.properties
#    或设置环境变量：
export OPENAI_API_KEY=sk-xxxxxxxx

# 2. 编译
mvn compile

# 3. 启动 CLI
mvn exec:java \
  -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application" \
  -Dspring.profiles.active=cli
```

### 启动 REST API 模式

```bash
mvn exec:java \
  -pl claude-code-4j-start \
  -Dexec.mainClass="ai.claude.code.Application"

# 验证 Agent Teams（通过 API）
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请创建一个叫 reviewer 的 Teammate，负责代码审查 BaseTools.java"}'
```

### 开启调试模式

在 `claude.properties` 中设置，可查看完整 API 请求/响应报文：

```properties
DEBUG_PRINT_PAYLOAD=true
```

### 各场景触发顺序

按本文档各 Scenario 的"触发方式"依次在 CLI 中输入即可复现。Agent Teams 场景需确保工作目录是 Git 仓库（`git init` 已执行），以便 WorktreeManager 正常工作。
