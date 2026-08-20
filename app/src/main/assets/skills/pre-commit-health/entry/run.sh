#!/bin/sh
# pre-commit-health 技能入口：对工作区待提交改动做提交前规范体检，输出 UTF-8 报告。
# 契约：SKILL_PROJECT_PATH 为容器内项目路径（宿主导入 /root/workspace，见 SkillExecutor）。
# 只读，不改任何文件；有阻断项（C-*）时退出码非 0。
#
# 兼容性：目标运行环境为 Alpine（busybox ash / busybox awk / busybox grep）。
#   严禁使用 gawk 专属正则（\x{...}、\s、\d）与 GNU 扩展，避免解析即崩。
# 分层：通用检查（敏感信息/提交信息格式/分支纪律）对任意 git 项目生效；
#   Android 专属检查（模块文档/strings.xml/版本号/targetSdk/prompts|docs 资产/迁移 SQL）
#   仅当识别为 Android 项目（存在 app/build.gradle.kts）时执行，否则降级提示跳过。
#
# 需容器内具备: git / grep / sed（内置 Alpine 已含）。个别特性缺失时降级为部分检查。

set -u
export LC_ALL=C.UTF-8 2>/dev/null || export LC_ALL=en_US.UTF-8 2>/dev/null || export LC_ALL=C

P="${SKILL_PROJECT_PATH:-}"
BLOCKERS=0
WARNINGS=0
ROOT=""
IS_GIT=false
IS_ANDROID=false
# 管道子 shell 无法回写外层计数，用临时文件记录阻断项
TMPB="$(mktemp 2>/dev/null || echo /tmp/pch.blockers)"

echo "================ 提交前规范体检报告 ================"

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
fi

# ---- 项目类型识别（B1 分层）：Android 专属检查的开关 ----
if [ -f "$ROOT/app/build.gradle.kts" ] || [ -f "$ROOT/app/build.gradle" ]; then
  IS_ANDROID=true
  echo "[类型] Android 项目（应用 Gradle 构建）"
else
  echo "[类型] 非 Android 项目 —— 跳过 Android 专属检查（模块文档/strings.xml/版本号/targetSdk/迁移 SQL），仅执行通用检查"
fi

# ---- 收集待提交改动文件（去重、去空） ----
CHANGED=""
if $IS_GIT; then
  CHANGED="$( { git -C "$ROOT" diff --name-only HEAD 2>/dev/null; git -C "$ROOT" status --porcelain 2>/dev/null | awk '{print $2}'; } | sed '/^$/d' | sort -u )"
fi
if [ -z "$CHANGED" ]; then
  # 非 git 仓库：退化为整树探测（限定常见关键目录）
  if [ -d "$ROOT/app/src/main/java/com/R/codecore/feature" ]; then
    CHANGED="$(find "$ROOT/app/src/main/java/com/R/codecore/feature" -type f -name '*.kt' | sed "s|^$ROOT/||")"
  fi
fi
echo "[改动面] 本次待提交/变更文件数: $(printf '%s\n' "$CHANGED" | sed '/^$/d' | wc -l | tr -d ' ')"

# busybox 兼容：统一用 grep -E（ERE），BRE 的 | 是字面量、\| 依赖 GNU 扩展，易踩坑
has_pref() { printf '%s\n' "$CHANGED" | LC_ALL=C grep -E -q "$1"; }

if $IS_ANDROID; then

# ================= 阻断项 C-1：模块文档同步 =================
mods=$(printf '%s\n' "$CHANGED" | while read -r f; do
  m=$(printf '%s\n' "$f" | sed -n 's|.*/com/R/codecore/feature/\([^/]*\)/.*|\1|p')
  [ -n "$m" ] && printf '%s\n' "$m"
done | sort -u)
if [ -n "$mods" ]; then
  for m in $mods; do
    if [ ! -f "$ROOT/docs/modules/$m.md" ]; then
      echo "❌ [C-1] 改动了 feature/$m/ 但缺少 docs/modules/$m.md（新增模块必须实时新建对应文档）"
      BLOCKERS=$((BLOCKERS+1))
    fi
  done
fi
# 孤儿文档检查（docs/modules 有文档但无对应 feature 目录）
if [ -d "$ROOT/docs/modules" ]; then
  for doc in "$ROOT"/docs/modules/*.md; do
    n=$(basename "$doc" .md)
    case "$n" in README|core) continue ;; esac
    if [ ! -d "$ROOT/app/src/main/java/com/R/codecore/feature/$n" ]; then
      echo "❌ [C-1] 孤儿文档: docs/modules/$n.md 无对应 feature/$n/ 模块（删除模块时须同步删除文档）"
      BLOCKERS=$((BLOCKERS+1))
    fi
  done
fi

# ================= 阻断项 C-2：.kt 硬编码中文（走 strings.xml） =================
# busybox 兼容：不依赖 gawk 的 \x{...} Unicode 语法，改按字节检测「非可打印 ASCII」。
# 在 UTF-8/C locale 下中文等非 ASCII 字符的高字节 >0x7E，[^ -~] 即可命中。
: > "$TMPB"
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in *.kt) : ;; *) continue ;; esac
  [ -f "$ROOT/$f" ] || continue
  LC_ALL=C grep -n '[^ -~]' "$ROOT/$f" 2>/dev/null | head -40 | while IFS=: read -r ln rest; do
    # 跳过 stringResource / R.string / getString 引用行
    case "$rest" in
      *stringResource*|*R.string*|*getString*) continue ;;
    esac
    # 跳过注释行：// 单行注释、/* 或 /** 块注释（含单行 KDoc）、块注释续行（前导空白后是 *）
    case "$rest" in
      *'//'*) continue ;;
      *'/*'*) continue ;;
    esac
    if printf '%s' "$rest" | LC_ALL=C grep -q '^[[:space:]]*\*'; then continue; fi
    # 跳过日志语句：FileLogger 输出为诊断日志，非用户可见 UI 文案（AGENTS.md 仅约束 UI 文案走 strings.xml）
    case "$rest" in
      *'FileLogger.'*) continue ;;
    esac
    echo "❌ [C-2] 疑似硬编码中文（应走 R.string.*）: $f:$ln $rest"
    echo x >> "$TMPB"
  done
done

# ================= 阻断项 C-3：手写 versionName/versionCode =================
if [ -f "$ROOT/app/build.gradle.kts" ]; then
  if grep -nE 'version(Name|Code)[[:space:]]*=' "$ROOT/app/build.gradle.kts" | grep -v 'gitVersionName\|gitCommitCount' | grep -q '='; then
    echo "❌ [C-3] app/build.gradle.kts 中出现手写 versionName/versionCode（应由 Git Tag 动态推导）"
    BLOCKERS=$((BLOCKERS+1))
  fi
fi

# ================= 阻断项 C-5：targetSdk 锁定 28 =================
if [ -f "$ROOT/app/build.gradle.kts" ]; then
  # 仅在同时存在 targetSdk 赋值且值 >28 时拦截（busybox 兼容，无 \s/\d 依赖）
  if LC_ALL=C grep -nE 'targetSdk[[:space:]]*=[[:space:]]*[0-9]+' "$ROOT/app/build.gradle.kts" | LC_ALL=C grep -E 'targetSdk[[:space:]]*=[[:space:]]*(2[9]|[3-9][0-9])' | grep -q .; then
    echo "❌ [C-5] targetSdk 被提高到 28 以上，须保持 28（PRoot W^X 绕过）"
    BLOCKERS=$((BLOCKERS+1))
  fi
fi

# ================= 建议项 W-1：prompts/docs 资产同步 =================
if has_pref '^app/src/main/assets/prompts/' || has_pref '^app/src/main/java/com/R/codecore/feature/.*/.*Tool\.kt' || has_pref '(AgentTool\.kt|StatefulAgentWorkflow\.kt)'; then
  echo "⚠️  [W-1] 变更涉 AI 工作流/工具：检查 assets/prompts/ 与 assets/docs/ 是否需同步（prompts/docs 资产同步纪律）"
  WARNINGS=$((WARNINGS+1))
fi

# ================= 建议项 W-2：模块文档反映行为变化 =================
if has_pref '^app/src/main/java/com/R/codecore/'; then
  echo "⚠️  [W-2] 涉及功能代码变更：确认对应 docs/modules/<模块>.md 已记录本次行为变化（六段式文档）"
  WARNINGS=$((WARNINGS+1))
fi

# ================= 建议项 W-3：迁移 SQL 字面量分号 =================
if has_pref '^app/src/main/assets/migrations/.*\.sql$'; then
  printf '%s\n' "$CHANGED" | while read -r f; do
    case "$f" in app/src/main/assets/migrations/*.sql)
      [ -f "$ROOT/$f" ] && LC_ALL=C grep -nE "';|;'" "$ROOT/$f" | head -10 | while IFS= read -r hit; do
        echo "⚠️  [W-3] $f: $hit 字符串字面量含分号，迁移按 ';' 切分会误切，应用 char(59)"
      done
    ;; esac
  done
fi

fi # end IS_ANDROID

# ================= 阻断项 C-4：敏感信息（通用） =================
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    *.kt|*.kts|*.md|*.pro|*.xml|*.properties|*.json|*.sql|*.sh|*.yml|*.yaml|*.js|*.ts|*.css|*.html) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE "ghp_[A-Za-z0-9]{6,}|api[_-]?key[[:space:]]*=[[:space:]]*['\"][A-Za-z0-9]{12,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|gho_|sk-[A-Za-z0-9]{16,}" "$ROOT/$f" 2>/dev/null | grep -v 'example\|sample\|<your' | head -20 | grep -q .; then
    echo "❌ [C-4] $f 出现疑似敏感信息（token/密钥），请勿提交"
    echo x >> "$TMPB"
  fi
done

# ================= 建议项 W-4：提交信息格式建议（通用） =================
suggested_type="chore"
if $IS_ANDROID; then
  if has_pref '^app/src/main/java/com/R/codecore/feature/'; then suggested_type="feat"; fi
  has_pref '\.kt$' && [ "$suggested_type" = "chore" ] && suggested_type="fix"
  has_pref '(\.github/|\.githooks/)' && [ "$suggested_type" = "chore" ] && suggested_type="ci"
else
  has_pref '\.github/' && suggested_type="ci"
  # 前端源码改动建议 feat（ERE 扩展名匹配）
  if [ "$suggested_type" = "chore" ] && printf '%s\n' "$CHANGED" | LC_ALL=C grep -E '\.(js|ts|css|html|vue|jsx|tsx)$' | grep -q .; then
    suggested_type="feat"
  fi
fi
if [ "$suggested_type" = "chore" ] && has_pref '(docs/|\.md$)'; then suggested_type="docs"; fi
scop=""
if [ -n "${mods:-}" ]; then scop=$(printf '%s' "$mods" | head -1); fi
if [ -n "$scop" ]; then
  echo "⚠️  [W-4] 建议提交信息: $suggested_type($scop): 简述本次改动（type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test，不加句号）"
else
  echo "⚠️  [W-4] 建议提交信息: $suggested_type: 简述本次改动（Conventional Commits，不加句号）"
fi
WARNINGS=$((WARNINGS+1))

# ================= 建议项 W-5：分支纪律（通用） =================
if $IS_GIT; then
  branch=$(git -C "$ROOT" branch --show-current 2>/dev/null || true)
  case "$branch" in
    feat/*|refactor/*|hotfix/*)
      echo "⚠️  [W-5] 当前分支 $branch 为功能/重构/热修分支：禁止在其上打 Tag 发版；发版必须先合入 main 再打 Tag"
      WARNINGS=$((WARNINGS+1))
    ;;
  esac
fi

echo "=================================================="
# 合并管道子 shell 累计的阻断项（C-2/C-4 走 TMPB，C-1/C-3/C-5 走 BLOCKERS）
if [ -f "$TMPB" ]; then
  tb=$(grep -c '' "$TMPB" 2>/dev/null || true)
  BLOCKERS=$((BLOCKERS + tb))
fi
rm -f "$TMPB"
echo "汇总: 阻断项=$BLOCKERS, 建议项=$WARNINGS"
if [ "$BLOCKERS" -gt 0 ]; then
  echo "结论: 存在阻断项，请按报告逐条修复后重跑本技能，直至阻断项=0 再提交。"
  exit 1
else
  echo "结论: 无阻断项，可提交。提交信息格式: <type>(<scope>): <subject>"
fi
exit 0