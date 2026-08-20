# 命令执行防死循环与 BusyBox 兼容护栏 — Design

> 📝 草案（已实施待评审）
>
> 关联模块：`feature/agent`（`tool/container/`）
> 触发场景：AI 在容器内写出 GNU 专属语法 + 无界循环并塞进常驻终端，导致无限刷屏/空耗。

## 1. 背景与问题

真实事故：AI 执行网络探测时写了：

```sh
while true; do echo | nc -q 1 host port; done
```

- `nc -q` 是 **GNU/OpenBSD netcat** 的「EOF 后等待」选项，**BusyBox nc 不支持**（只有 `-w`）。每次调用立即报 `nc: unrecognized option: q` + 打印 Usage 并以非 0 退出；
- 外层 `while true` 是**无界循环**，失败即重试，永不结束；
- 且该命令被塞进 `terminal(action="start", notify=false)` **常驻终端**——该模式**没有超时**，命令持续运行直到手动停止。

三者叠加 = 无限刷屏、空耗 CPU，只能手动停止。

根因分两层：
1. **AI 缺少容器命令兼容知识**：容器是 Alpine（BusyBox），不是 GNU/Linux，写命令未做 POSIX/BusyBox 适配。
2. **缺少执行护栏**：`Bash` 有 120s 超时兜底但默认较长；`terminal start` 常驻模式无任何超时/拦截。

## 2. 方案设计（分层预防）

### 层 1：提示词约束（治本，约束 AI 行为）

`app/src/main/assets/prompts/60-tools-and-paths.md` 新增「容器命令兼容性与执行纪律」小节：

- **BusyBox/POSIX 兼容**：给出常用 GNU → BusyBox 对照表（`nc -q`→`nc -w`/`wget -T`/`curl --connect-timeout`、`grep -P \d \s`→`grep -E`、`awk \x{...}`→字节判断、`date -d`、`head -n -N` 等）。
- **严禁无界循环**：`while true`/`while :`/`until false`/`for ((;;))`；需要等待/轮询必须有限次数 + `sleep` + 明确退出条件。
- **`terminal(action="start")` 常驻模式没有超时**：只用于真正常驻服务；一次性/探测/循环/构建等会自行结束的任务用 `Bash`（有超时）或 `notify=true`。
- **失败先修根因，不要盲目重试**：连续失败 2 次换方法或问用户。

### 层 2：代码护栏（堵漏）

新增 `feature/agent/domain/tool/container/CommandLoopGuard.kt` + `BusyBoxCompatibilityGuard.kt`：

**`CommandLoopGuard`（拦截型）**
- `hasUnboundedLoop(command)`：正则检测无界循环模式 `while true` / `while :` / `while [ 1 ]` / `while [[ 1 ]]` / `until false` / `for ((;;))`（bash 双括号语法，须匹配 `\(\(...\)\)`）。
- `isForkBomb(command)`：检测经典 fork bomb `:(){ :|:& };:`（去空白后匹配）。
- **刻意不命中有界/条件循环**：`while read`（管道/文件逐行，EOF 结束）、`while [ -f x ]` / `until 条件`（条件满足即退出）、`for i in ...`（有限集合）、`for (( i=0; i<10; i++ ))`（有终止条件），避免误伤正常用法。
- `GUARDED_TIMEOUT_SECONDS = 30`：命中无界循环后 Bash 工具强制钳制的超时。

**`BusyBoxCompatibilityGuard`（预警型）**
- `issues(command)`：识别 BusyBox 不支持的 GNU 专属参数（高置信度，对照 BusyBox 帮助确认无此选项）：`nc -q` / `grep -P` / grep 模式 `\d\s\w`（PCRE 转义）/ `find -printf` / `xargs -d` / `cp --parents`。
- `hangRiskHints(command)`：`ping` 未带 `-c|-w` 次数/时限会一直 ping 的提醒。
- `warningMessage(command)` / `appendHint(...)`：把提示拼进权限卡 `details` 与工具结果末尾，让 AI 看到并修正写法。

接入点：

| 工具 | 行为 | 理由 |
|---|---|---|
| `Bash`（`ExecuteCommandTool`） | 命中无界循环 → 超时钳制为 30s；命中 fork bomb → **直接拒绝**；命中 BusyBox 不兼容/无限 ping → 提示拼进结果 + 权限卡警告 | Bash 已有超时强制终止，钳制缩短刷屏窗口即可，不误伤合法命令；fork bomb 经 `&` 自复制超时不可靠，必须拒绝；兼容性问题预警让 AI 自动改写法 |
| `terminal(action="start")`（`TerminalSessionTool`） | 命中无界循环 / fork bomb → **直接拒绝启动**并提示改用 Bash；命中 BusyBox 不兼容 → 权限卡警告 + 结果末尾提示 | 常驻模式无超时，无法兜底，必须拦截 |

### 层 3：文档与验证

- `docs/modules/agent.md` §3.3 记录行为变化。
- 编译验证 `./gradlew :app:assembleDebug`。

## 3. 决策与取舍

- **为何 Bash 不直接拒绝无界循环？** Bash 有超时兜底（30s 必停），且无界循环存在少量正当用法（如用户想跑一个有限时间窗口的循环）；钳制超时 + 权限卡提示是「限制而非阻断」，误伤面最小。
- **为何 terminal 常驻直接拒绝？** 常驻模式语义就是"一直运行到手动停止"，无任何超时兜底，无界循环在此 = 事故必然复现；拒绝并引导到 Bash 是唯一可靠的拦截点。
- **fork bomb 为何两者都拒绝？** fork bomb 经 `&` 后台自复制进程脱离 shell，超时只能杀掉父 shell，无法终止已脱离的子进程，会持续耗尽容器 CPU/内存——只能前置拦截。
- **BusyBox 兼容性问题为何只预警不拦截？** 规则只收录高置信度 GNU-only 参数，但命令可能因多层包装/变量而误判；且这类命令多数自身就会报错（如 `nc: unrecognized option: q`）。预警（权限卡 + 结果末尾提示）让 AI 看到并主动改写法，比一刀切拦截误伤面更小。`date -d`/`sed -i.bak`/`head -n -N` 等 BusyBox 部分版本其实支持，故**不**进静态规则，只留在提示词对照表，避免误报。
- **检测只覆盖"无条件循环"**：`while [ -f x ]` 等条件循环理论上也可能不结束（条件一直为真），但误伤风险高（大量 `while [ -f ]` 等待文件出现的合法脚本），本护栏不做激进拦截，交给提示词约束与 Bash 超时兜底。

## 4. 后续可扩展（非本期）

- 对 `terminal(start, notify=false)` 增加「常驻时长上限」或「输出量上限」自动停止（涉及终端会话模型，改动面大，暂缓）。
- 更完整的 BusyBox 命令 lint（解析命令并匹配更多已知 GNU-only 参数），可作为 `check_environment` 或独立护栏工具演进。
- 对 `terminal(action="send")` 的输入也做同样的护栏检查（当前仅覆盖 `start`，send 因交互场景多暂不拦截）。
- 动态兜底：给常驻终端会话增加「N 分钟内无输出自动提示/停止」的软护栏。
