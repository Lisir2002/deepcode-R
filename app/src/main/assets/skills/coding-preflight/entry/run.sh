#!/bin/sh
# coding-preflight 技能入口：编程前准备体检。自动采集环境/仓库/项目结构快照，输出就绪判定与计划建议。
# 契约：SKILL_PROJECT_PATH 为容器内项目路径（宿主导入 /root/workspace，见 SkillExecutor）；
#       SKILL_ARG_TASK 为可选任务描述（由 loadSkill 的 args 注入，见 SkillExecutor）。
# 只读，不改任何文件；有阻断项（R-*）时退出码非 0。
#
# 兼容性：目标运行环境为 Alpine（busybox ash / busybox grep / busybox awk / busybox sed）。
#   严禁使用 gawk 专属正则（\x{...}、\s、\d）与 GNU 扩展，避免解析即崩。
# 需容器内具备: git / grep / sed / awk（内置 Alpine 已含）。个别特性缺失时降级为部分采集。

set -u
export LC_ALL=C.UTF-8 2>/dev/null || export LC_ALL=en_US.UTF-8 2>/dev/null || export LC_ALL=C

P="${SKILL_PROJECT_PATH:-}"
TASK="${SKILL_ARG_TASK:-}"
BLOCKERS=0
WARNINGS=0
ROOT=""
IS_GIT=false
IS_ANDROID=false
# 管道子 shell 无法回写外层计数，用临时文件累计阻断项/建议项（mktemp 失败时回退固定路径）。
TMPB="$(mktemp 2>/dev/null || echo /tmp/cpf.blockers)"
TMPW="$(mktemp 2>/dev/null || echo /tmp/cpf.warnings)"
: > "$TMPB"
: > "$TMPW"

echo "================ 编程前准备报告 ================"

# ---- 定位仓库根 ----
if [ -n "$P" ] && [ -d "$P" ]; then
  if command -v git >/dev/null 2>&1; then
    ROOT="$(git -C "$P" rev-parse --show-toplevel 2>/dev/null || true)"
    if [ -n "$ROOT" ]; then IS_GIT=true; fi
  fi
fi
if [ -n "$ROOT" ]; then
  echo "[项目] 仓库根: $ROOT"
else
  ROOT="$P"
  [ -z "$ROOT" ] && ROOT="/root/workspace"
  echo "[项目] 路径: $ROOT（未识别为 git 仓库，以下仅做静态快照）"
fi

# ---- 任务描述 ----
if [ -n "$TASK" ]; then
  echo "[任务] $TASK"
else
  echo "[任务] （未提供任务描述，建议先向用户澄清需求再拆解）"
  echo "⚠️  [W-5] 未提供任务描述，建议先用询问工具澄清用户需求与验收标准再动手" 
  echo w >> "$TMPW"
fi

# ---- 项目类型识别（B1 分层）：影响 Android 专属资产同步提示 ----
if [ -f "$ROOT/app/build.gradle.kts" ] || [ -f "$ROOT/app/build.gradle" ]; then
  IS_ANDROID=true
  echo "[类型] Android 项目（应用 Gradle 构建）"
else
  echo "[类型] 非 Android 项目（前端/后端/脚本仓库等）"
fi

# ---- P-1 环境组件快照：按项目栈推断关键构建组件并探测（Git 始终探测） ----
# busybox/dash 兼容：命令替换内不要直接嵌套带引号的 "$2"/"$3"（部分 sh 解析会崩），
# 先转存到局部变量再拼命令；command -v + 取版本号首行；不可用则标 missing。
probe() { # $1=显示名  $2=命令名  $3=可选版本参数
  name="$1"; cmd="$2"; opt="$3"
  if command -v "$cmd" >/dev/null 2>&1; then
    ver=""
    if [ -n "$opt" ]; then
      ver="$($cmd "$opt" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^.* version /v/;s/^(GNU coreutils) //')"
    else
      ver="$($cmd 2>/dev/null | head -1 | tr -d '\r' | sed 's/^.* version /v/;s/^(GNU coreutils) //')"
    fi
    [ -z "$ver" ] && ver="installed"
    echo "$name=installed($ver)"
  else
    echo "$name=missing"
  fi
}
ENV_LINE="[环境] $(probe Git git --version)"
if $IS_ANDROID; then
  ENV_LINE="$ENV_LINE | $(probe Java java -version) | $(probe Gradle gradle --version) | $(probe AndroidSDK sdkmanager --version)"
else
  if [ -f "$ROOT/package.json" ]; then
    ENV_LINE="$ENV_LINE | $(probe Node node --version) | $(probe Npm npm --version)"
  fi
  if [ -f "$ROOT/go.mod" ]; then
    ENV_LINE="$ENV_LINE | $(probe Go go version)"
  fi
  if [ -f "$ROOT/Cargo.toml" ]; then
    ENV_LINE="$ENV_LINE | $(probe Cargo cargo --version)"
  fi
  if [ -f "$ROOT/pom.xml" ]; then
    ENV_LINE="$ENV_LINE | $(probe Java java -version) | $(probe Maven mvn --version)"
  fi
  if [ -f "$ROOT/requirements.txt" ] || [ -f "$ROOT/Pipfile" ]; then
    ENV_LINE="$ENV_LINE | $(probe Python python3 --version)"
  fi
fi
echo "$ENV_LINE"
# R-1 环境缺关键组件判定（Android 关注 Java/Gradle，其余关注按栈探测到的 missing）
if $IS_ANDROID; then
  if ! command -v java >/dev/null 2>&1 || ! command -v gradle >/dev/null 2>&1; then
    echo "❌ [R-1] Android 项目缺少关键构建组件（Java/Gradle 有 missing）——先安装再写代码，避免写完构建失败"
    echo b >> "$TMPB"
  fi
else
  # 非 Android：仅当探测到项目栈对应工具缺失时提示（避免无关组件误报）
  for c in node npm go cargo mvn python3; do
    if ! command -v "$c" >/dev/null 2>&1; then
      # 仅当项目内有该栈标志文件时才视为缺失
      case "$c" in
        node|npm)  [ -f "$ROOT/package.json" ] && { echo "❌ [R-1] 项目含 package.json 但缺 $c ——先安装 Node 工具链再写代码"; echo b >> "$TMPB"; } ;;
        go)        [ -f "$ROOT/go.mod" ] && { echo "❌ [R-1] 项目含 go.mod 但缺 $c ——先安装 Go 工具链再写代码"; echo b >> "$TMPB"; } ;;
        cargo)     [ -f "$ROOT/Cargo.toml" ] && { echo "❌ [R-1] 项目含 Cargo.toml 但缺 $c ——先安装 Rust 工具链再写代码"; echo b >> "$TMPB"; } ;;
        mvn)       [ -f "$ROOT/pom.xml" ] && { echo "❌ [R-1] 项目含 pom.xml 但缺 $c ——先安装 Maven 工具链再写代码"; echo b >> "$TMPB"; } ;;
        python3)   { [ -f "$ROOT/requirements.txt" ] || [ -f "$ROOT/Pipfile" ]; } && { echo "❌ [R-1] 项目含 Python 依赖文件但缺 $c ——先安装 Python 再写代码"; echo b >> "$TMPB"; } ;;
      esac
    fi
  done
fi

# ---- P-2 仓库状态 ----
if $IS_GIT; then
  BR="$(git -C "$ROOT" branch --show-current 2>/dev/null || true)"
  if [ -z "$BR" ]; then
    echo "❌ [R-3] 当前为游离 HEAD（detached），先 checkout 到目标分支再开工，防新提交丢失"
    echo b >> "$TMPB"
    BR="(detached)"
  fi
  if [ -d "$ROOT/.git/MERGE_HEAD" ] || [ -d "$ROOT/.git/rebase-merge" ] || [ -d "$ROOT/.git/rebase-apply" ] \
     || [ -f "$ROOT/.git/CHERRY_PICK_HEAD" ] || [ -f "$ROOT/.git/REVERT_HEAD" ]; then
    echo "❌ [R-2] 仓库处于 merge/rebase/cherry-pick/revert 中间操作，先完成（--continue）或中止（--abort）再开始新任务"
    echo b >> "$TMPB"
  fi
  # 未提交改动（已暂存/未暂存/未跟踪）
  STG="$(git -c core.quotepath=false -C "$ROOT" diff --cached --name-only 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  UST="$(git -c core.quotepath=false -C "$ROOT" diff --name-only 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  UNTR="$(git -c core.quotepath=false -C "$ROOT" ls-files --others --exclude-standard 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  TOTAL=$((STG+UST+UNTR))
  echo "[仓库] 分支: $BR（未提交改动: 已暂存 $STG / 未暂存 $UST / 未跟踪 $UNTR，共 $TOTAL）"
  if [ "$TOTAL" -gt 0 ]; then
    echo "❌ [R-4] 工作区有 $TOTAL 个未提交改动——与用户确认是否属同一主题，否则先 stash/提交，避免新旧混杂"
    echo b >> "$TMPB"
  fi
  # R-5 错误分支：功能/重构任务（无 task 时跳过，按经验提示）
  if [ "$BR" = "main" ] || [ "$BR" = "master" ]; then
    echo "⚠️  [R-5] 当前在 $BR 分支；若本次为功能/重构且改动面较大，建议先 git checkout -b feat/xxx 再开工"
    echo w >> "$TMPW"
  fi
  echo "[仓库] 最近提交: $(git -C "$ROOT" log -1 --format='%h %s' 2>/dev/null || echo 无)"
else
  echo "[仓库] 非 git 仓库，跳过仓库状态检查"
fi

# ---- P-3 项目结构与模块清单（Android） ----
if $IS_ANDROID; then
  FEAT="$(ls -1 "$ROOT/app/src/main/java/com/R/codecore/feature" 2>/dev/null | grep -v '^$' | tr '\n' ' ' | sed 's/ $//')"
  if [ -n "$FEAT" ]; then
    echo "[结构] feature 模块: $FEAT"
    # 检查各模块文档是否存在（仅提示缺失）
    for m in $FEAT; do
      if [ ! -f "$ROOT/docs/modules/$m.md" ]; then
        echo "⚠️  [W-2] feature/$m/ 缺少 docs/modules/$m.md（涉及该模块改动时需先读/补模块文档）"
        echo w >> "$TMPW"
      fi
    done
  fi
  for d in docs/modules docs/plan-docs app/src/main/assets/prompts app/src/main/assets/docs; do
    [ -d "$ROOT/$d" ] && echo "[结构] $d 存在" || echo "[结构] $d 缺失"
  done
else
  echo "[结构] 非 Android 项目：列出根目录顶层（省略 .git）"
  ls -1 "$ROOT" 2>/dev/null | grep -v '^\.git$' | head -20 | sed 's/^/  - /'
fi

# ---- P-4 关键文件探测 ----
# AGENTS.md（AI 纪律源）/ README.md（项目说明）缺失时升级为建议项（W-6/W-7），
# 引导先补齐纪律与说明文档再开工；.gitignore/.gitattributes 仅作快照展示。
for f in AGENTS.md .gitignore .gitattributes README.md; do
  if [ -f "$ROOT/$f" ]; then
    echo "[文件] $f 存在"
  else
    echo "[文件] $f 缺失"
    case "$f" in
      AGENTS.md)
        echo "⚠️  [W-6] 纪律文档 AGENTS.md 缺失——建议先创建（含边界规则 Always/Ask First/Never、提交规范、资产同步纪律）再开始写码，AI 才能依纪律行事"
        echo w >> "$TMPW" ;;
      README.md)
        echo "⚠️  [W-7] 说明文档 README.md 缺失——建议补一份项目说明（技术栈/运行方式/接口约定），便于理解与交接"
        echo w >> "$TMPW" ;;
    esac
  fi
done

# ---- 汇总 ----
echo "---------------- 就绪判定 ----------------"
echo "❌ 阻断项: $(wc -l < "$TMPB" | tr -d ' ')   ⚠️ 建议项: $(wc -l < "$TMPW" | tr -d ' ')"
echo ""
echo "================ 计划建议 ================"
echo "1) 任务理解：复述需求、识别目标文件/模块，不确定的信息用 list/readFile/search 核实，不凭记忆。"
echo "2) 记忆加载：用 memory 工具查看项目记忆，加载相关「坑」类历史经验。"
echo "3) 模块文档：涉及 feature 模块时先读 docs/modules/<模块>.md 了解架构约束。"
echo "4) 计划拆解：输出可执行步骤（每步含做什么/涉及文件/如何验证），步骤多时用待办清单登记。"
echo "5) 验收标准：明确「完成 = 可观测结果」（构建通过/测试全绿/文档同步）。"
echo "6) 纪律自检：对照 AGENTS.md（Always/Ask First/Never），列出资产同步项与需先询问项。"
echo "=========================================="

if [ "$(wc -l < "$TMPB" | tr -d ' ')" -gt 0 ]; then
  exit 1
fi
exit 0
