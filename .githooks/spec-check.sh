#!/usr/bin/env bash
# spec-check.sh —— Spec 规范驱动预检（被 .githooks/pre-commit 调用）。
#
# 职责（见 docs/plan-docs/norm-chain-design.md §3.4）：
#   1) 配套性触发：本次提交「新增 feature/<module> 目录 或 新增非 test 路径 .kt」
#      → 提示需配套 docs/plan-docs/*-design.md（阻断 + --no-verify 逃生口，与 docs/modules 同款）。
#   2) 评审状态行校验：本次提交含 *-design.md → 校验文档头部 `> 评审状态：<状态>` 引用行
#      存在且值合法（📝 草案 / ✅ 已评审 / 已实施）（阻断 + 逃生口）。
#   3) SOP 同步提示（warning 级、不阻断）：改 AGENTS.md → 提示检查 sop/10-50 同步；
#      改 prompts/ 行为规则文件（15-project-rules、40-approach）→ 提示检查 sop/60-ai-conduct 同步。
#
# 启用：仓库根执行 `git config core.hooksPath .githooks`
# 跳过（紧急情况）：git commit --no-verify ...

set -u

ROOT="$(git rev-parse --show-toplevel)"
PLAN_DOCS_DIR="$ROOT/docs/plan-docs"

errors=0
warnings=0

# 只校验本次提交触碰的内容（git diff --cached）
CACHED_NAMESTATUS="$(git diff --cached --name-status --diff-filter=ACMR 2>/dev/null)"
NEW_FILES="$(git diff --cached --name-status --diff-filter=A 2>/dev/null)"

#############################################
# 1) 配套性触发：新增 .kt（非 test 路径）或新增 feature 模块
#############################################
need_design_hint=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  status="${line%%$'\t'*}"
  path="${line#*$'\t'}"
  # 新增 .kt 且非 test 路径 → 命中配套性（新增 feature/<module> 目录必然伴随新增 .kt，一并覆盖）
  if [[ "$path" == *.kt ]] && [[ "$path" != *"/test/"* ]] && [[ "$path" != *"/androidTest/"* ]] && [[ "$status" == "A" ]]; then
    need_design_hint=1
    break
  fi
done <<< "$CACHED_NAMESTATUS"

if (( need_design_hint == 1 )); then
  echo "ℹ️  Spec 规范（配套性）：本次提交新增了源码文件/模块，建议配套一份设计文档 docs/plan-docs/*-design.md（命名 <名称>-design.md，头部标注评审状态），重大改动先评审再实施。" >&2
  # 新增 .kt 已命中即阻断（与 docs/modules 同款：阻断 + --no-verify 逃生口）
  errors=$((errors+1))
fi

#############################################
# 2) 评审状态行校验：本次提交含 *-design.md
#############################################
VALID_STATUSES=("📝 草案" "✅ 已评审" "已实施")
touched_design=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  path="${line#*$'\t'}"
  if [[ "$path" == *-design.md ]] && [[ -f "$ROOT/$path" ]]; then
    touched_design=1
    status_line="$(grep -m1 -E '^> *评审状态：' "$ROOT/$path" || true)"
    if [[ -z "$status_line" ]]; then
      echo "❌ Spec 状态行缺失：$path 缺少文档头部引用行「> 评审状态：📝 草案 / ✅ 已评审 / 已实施」" >&2
      errors=$((errors+1))
      continue
    fi
    value="${status_line#*> *评审状态：}"
    value="$(echo "$value" | xargs)"
    ok=0
    for v in "${VALID_STATUSES[@]}"; do
      if [[ "$value" == "$v" ]]; then ok=1; break; fi
    done
    if (( ok == 0 )); then
      echo "❌ Spec 状态行非法：$path 的评审状态「$value」不在合法取值（📝 草案 / ✅ 已评审 / 已实施），且不得带括号注释/组合态" >&2
      errors=$((errors+1))
    fi
  fi
done <<< "$CACHED_NAMESTATUS"

#############################################
# 3) SOP 同步提示（warning 级、不阻断）
#############################################
changed_agents=0
changed_prompts=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  path="${line#*$'\t'}"
  if [[ "$path" == "AGENTS.md" ]]; then
    changed_agents=1
  fi
  if [[ "$path" == app/src/main/assets/prompts/15-project-rules.md ]] || \
     [[ "$path" == app/src/main/assets/prompts/40-approach.md ]]; then
    changed_prompts=1
  fi
done <<< "$CACHED_NAMESTATUS"

if (( changed_agents == 1 )); then
  echo "ℹ️  SOP 同步提示：本次提交改了 AGENTS.md，请检查 assets/sop/ 下 10-50 流程资产是否需要同步（warning，不阻断）" >&2
  warnings=$((warnings+1))
fi
if (( changed_prompts == 1 )); then
  echo "ℹ️  SOP 同步提示：本次提交改了 prompts/ 行为规则文件，请检查 assets/sop/60-ai-conduct.md 是否需要同步（warning，不阻断）" >&2
  warnings=$((warnings+1))
fi

#############################################
# 汇总
#############################################
if (( errors > 0 )); then
  cat >&2 <<EOF

Spec 规范驱动纪律（见 AGENTS.md「资产同步纪律」与 docs/plan-docs/norm-chain-design.md §3.4）：
  - 新增源码/模块 → 先出设计文档 docs/plan-docs/*-design.md 并标记评审状态
  - 设计文档 → 头部引用行「> 评审状态：<状态>」，取值 📝 草案 / ✅ 已评审 / 已实施

跳过校验（紧急情况）：git commit --no-verify ...
EOF
  exit 1
fi

exit 0
