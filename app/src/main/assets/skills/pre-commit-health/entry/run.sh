#!/bin/sh
# pre-commit-health 技能入口：对工作区待提交改动做提交前规范体检，输出 UTF-8 报告。
# 契约：SKILL_PROJECT_PATH 为容器内项目路径（宿主导入 /root/workspace，见 SkillExecutor）。
# 只读，不改任何文件；有阻断项（C-*）时退出码非 0。
#
# 需容器内具备: git / grep / sed / awk（内置 Alpine 已含）。个别特性缺失时降级为部分检查。

set -u
export LC_ALL=C.UTF-8 2>/dev/null || export LC_ALL=en_US.UTF-8 2>/dev/null || export LC_ALL=C

P="${SKILL_PROJECT_PATH:-}"
BLOCKERS=0
WARNINGS=0
ROOT=""
IS_GIT=false
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

has() { printf '%s\n' "$CHANGED" | grep -qx "$1"; }
has_pref() { printf '%s\n' "$CHANGED" | grep -q "$1"; }
feature_module_of() { # 从路径提取 feature/<mod>/ 的 mod
  printf '%s\n' "$1" | sed -n 's|.*/com/R/codecore/feature/\([^/]*\)/.*|\1|p'
}

# ================= 阻断项 C-1：模块文档同步 =================
mods=$(printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/java/com/R/codecore/feature/*/*.kt|app/src/main/java/com/R/codecore/feature/*/) ;;
    app/src/main/java/com/R/codecore/feature/*/*) : ;;
  esac
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
: > "$TMPB"
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in *.kt) : ;; *) continue ;; esac
  [ -f "$ROOT/$f" ] || continue
  awk '
    /^[[:space:]]*(\/\/|#|\*|\/\*)/ { next }          # 纯注释行跳过
    {
      # 含中文字符 且 非 stringResource/R.getString 引用 且 疑似出现在字符串里
      if ($0 ~ /[\x{4e00}-\x{9fff}]/ && $0 !~ /R\.string\./ && $0 !~ /stringResource/ && $0 !~ /getString/) {
        if ($0 ~ /=?[[:space:]]*"|<string>|append\("|"[:-]?[^"]*[\x{4e00}-\x{9fff}]/ ) {
          printf "   %s: %s\n", FILENAME, $0
        }
      }
    }' "$ROOT/$f"
done | head -40 | while IFS= read -r ln; do
  echo "❌ [C-2] 疑似硬编码中文（应走 R.string.*）: $ln"
  echo x >> "$TMPB"
done

# ================= 阻断项 C-3：手写 versionName/versionCode =================
if [ -f "$ROOT/app/build.gradle.kts" ]; then
  if grep -nE 'version(Name|Code)[[:space:]]*=' "$ROOT/app/build.gradle.kts" | grep -v 'gitVersionName\|gitCommitCount' | grep -q '='; then
    echo "❌ [C-3] app/build.gradle.kts 中出现手写 versionName/versionCode（应由 Git Tag 动态推导）"
    BLOCKERS=$((BLOCKERS+1))
  fi
fi

# ================= 阻断项 C-4：敏感信息 =================
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    *.kt|*.kts|*.md|*.pro|*.xml|*.properties|*.json|*.sql|*.sh|*.yml|*.yaml) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if grep -nE 'ghp_[A-Za-z0-9]{6,}|api[_-]?key[[:space:]]*=[[:space:]]*["'"']?[A-Za-z0-9]{12,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|gho_|sk-[A-Za-z0-9]{16,}' "$ROOT/$f" 2>/dev/null | grep -v 'example\|sample\|<your' | head -20 | grep -q .; then
    echo "❌ [C-4] $f 出现疑似敏感信息（token/密钥），请勿提交"
    echo x >> "$TMPB"
  fi
done

# ================= 阻断项 C-5：targetSdk 锁定 28 =================
if [ -f "$ROOT/app/build.gradle.kts" ]; then
  tsdk=$(grep -oE 'targetSdk[[:space:]]*=[[:space:]]*[0-9]+' "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]+' | tr -d ' ')
  [ -z "$tsdk" ] && tsdk=0
  # 计算是否确有更高值（含 lint/legacy 配置里的 target）
  hi=$(grep -oE 'target(Sdk)?[[:space:]]*=?(=?\s*)[0-9]{3}' "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]{3}' | awk -v cur="$tsdk" '$1>28{print $1}' | head -1)
  if [ -n "$hi" ]; then
    echo "❌ [C-5] targetSdk 被提高到 $hi（探测到 28+ 值），须保持 28（PRoot W^X 绕过）"
    BLOCKERS=$((BLOCKERS+1))
  fi
fi

# ================= 建议项 W-1：prompts/docs 资产同步 =================
if has_pref '^app/src/main/assets/prompts/' || has_pref '^app/src/main/java/com/R/codecore/feature/.*/.*Tool\.kt' || has_pref 'AgentTool\.kt\|StatefulAgentWorkflow\.kt'; then
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
      [ -f "$ROOT/$f" ] && grep -nE "';|;'" "$ROOT/$f" && \
        echo "⚠️  [W-3] $f 字符串字面量含分号，迁移按 ';' 切分会误切，应用 char(59)"
    ;; esac
  done
fi

# ================= 建议项 W-4：提交信息格式建议 =================
suggested_type=""
if has_pref '^app/src/main/java/com/R/codecore/feature/'; then suggested_type="feat"; fi
has_pref '\.kt$' && [ -z "$suggested_type" ] && suggested_type="fix"
has_pref 'docs/|\.md$' && [ -z "$suggested_type" ] && suggested_type="docs"
has_pref '\.github/|\.githooks/' && [ -z "$suggested_type" ] && suggested_type="ci"
[ -z "$suggested_type" ] && suggested_type="chore"
scop=""
if [ -n "$mods" ]; then scop=$(printf '%s' "$mods" | head -1); fi
if [ -n "$scop" ]; then
  echo "⚠️  [W-4] 建议提交信息: $suggested_type($scop): 简述本次改动（type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test，不加句号）"
else
  echo "⚠️  [W-4] 建议提交信息: $suggested_type: 简述本次改动（Conventional Commits，不加句号）"
fi
WARNINGS=$((WARNINGS+1))

# ================= 建议项 W-5：分支纪律 =================
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