#!/usr/bin/env bash
# gitops：DeepCore-Code Git 工程化 CLI。
# 用途：与 AgentTool `gitops` 同源的开发者 CLI，终端直接调用提交规范校验/发版前体检/版本日志生成。
# 启用：在仓库根执行后直接 `./scripts/gitops/gitops.sh <action> [args...]`。
#   或 `chmod +x scripts/gitops/gitops.sh && ln -s ../../scripts/gitops/gitops.sh /usr/local/bin/gitops`。
#
# 子命令（与 AgentTool `gitops` action 对齐）：
#   check-commit <msg>     Conventional Commits 校验
#   suggest-commit          基于 status + diff 生成提交建议
#   hooks-status            本地 git hooks 启用状态
#   release-check <version> 发版前体检 + RC 判定
#   release-tag <version>   本地打 tag（推送交给外部 `git push origin <tag>`）
#   changelog [prev-tag]    从 git log 自动生成版本日志草稿
#
# 经验来源：
#   - 提交规范：.githooks/commit-msg
#   - 发版流程：AGENTS.md「发版流程（RC 判定）」+ docs/ci-release.md
#   - 版本日志：CHANGELOG.md 六类分类（Keep a Changelog）
#
# 退出码：0=通过/OK；1=失败；2=参数错误。

set -u

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$ROOT" ]]; then
  echo "错误：当前目录不在 git 仓库中" >&2
  exit 2
fi

cmd="${1:-}"
shift || true

TYPES_REGEX='^(feat|fix|refactor|docs|style|chore|ci|build|perf|test)(\([A-Za-z0-9._-]+\))?!?: .+'
TAG_REGEX='^v[0-9]+\.[0-9]+\.[0-9]+(-(rc|beta|alpha|dev)[0-9]+)?$'

is_main_branch() {
  local branch
  branch="$(git symbolic-ref --short -q HEAD 2>/dev/null)" || return 1
  [[ "$branch" == "main" ]]
}

is_worktree_clean() {
  local status
  status="$(git status --porcelain 2>/dev/null)"
  [[ -z "$status" ]]
}

is_rc_version() {
  [[ "$1" =~ $TAG_REGEX ]] || return 1
  [[ "$1" == *"-rc"* || "$1" == *"-beta"* || "$1" == *"-alpha"* || "$1" == *"-dev"* ]]
}

# 按变更的路径集合推断 scope（feature/<name>/ 匹配，取最常见）
infer_scope() {
  local changes="$1"
  if [[ -z "$changes" ]]; then
    echo ""
    return
  fi
  # 统计每个 feature 出现次数
  local scope
  scope="$(echo "$changes" \
    | grep -oE 'feature/[A-Za-z0-9_-]+/' \
    | sed 's/^feature\///; s/\/$//' \
    | sort | uniq -c | sort -rn | awk '{print $2; exit}')"
  echo "${scope:-}"
}

# 按路径命中敏感改动（启动/容器/构建链路）
touches_sensitive() {
  local changes="$1"
  [[ -z "$changes" ]] && return 1
  echo "$changes" | grep -qE \
    'AndroidManifest\.xml|AIEditorApp\.kt|feature/(terminal|container|settings)/|LinuxContainerEngine|ContainerInstaller|app/build\.gradle\.kts|gradle/libs\.versions\.toml|\.github/workflows'
}

# 仅文档/资源改动
is_non_code_only() {
  local changes="$1"
  [[ -n "$changes" ]] || return 1
  ! echo "$changes" | grep -qvE '\.md$|values/strings\.xml$'
}

cmd_check_commit() {
  local msg="$1"
  if [[ -z "$msg" ]]; then
    echo "错误：缺少提交信息" >&2
    exit 2
  fi
  local first_line
  first_line="$(printf '%s\n' "$msg" | sed -n '1p')"
  local escaped
  escaped="$(printf '%s' "$first_line" | LC_ALL=C grep -Eq "$TYPES_REGEX" && echo OK || echo FAIL)"
  # 跳过 merge/revert/fixup/squash
  case "$first_line" in
    "Merge "*|"Revert "*|"fixup!"*|"squash!"*)
      echo "ok=true skipped=true reason=自动生成的提交已跳过"
      return 0
      ;;
  esac
  local subject
  subject="$(printf '%s' "$first_line" | sed -n 's/^[^:]*: //p')"
  if [[ "$escaped" == "OK" && -n "$subject" ]]; then
    echo "ok=true matched=true type=$(echo "$first_line" | sed -n 's/^\([a-z]*\).*/\1/p')"
  else
    echo "ok=false matched=false first_line=$first_line"
    echo "提示：不符合 <type>(<scope>): <subject>；可用 type={feat fix refactor docs style chore ci build perf test}；scope 建议 agent/settings/terminal/workspace/git/ui/mcp/db/core/docs/build/deps" >&2
    exit 1
  fi
}

cmd_suggest_commit() {
  local status changes
  status="$(git status --porcelain -b)"
  if [[ -z "$status" ]]; then
    echo "ok=true has_changes=false suggestion=无待提交改动"
    return 0
  fi
  # 文件清单（仅改动行，去掉 branch 行）
  changes="$(git status --porcelain 2>/dev/null | sed '/^## /d')"
  local added=0 modified=0 deleted=0
  # 逐行统计状态码
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local code="${line:0:1}"
    case "$code" in
      A) added=$((added + 1)) ;;
      M|R) modified=$((modified + 1)) ;;
      D) deleted=$((deleted + 1)) ;;
    esac
  done <<< "$changes"

  local type scope subject
  if [[ $added -gt 0 && $modified -eq 0 && $deleted -eq 0 ]]; then
    type="feat"
  elif [[ $deleted -gt 0 ]]; then
    type="fix"
  elif [[ $modified -ge $added && $modified -ge $deleted ]]; then
    type="fix"
  elif [[ $added -gt $modified ]]; then
    type="feat"
  else
    type="refactor"
  fi
  scope="$(infer_scope "$changes")"
  subject="更新改动（+$added 新增, ~$modified 修改, -$deleted 删除）"
  subject="${subject:0:72}"
  local suggested
  if [[ -n "$scope" ]]; then
    suggested="$type($scope): $subject"
  else
    suggested="$type: $subject"
  fi
  echo "ok=true suggested_type=$type suggested_scope=${scope:-<none>} suggested=$suggested"
  echo "status: added=$added modified=$modified deleted=$deleted"
  echo "下一步：gitops check-commit \"$suggested\"  → git commit -m \"$suggested\" → 推送前跑单测"
}

cmd_hooks_status() {
  local hooks_path expected enabled advice
  hooks_path="$(git config --get core.hooksPath 2>/dev/null)"
  expected=".githooks"
  if [[ "$hooks_path" == "$expected" || "$hooks_path" == *"/$expected" ]]; then
    enabled="true"
    advice="hooks 已启用（core.hooksPath=$hooks_path）"
  else
    enabled="false"
    advice="未检测到 hooks 启用。在仓库根执行：git config core.hooksPath .githooks"
  fi
  echo "ok=true enabled=$enabled hooks_path=$hooks_path expected=$expected"
  echo "$advice"
  echo "hooks: commit-msg（Conventional Commits） | pre-commit（feature↔模块文档同步+Spec预检）"
}

cmd_release_check() {
  local version="$1"
  if [[ -z "$version" ]]; then
    echo "错误：缺少 version（如 v1.2.3）" >&2
    exit 2
  fi
  if ! [[ "$version" =~ $TAG_REGEX ]]; then
    echo "错误：version 格式不符（需 vX.Y.Z 或 vX.Y.Z-rcN/-beta/-alpha/-dev）：$version" >&2
    exit 2
  fi
  local branch clean_ok main_ok change_count prev_tag changes
  branch="$(git symbolic-ref --short -q HEAD 2>/dev/null || echo "(detached)")"
  main_ok=false
  [[ "$branch" == "main" ]] && main_ok=true
  clean_ok=false
  is_worktree_clean && clean_ok=true
  prev_tag="$(git tag --sort=-creatordate | head -n 1)"
  prev_tag="${prev_tag:-HEAD}"
  change_count=0
  changes="$(git diff --name-only "$prev_tag"..HEAD 2>/dev/null || true)"
  if [[ "$prev_tag" == "HEAD" ]]; then
    change_count="$(git rev-list --count HEAD 2>/dev/null || echo 0)"
  else
    change_count="$(git rev-list --count "$prev_tag"..HEAD 2>/dev/null || echo 0)"
  fi
  local touched_sensitive="false"
  if touches_sensitive "$changes"; then touched_sensitive="true"; fi
  local non_code_only="false"
  if is_non_code_only "$changes"; then non_code_only="true"; fi

  local is_rc_requested="false"
  if is_rc_version "$version"; then is_rc_requested="true"; fi

  local rc_reason rc_recommended
  if $is_rc_requested; then
    rc_reason="tag 含预发布后缀，按 RC 处理"
    rc_recommended="true"
  elif $touched_sensitive; then
    rc_reason="改动触及启动/容器/构建链路，建议先发 RC 预览版"
    rc_recommended="true"
  elif [[ $change_count -gt 0 && "$non_code_only" == "false" ]]; then
    rc_reason="含功能代码改动，建议先发 RC 预览版"
    rc_recommended="true"
  elif [[ "$non_code_only" == "true" ]]; then
    rc_reason="纯文档/资源文案改动，可直接发正式版"
    rc_recommended="false"
  elif [[ $change_count -eq 0 ]]; then
    rc_reason="无提交变更，可直接发正式版（需人工确认 tag 语义）"
    rc_recommended="false"
  else
    rc_reason="改动面较小且未触碰关键链路，可直接发正式版"
    rc_recommended="false"
  fi

  local can_release="false"
  local blockers=""
  if $main_ok && $clean_ok; then
    can_release="true"
  else
    $main_ok || blockers="${blockers:+$blockers | }分支 $branch 非 main"
    $clean_ok || blockers="${blockers:+$blockers | }工作区有未提交改动"
  fi

  echo "ok=true can_release=$can_release branch=$branch main_ok=$main_ok clean_ok=$clean_ok"
  echo "target_version=$version prev_tag=$prev_tag change_count=$change_count"
  echo "touched_sensitive=$touched_sensitive is_non_code_only=$non_code_only"
  echo "recommended_release=$(if $rc_recommended; then echo RC; else echo STABLE; fi)"
  echo "rc_reason=$rc_reason"
  if $can_release; then
    echo "下一步："
    echo "  1. git tag -a $version -m 'Release $version'"
    echo "  2. git push origin $version"
    if $rc_recommended; then
      echo "  3. 真机跑 AI 对话+终端+容器启动三条主线；RC 问题从该 tag 拉 hotfix 修复"
    else
      echo "  3. CI 构建产物校验：ABI 双通用包/签名/SHA256"
    fi
  else
    echo "阻塞因素：$blockers"
    echo "下一步：先切到 main + 提交干净 → 再跑 release-check"
    exit 1
  fi
}

cmd_release_tag() {
  local version="$1"
  if [[ -z "$version" ]]; then
    echo "错误：缺少 version（如 v1.2.3）" >&2
    exit 2
  fi
  if ! [[ "$version" =~ $TAG_REGEX ]]; then
    echo "错误：version 格式不符：$version" >&2
    exit 2
  fi
  local branch
  branch="$(git symbolic-ref --short -q HEAD 2>/dev/null || echo "(detached)")"
  if [[ "$branch" != "main" ]]; then
    echo "错误：仅允许在 main 分支打 tag（当前 $branch）" >&2
    exit 1
  fi
  if ! is_worktree_clean; then
    echo "错误：工作区有未提交改动，先提交干净再打 tag" >&2
    exit 1
  fi
  if git rev-parse -q --verify "refs/tags/$version" >/dev/null 2>&1; then
    echo "错误：tag '$version' 已存在" >&2
    exit 1
  fi
  git tag -a "$version" -m "Release $version" || {
    echo "错误：git tag 创建失败" >&2
    exit 1
  }
  echo "ok=true tag=$version created=true"
  echo "下一步：git push origin $version（凭据由 credential.helper 自动注入）"
}

cmd_changelog() {
  local prev_tag="${1:-}"
  local log_raw commits
  if [[ -z "$prev_tag" ]]; then
    prev_tag="$(git tag --sort=-creatordate | head -n 1)"
  fi
  prev_tag="${prev_tag:-HEAD}"
  if [[ "$prev_tag" == "HEAD" ]]; then
    log_raw="$(git log --pretty=format:'%s' 2>/dev/null || true)"
  else
    log_raw="$(git log "$prev_tag"..HEAD --pretty=format:'%s' 2>/dev/null || true)"
  fi
  if [[ -z "$log_raw" ]]; then
    echo "ok=true prev_tag=$prev_tag commit_count=0 draft=(无提交)"
    return 0
  fi
  local added="" improved="" fixed="" changed="" removed="" adjusted="" unclassified=""
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local first type
    first="$(echo "$line" | sed -n '1p')"
    type="$(echo "$first" | sed -n 's/^\([a-z]*\).*/\1/p')"
    local entry="- \`${first}\`"
    case "$type" in
      feat) added="${added:+$added
}$entry" ;;
      perf) improved="${improved:+$improved
}$entry" ;;
      fix) fixed="${fixed:+$fixed
}$entry" ;;
      refactor) changed="${changed:+$changed
}$entry" ;;
      docs|style|chore|ci|build|test) adjusted="${adjusted:+$adjusted
}$entry" ;;
      *) unclassified="${unclassified:+$unclassified
}$entry" ;;
    esac
  done <<< "$log_raw"
  commit_count="$(echo "$log_raw" | wc -l | tr -d ' ')"
  echo "ok=true prev_tag=$prev_tag commit_count=$commit_count"
  echo ""
  [[ -n "$added" ]] && { echo "### Added（新增）"; echo ""; echo "$added"; echo ""; }
  [[ -n "$improved" ]] && { echo "### Improved（改进）"; echo ""; echo "$improved"; echo ""; }
  [[ -n "$fixed" ]] && { echo "### Fixed（修复）"; echo ""; echo "$fixed"; echo ""; }
  [[ -n "$changed" ]] && { echo "### Changed（变更）"; echo ""; echo "$changed"; echo ""; }
  [[ -n "$removed" ]] && { echo "### Removed（删除）"; echo ""; echo "$removed"; echo ""; }
  [[ -n "$adjusted" ]] && { echo "### Adjusted（调整）"; echo ""; echo "$adjusted"; echo ""; }
  [[ -n "$unclassified" ]] && { echo "### Unclassified（待归类）"; echo ""; echo "$unclassified"; echo ""; }
  echo "下一步：复核 draft → 追加到 CHANGELOG.md → 在 docs/modules/<module>.md 追加版本演进记录"
}

usage() {
  cat <<EOF
gitops：DeepCore-Code Git 工程化 CLI

用法：
  gitops check-commit <msg>      Conventional Commits 校验
  gitops suggest-commit          基于 status+diff 生成提交建议
  gitops hooks-status            本地 git hooks 启用状态
  gitops release-check <version> 发版前体检 + RC 判定（如 v1.2.3 / v1.2.3-rc1）
  gitops release-tag <version>   本地打 tag（推送交给外部）
  gitops changelog [prev-tag]    自动生成版本日志草稿

示例：
  gitops check-commit "feat(agent): 新增流式工具调用"
  gitops suggest-commit
  gitops release-check v1.2.3-rc1
  gitops changelog v1.2.0

退出码：0=OK  1=失败  2=参数错误
EOF
}

case "$cmd" in
  check-commit|check_commit) cmd_check_commit "${1:-}" ;;
  suggest-commit|suggest_commit) cmd_suggest_commit ;;
  hooks-status|hooks_status) cmd_hooks_status ;;
  release-check|release_check) cmd_release_check "${1:-}" ;;
  release-tag|release_tag) cmd_release_tag "${1:-}" ;;
  changelog) cmd_changelog "${1:-}" ;;
  -h|--help|help) usage ;;
  *) usage; exit 2 ;;
esac
