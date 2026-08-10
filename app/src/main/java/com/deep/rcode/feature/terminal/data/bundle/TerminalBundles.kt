package com.deep.rcode.feature.terminal.data.bundle

/**
 * 功能包 Bundle 的稳定标识。
 *
 * 与 [TerminalBundle.packages]（具体 apk 包名）解耦：
 * - 升级包内容（例如把 `python3` 拆成 `python3 + py3-pip`）时只改 Bundle 定义，
 *   BundleId 保持不变，用户侧的「已安装」标记不需要重新识别。
 * - 用作 DataStore / File 标记文件名的一部分，必须是稳定字符串。
 */
enum class TerminalBundleId(val stableKey: String) {
    PYTHON("python"),
    NODE("node"),
    RIPGREP("rg"),
    GIT("git"),
    BASH("bash"),
    NET("net"),
    ;

    companion object {
        fun fromKey(key: String): TerminalBundleId? = entries.firstOrNull { it.stableKey == key }
    }
}

/** 一个 Bundle 的安装状态。由 [TerminalBundleRepository] 发出 StateFlow 供 UI 订阅。 */
sealed interface BundleInstallState {
    /** 未安装，且没有正在进行的操作。 */
    data object NotInstalled : BundleInstallState

    /** 正在安装。[line] 为 apk 输出行（可为 null，UI 此时只显示进度条转圈）。 */
    data class Installing(val line: String? = null) : BundleInstallState

    /** 安装失败。[reason] 为错误信息。 */
    data class Failed(val reason: String) : BundleInstallState

    /** 已安装，[installedVersion] 为当前 bundle 定义版本号（用于升级时触发重装）。 */
    data class Installed(val installedVersion: Int) : BundleInstallState

    /** 正在卸载。 */
    data object Uninstalling : BundleInstallState
}

/**
 * 功能包 Bundle 定义。
 *
 * 这是「用户层」的概念（「装 Python 运行时」），而不是「apk 包层」（装 python3/py3-pip）。
 * UI 按 [displayName]/[iconRes]/[description]/[sizeEstimateMb] 给用户卡片，
 * 引擎按 [packages] 去执行 apk。
 */
data class TerminalBundle(
    val id: TerminalBundleId,
    val displayName: String,
    val iconName: String, // 用 Feather icon name，字符串存便于 DataBinding/预览映射
    val description: String,
    /** 预估大小（MB）。UI 卡片展示用，不是精确值。 */
    val sizeEstimateMb: Int,
    /** 实际执行 `apk add` 的包名，空格分隔。变更时 +1 [version] 触发存量设备重装。 */
    val packages: String,
    /** 该 bundle 的配置版本：每改 [packages]、provision 逻辑 +1，触发重新 apk add。 */
    val version: Int,
    /** AI 推荐组合一键安装是否勾选该 bundle。 */
    val includedInAiRecommended: Boolean,
    /** 安装后需执行的一次性 hook shell（例如切默认 shell、写 git 配置）。可 null。 */
    val postInstallHook: String? = null
)

/**
 * 7 个内置 Bundle 清单。改包/版本直接改这里；注意改了 [packages]/hook 同步 +1 version。
 * 版本基线（均为 Alpine 3.21 arm64）：
 *   - python3  3.12.x (~40MB) + py3-pip (~5MB)
 *   - nodejs 22.x LTS (~22MB) + npm (~6MB)
 *   - ripgrep 14.x (~4MB)
 *   - git 2.47 + git-credential-store helper (~22MB + helper 脚本)
 *   - bash 5.2 + less + ncurses (~5MB)
 *   - curl + wget + ca-certificates (~3MB)
 */
object TerminalBundles {

    /** AI 推荐组合（一键安装）包含的 bundles。 */
    val AI_RECOMMENDED_IDS: Set<TerminalBundleId> = setOf(
        TerminalBundleId.PYTHON,
        TerminalBundleId.RIPGREP,
        TerminalBundleId.GIT,
        TerminalBundleId.BASH,
        TerminalBundleId.NET
    )

    val ALL: List<TerminalBundle> = listOf(
        TerminalBundle(
            id = TerminalBundleId.PYTHON,
            displayName = "Python 运行时",
            iconName = "code",
            description = "AI 执行 Python 代码块必需；内置 pip 用于安装项目依赖。",
            sizeEstimateMb = 45,
            packages = "python3 py3-pip",
            version = 2,
            includedInAiRecommended = true,
            postInstallHook = """
            # ============================================================
            # RC61c S2 Fix: Python pip 安装的三链路兜底
            # 背景（截图事故）：
            #   Alpine v3.21 community 仓库有时镜像刷新/地区节点差异导致 py3-pip
            #   查无此包（截图显示：apk ERROR: py3-pip (no such package) 且
            #   WARNING: fetching http://mirrors.aliyun.com/alpine/v3.21/community: IO ERROR）。
            # 三链路兜底（任何一条成功就退出，保证 python3 -m pip 永远可用）：
            #   [1] apk info -e py3-pip 已装 OK → 直接返回
            #   [2] apk add --no-cache py3-pip 成功 → OK
            #   [3] python3 -m ensurepip --upgrade（Python 官方内置；不依赖外部仓库）
            #   [4] curl/wget https://bootstrap.pypa.io/get-pip.py → python3 get-pip.py
            # ============================================================
            set +e
            pip_ok=0
            # ---------- 链路 1&2：apk 里真的有 py3-pip（健康节点通常能命中） ----------
            if command -v apk >/dev/null 2>&1; then
              if apk info -e py3-pip >/dev/null 2>&1; then
                echo "[pip] 链路1 命中：py3-pip 已安装，OK"
                pip_ok=1
              else
                echo "[pip] 链路2：尝试 apk add py3-pip（依赖 community 仓库）"
                apk add --no-cache py3-pip >/tmp/py3_pip_apk.log 2>&1
                if [ $? -eq 0 ] && apk info -e py3-pip >/dev/null 2>&1; then
                  echo "[pip] 链路2 成功"
                  pip_ok=1
                else
                  echo "[pip] 链路2 失败，apk 日志末 3 行："
                  tail -n 3 /tmp/py3_pip_apk.log 2>/dev/null || true
                fi
              fi
            fi
            # ---------- 链路 3：ensurepip（Python 官方内置，不依赖 apk 仓库） ----------
            if [ "$pip_ok" -eq 0 ]; then
              echo "[pip] 链路3：python3 -m ensurepip --upgrade（不依赖 Alpine 仓库）"
              python3 -m ensurepip --upgrade >/tmp/py3_ensurepip.log 2>&1
              rc=$?
              if command -v pip3 >/dev/null 2>&1 || python3 -m pip --version >/dev/null 2>&1; then
                echo "[pip] 链路3 成功 (ensurepip rc=$rc)"
                pip_ok=1
              else
                echo "[pip] 链路3 失败，ensurepip 日志末 3 行："
                tail -n 3 /tmp/py3_ensurepip.log 2>/dev/null || true
              fi
            fi
            # ---------- 链路 4：get-pip.py（终极兜底，PyPA 官方，只依赖能联网下载 1.8MB） ----------
            if [ "$pip_ok" -eq 0 ]; then
              echo "[pip] 链路4：下载 PyPA get-pip.py 终极安装（失败 3 次停止不占网）"
              GP_URL="https://bootstrap.pypa.io/get-pip.py"
              GP_TMP="/tmp/get-pip.py"
              got=0
              for i in 1 2 3; do
                rm -f "$GP_TMP"
                if command -v curl >/dev/null 2>&1; then
                  curl -fsSL "$GP_URL" -o "$GP_TMP" >/dev/null 2>&1
                elif command -v wget >/dev/null 2>&1; then
                  wget -q "$GP_URL" -O "$GP_TMP" >/dev/null 2>&1
                else
                  echo "[pip] 链路4 中止：容器内无 curl/wget"
                  break
                fi
                if [ -s "$GP_TMP" ] && [ "$(wc -c < "$GP_TMP")" -gt 4096 ]; then
                  got=1
                  break
                fi
                sleep 1
              done
              if [ "$got" -eq 1 ]; then
                python3 "$GP_TMP" >/tmp/py3_getpip.log 2>&1
                if command -v pip3 >/dev/null 2>&1 || python3 -m pip --version >/dev/null 2>&1; then
                  echo "[pip] 链路4 成功"
                  pip_ok=1
                else
                  echo "[pip] 链路4 失败，get-pip 日志末 3 行："
                  tail -n 3 /tmp/py3_getpip.log 2>/dev/null || true
                fi
              fi
              rm -f "$GP_TMP"
            fi
            # ---------- 最终输出 pip version ----------
            if [ "$pip_ok" -eq 1 ]; then
              echo "[pip] 三链路兜底成功。pip 版本："
              python3 -m pip --version 2>&1 || pip3 --version 2>&1 || true
              # 顺手升到比较稳定的 pip 24.x（只升一次，失败不影响 pip 存在）
              python3 -m pip install --upgrade 'pip<25' >/dev/null 2>&1 || true
              exit 0
            else
              echo "[pip] 三链路全部失败（可能容器网络未就绪），本次 Python bundle 仍算装成"
              echo "[pip] （后续用户手动运行：apk add py3-pip 或 python3 -m ensurepip）"
              exit 0
            fi
            """.trimIndent()
        ),
        TerminalBundle(
            id = TerminalBundleId.NODE,
            displayName = "Node.js 运行时",
            iconName = "box",
            description = "执行 JS/TS 与 npm 包必需。AI 运行前端项目/脚手架时使用。",
            sizeEstimateMb = 28,
            packages = "nodejs npm",
            version = 1,
            includedInAiRecommended = false
        ),
        TerminalBundle(
            id = TerminalBundleId.RIPGREP,
            displayName = "高速搜索 (rg)",
            iconName = "search",
            description = "ripgrep 是全文搜索/代码搜索/日志搜索的底层引擎，速度比 grep 快一个数量级。",
            sizeEstimateMb = 4,
            packages = "ripgrep",
            version = 1,
            includedInAiRecommended = true
        ),
        TerminalBundle(
            id = TerminalBundleId.GIT,
            displayName = "Git",
            iconName = "git-branch",
            description = "工作区 git 操作 / 克隆项目 / 提交 / 推送。附赠 git-credential-store 凭证助手。",
            sizeEstimateMb = 22,
            packages = "git",
            version = 2,
            includedInAiRecommended = true,
            postInstallHook =
            """
            # 配置 credential.helper=store：把 ~/.git-credentials 放在宿主共享目录
            # /data/data/<pkg>/files/git/ 下（由 ContainerEngine 参数 -H /root/.git-credentials -> 该路径映射），
            # 跨 rootfs 升级不丢。
            git config --global credential.helper store 2>/dev/null || true
            """.trimIndent()
        ),
        TerminalBundle(
            id = TerminalBundleId.BASH,
            displayName = "Bash 环境",
            iconName = "terminal",
            description = "把默认 shell 从 busybox ash 换成 bash，支持 !! 历史、Tab 补全、彩色 PS1、less 分页。",
            sizeEstimateMb = 5,
            packages = "bash less ncurses",
            version = 1,
            includedInAiRecommended = true,
            postInstallHook =
            """
            # 把 /etc/passwd 中 root 的 shell 从 /bin/sh 改成 /bin/bash
            # （busybox adduser 的默认 sh；sed 一次到位）。
            if grep -q '^root:' /etc/passwd 2>/dev/null; then
              sed -i 's|^root:\([^:]*\):\([^:]*\):\([^:]*\):\([^:]*\):\([^:]*\):\([^:]*\):/bin/sh$|root:\1:\2:\3:\4:\5:\6:/bin/bash|' /etc/passwd 2>/dev/null || true
            fi
            # 新用户默认 shell / 兜底环境变量
            [ -f /etc/default/useradd ] || echo 'SHELL=/bin/bash' > /etc/default/useradd 2>/dev/null || true
            # PS1 前缀钩子：无论 .bashrc 里原先 PS1 如何设定，都在最前面加上 "> "
            # 用 PROMPT_COMMAND 确保渲染前补前缀；去重函数防止嵌套补两次
            mkdir -p /root
            cat >> /root/.bashrc <<'BASHRC_EOF'
            __apply_prompt_prefix() {
              case "${'$'}PS1" in
                '> '*) : ;;
                *) PS1='> '${'$'}PS1 ;;
              esac
            }
            case ";${'$'}{PROMPT_COMMAND:-};" in
              *";__apply_prompt_prefix;"*) : ;;
              *) PROMPT_COMMAND="__apply_prompt_prefix${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;;
            esac
            BASHRC_EOF
            # 系统级兜底（其他用户 / 非交互登录）
            cat >> /etc/profile <<'PROFILE_EOF'
            __apply_prompt_prefix() {
              case "${'$'}PS1" in
                '> '*) : ;;
                *) PS1='> '${'$'}PS1 ;;
              esac
            }
            case ";${'$'}{PROMPT_COMMAND:-};" in
              *";__apply_prompt_prefix;"*) : ;;
              *) PROMPT_COMMAND="__apply_prompt_prefix${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;;
            esac
            PROFILE_EOF
            """.trimIndent()
        ),
        TerminalBundle(
            id = TerminalBundleId.NET,
            displayName = "网络工具",
            iconName = "globe",
            description = "curl / wget / ca-certificates。脚本联网、API 测试、下载文件都需要。",
            sizeEstimateMb = 3,
            packages = "curl wget ca-certificates",
            version = 1,
            includedInAiRecommended = true
        )
    )

    fun byId(id: TerminalBundleId): TerminalBundle? = ALL.firstOrNull { it.id == id }
}
