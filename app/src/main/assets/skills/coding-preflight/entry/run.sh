#!/bin/sh
# coding-preflight 技能入口：编程前准备体检。自动采集环境/仓库/项目结构快照，输出就绪判定与计划建议。
# 契约：SKILL_PROJECT_PATH 为容器内项目路径（宿主导入 /root/workspace，见 SkillExecutor）；
#       SKILL_ARG_TASK 为可选任务描述（由 loadSkill 的 args 注入，见 SkillExecutor）。
# 只读，不改任何文件；有阻断项（R-*）时退出码非 0；有建议项（W-*）时输出软阻断提示。
#
# 兼容性：目标运行环境为 Alpine（busybox ash / busybox grep / busybox awk / busybox sed）。
#   严禁使用 gawk 专属正则（\x{...}、\s、\d）与 GNU 扩展，避免解析即崩。
# 需容器内具备: git / grep / sed / awk（内置 Alpine 已含）。个别特性缺失时降级为部分采集。

set -u
export LC_ALL=C.UTF-8 2>/dev/null || export LC_ALL=en_US.UTF-8 2>/dev/null || export LC_ALL=C

P="${SKILL_PROJECT_PATH:-}"
TASK="${SKILL_ARG_TASK:-}"
ROOT=""
IS_GIT=false
# 管道子 shell 无法回写外层计数，用临时文件累计阻断项/建议项（mktemp 失败时回退固定路径）。
TMPB="$(mktemp 2>/dev/null || echo /tmp/cpf.blockers)"
TMPW="$(mktemp 2>/dev/null || echo /tmp/cpf.warnings)"
TMPTEMPLATES="$(mktemp 2>/dev/null || echo /tmp/cpf.templates)"
: > "$TMPB"
: > "$TMPW"
: > "$TMPTEMPLATES"

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

# ---- 项目类型识别（通用化：不做「非Android」二分，按标志文件 + 目录指纹判断） ----
PROJECT_TYPE_LABEL=""
PROJECT_TYPE_DETAIL=""
is_android=false
is_web_frontend=false
is_node_backend=false
is_php=false
is_python=false
is_golang=false
is_rust=false
is_java=false
is_c_cpp=false
is_script=false

# 1) Android：Gradle + app/build.gradle* + AndroidManifest/源目录特征
if [ -f "$ROOT/app/build.gradle.kts" ] || [ -f "$ROOT/app/build.gradle" ] \
   || ([ -f "$ROOT/settings.gradle.kts" ] && grep -qE ":app|include.*app" "$ROOT/settings.gradle.kts" 2>/dev/null) \
   || ([ -f "$ROOT/settings.gradle" ] && grep -qE ":app|include.*app" "$ROOT/settings.gradle" 2>/dev/null); then
  is_android=true
fi
# 2) 前端：package.json 且含 index.html / src / public / vite / webpack / pages
if [ -f "$ROOT/package.json" ]; then
  if grep -qE '"(vite|vue|react|next|nuxt|svelte|webpack|rollup|parcel)"' "$ROOT/package.json" 2>/dev/null \
     || [ -f "$ROOT/index.html" ] || [ -d "$ROOT/src" ] || [ -d "$ROOT/public" ] || [ -d "$ROOT/pages" ]; then
    is_web_frontend=true
  fi
fi
# 3) Node 后端：package.json 且含 express/koa/nest/fastify/egg，或存在 server.js / app.js
if [ -f "$ROOT/package.json" ]; then
  if grep -qE '"(express|koa|@nestjs|fastify|egg|hapi|nestjs)"' "$ROOT/package.json" 2>/dev/null \
     || [ -f "$ROOT/server.js" ] || [ -f "$ROOT/app.ts" ] || [ -d "$ROOT/controllers" ] || [ -d "$ROOT/routes" ]; then
    is_node_backend=true
  fi
fi
# 4) PHP
HAS_PHP_FILE=false
for pat in "$ROOT"/*.php "$ROOT/index.php" "$ROOT/api.php" "$ROOT/composer.json"; do
  [ -e "$pat" ] && HAS_PHP_FILE=true
done
[ -d "$ROOT/app/Http" ] && HAS_PHP_FILE=true
[ -d "$ROOT/public" ] && [ -f "$ROOT/public/index.php" ] && HAS_PHP_FILE=true
$HAS_PHP_FILE && is_php=true
# 5) Python
HAS_PY=false
for pat in "$ROOT/requirements.txt" "$ROOT/Pipfile" "$ROOT/pyproject.toml"; do [ -e "$pat" ] && HAS_PY=true; done
for f in "$ROOT"/*.py; do [ -e "$f" ] && HAS_PY=true; done
[ -d "$ROOT/app" ] && [ -f "$ROOT/app/__init__.py" ] && HAS_PY=true
$HAS_PY && is_python=true
# 6) Go
HAS_GO=false
[ -f "$ROOT/go.mod" ] && HAS_GO=true
for f in "$ROOT"/*.go; do [ -e "$f" ] && HAS_GO=true; done
$HAS_GO && is_golang=true
# 7) Rust
HAS_RS=false
[ -f "$ROOT/Cargo.toml" ] && HAS_RS=true
for f in "$ROOT"/*.rs; do [ -e "$f" ] && HAS_RS=true; done
$HAS_RS && is_rust=true
# 8) Java
HAS_JAVA=false
[ -f "$ROOT/pom.xml" ] && HAS_JAVA=true
[ -f "$ROOT/build.gradle" ] && [ "$is_android" = "false" ] && HAS_JAVA=true
[ -d "$ROOT/src/main/java" ] && HAS_JAVA=true
$HAS_JAVA && is_java=true
# 9) C/C++
HAS_C=false
[ -f "$ROOT/CMakeLists.txt" ] && HAS_C=true
[ -f "$ROOT/Makefile" ] && HAS_C=true
for f in "$ROOT"/*.c "$ROOT"/*.cpp "$ROOT"/*.h; do [ -e "$f" ] && HAS_C=true; done
$HAS_C && is_c_cpp=true

# 组合标签（允许多标签）
TAGS=""
$is_android       && TAGS="${TAGS}${TAGS:+/}Android应用工程"
$is_web_frontend  && TAGS="${TAGS}${TAGS:+/}Web前端工程"
$is_node_backend  && TAGS="${TAGS}${TAGS:+/}Node后端工程"
$is_php           && TAGS="${TAGS}${TAGS:+/}PHP工程"
$is_python        && TAGS="${TAGS}${TAGS:+/}Python工程"
$is_golang        && TAGS="${TAGS}${TAGS:+/}Go工程"
$is_rust          && TAGS="${TAGS}${TAGS:+/}Rust工程"
$is_java          && TAGS="${TAGS}${TAGS:+/}Java工程"
$is_c_cpp         && TAGS="${TAGS}${TAGS:+/}C/C++工程"

# 目录是否为空工作区
EMPTY_WORKSPACE=true
if ls -1A "$ROOT" 2>/dev/null | grep -qv '^$' >/dev/null 2>&1; then
  EMPTY_WORKSPACE=false
fi

# 最终输出
if [ -z "$TAGS" ] && $EMPTY_WORKSPACE; then
  PROJECT_TYPE_LABEL="空工作区（新建项目）"
  PROJECT_TYPE_DETAIL="工作区为空，建议先明确项目类型与技术栈再补全基础文档与目录结构。"
elif [ -z "$TAGS" ]; then
  # 非空但无匹配栈标志 → 脚本/纯文本/静态资源仓库
  HAS_SCRIPT=false
  for f in "$ROOT"/*.sh "$ROOT"/*.py "$ROOT"/*.js "$ROOT"/*.ts; do [ -e "$f" ] && HAS_SCRIPT=true; done
  if $HAS_SCRIPT; then
    PROJECT_TYPE_LABEL="脚本工程"
    PROJECT_TYPE_DETAIL="检测到脚本文件（未匹配到具体栈框架），按脚本类工程处理。"
  else
    PROJECT_TYPE_LABEL="通用工程（静态资源/纯文本/未匹配栈）"
    PROJECT_TYPE_DETAIL="未检测到主流工程标志文件，按通用最小原则补齐基础文档与 Git 忽略配置。"
  fi
else
  PROJECT_TYPE_LABEL="$TAGS"
  # 多标签时加一句说明
  case "$TAGS" in
    */*) PROJECT_TYPE_DETAIL="检测到多技术栈并存（$TAGS），按交集的通用最小原则补齐资产。" ;;
    *)   PROJECT_TYPE_DETAIL="按${TAGS}最小工程集补齐资产、配置与文档。" ;;
  esac
fi

echo "[类型] $PROJECT_TYPE_LABEL"
[ -n "$PROJECT_TYPE_DETAIL" ] && echo "[类型说明] $PROJECT_TYPE_DETAIL"

# ---- P-1 环境组件快照：按识别出的项目栈推断关键构建组件并探测（Git 始终探测） ----
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
$is_android && ENV_LINE="$ENV_LINE | $(probe Java java -version) | $(probe Gradle gradle --version) | $(probe AndroidSDK sdkmanager --version)"
($is_web_frontend || $is_node_backend) && [ -f "$ROOT/package.json" ] && ENV_LINE="$ENV_LINE | $(probe Node node --version) | $(probe Npm npm --version)"
$is_golang && ENV_LINE="$ENV_LINE | $(probe Go go version)"
$is_rust   && ENV_LINE="$ENV_LINE | $(probe Cargo cargo --version)"
$is_java && ! $is_android && ENV_LINE="$ENV_LINE | $(probe Java java -version) | $(probe Maven mvn --version)"
$is_php    && ENV_LINE="$ENV_LINE | $(probe PHP php -v)"
$is_python && ENV_LINE="$ENV_LINE | $(probe Python python3 --version)"
echo "$ENV_LINE"
# R-1 环境缺关键组件判定（按栈精确匹配，不提示无关组件）
if $is_android; then
  if ! command -v java >/dev/null 2>&1 || ! command -v gradle >/dev/null 2>&1; then
    echo "❌ [R-1] Android 项目缺少关键构建组件（Java/Gradle 有 missing）——先安装再写代码，避免写完构建失败"
    echo b >> "$TMPB"
  fi
fi
($is_web_frontend || $is_node_backend) && [ -f "$ROOT/package.json" ] && {
  for c in node npm; do
    if ! command -v "$c" >/dev/null 2>&1; then
      echo "❌ [R-1] 项目含 package.json 但缺 $c ——先安装 Node 工具链再写代码"
      echo b >> "$TMPB"
    fi
  done
}
$is_golang && ! command -v go >/dev/null 2>&1 && {
  echo "❌ [R-1] Go 工程缺 go 命令——先安装 Go 工具链再写代码"; echo b >> "$TMPB"
}
$is_rust && ! command -v cargo >/dev/null 2>&1 && {
  echo "❌ [R-1] Rust 工程缺 cargo 命令——先安装 Rust 工具链再写代码"; echo b >> "$TMPB"
}
$is_java && ! $is_android && {
  if [ -f "$ROOT/pom.xml" ] && ! command -v mvn >/dev/null 2>&1; then
    echo "❌ [R-1] Maven 工程缺 mvn 命令——先安装 Maven 再写代码"; echo b >> "$TMPB"
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "❌ [R-1] Java 工程缺 java 命令——先安装 JDK 再写代码"; echo b >> "$TMPB"
  fi
}
$is_php && ! command -v php >/dev/null 2>&1 && {
  echo "❌ [R-1] PHP 工程缺 php 命令——先安装 PHP 运行时再写代码"; echo b >> "$TMPB"
}
$is_python && {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "❌ [R-1] Python 工程缺 python3 命令——先安装 Python 再写代码"; echo b >> "$TMPB"
  fi
}

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
  STG="$(git -c core.quotepath=false -C "$ROOT" diff --cached --name-only 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  UST="$(git -c core.quotepath=false -C "$ROOT" diff --name-only 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  UNTR="$(git -c core.quotepath=false -C "$ROOT" ls-files --others --exclude-standard 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  TOTAL=$((STG+UST+UNTR))
  echo "[仓库] 分支: $BR（未提交改动: 已暂存 $STG / 未暂存 $UST / 未跟踪 $UNTR，共 $TOTAL）"
  if [ "$TOTAL" -gt 0 ]; then
    echo "❌ [R-4] 工作区有 $TOTAL 个未提交改动——与用户确认是否属同一主题，否则先 stash/提交，避免新旧混杂"
    echo b >> "$TMPB"
  fi
  if [ "$BR" = "main" ] || [ "$BR" = "master" ]; then
    echo "⚠️  [R-5] 当前在 $BR 分支；若本次为功能/重构且改动面较大，建议先 git checkout -b feat/xxx 再开工"
    echo w >> "$TMPW"
  fi
  echo "[仓库] 最近提交: $(git -C "$ROOT" log -1 --format='%h %s' 2>/dev/null || echo 无)"
else
  echo "[仓库] 未初始化 git 仓库，跳过仓库状态检查"
  echo "⚠️  [W-1] 工作区未初始化为 git 仓库——建议先执行 git init 并创建初始提交，便于回溯与回滚。"
  echo w >> "$TMPW"
fi

# ---- P-3 项目结构与模块清单 ----
if $is_android; then
  FEAT="$(ls -1 "$ROOT/app/src/main/java/com/R/codecore/feature" 2>/dev/null | grep -v '^$' | tr '\n' ' ' | sed 's/ $//')"
  if [ -n "$FEAT" ]; then
    echo "[结构] Android feature 模块: $FEAT"
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
  # 通用结构：列出根目录顶层（省略 .git），带目录/文件标识
  echo "[结构] 根目录顶层（省略 .git）："
  for it in "$ROOT"/* "$ROOT"/.*; do
    [ -e "$it" ] || continue
    b="$(basename "$it")"
    [ "$b" = "." ] || [ "$b" = ".." ] || [ "$b" = ".git" ] && continue
    if [ -d "$it" ]; then
      echo "  - DIR  $b/"
    else
      sz=$(wc -c < "$it" 2>/dev/null | tr -d ' ')
      echo "  - FILE $b  ($sz bytes)"
    fi
  done | head -40
fi

# ---- 文档最小模板生成函数（用于 W-6~W-9 缺失时把模板输出给模型直接使用） ----
# 注意：输出到 $TMPTEMPLATES，最后一次性在报告末尾附带【文档模板】段。
append_template() { # $1=文件名  $2=模板正文
  fname="$1"; body="$2"
  { echo "---- 缺失文档模板: $fname （直接写入以下内容即可）----"; echo "$body"; echo ""; } >> "$TMPTEMPLATES"
}

# ---- P-4 关键文件探测 ----
# AGENTS.md / README.md / .gitignore / .gitattributes 四份基础资产：缺失时全部计为建议项（W-6~W-9），
# 并附带【通用最小可用模板】到报告末尾，让模型直接 writeFile，不需要凭空编造内容。
for f in AGENTS.md README.md .gitignore .gitattributes; do
  if [ -f "$ROOT/$f" ]; then
    echo "[文件] $f 存在"
  else
    echo "[文件] $f 缺失"
    case "$f" in
      AGENTS.md)
        AGENTS_TMPL="# AGENTS.md

## 角色与优先级

你是本仓库的协同开发者，负责代码开发、资产同步与运维。决策优先级：
1. **正确性优先**：构建必须通过、测试必须全绿；拿不准时宁少改、不改错。
2. **纪律优先**：遵循本文件的资产同步、提交规范与边界规则。
3. **最小改动**：只做被要求的事，不做过度设计、不顺手重构。
4. **可维护性**：结构清晰、命名规范，改动同步维护对应文档。

## 边界规则（Always / Ask First / Never）

### Always（必须做）
- 文件操作优先使用专用工具；不要用 shell 替代。
- 编译型代码改动提交前，先构建验证可编译。
- 任何 git push 前，先跑测试并全部通过（纯文档/纯 md 改动除外）。
- 遵循资产同步纪律：prompts / docs / strings / 模块文档四类变更必须同步。
- 遵循提交规范：Conventional Commits（type(scope): subject）。
- 新功能/复杂多文件改动/架构重构：新建分支，验证后合回 main 并清理。

### Ask First（先询问确认）
- 破坏性操作：删除文件/删除分支/删除远端引用/force push。
- 打 Tag 发版。
- 架构级重构、跨模块结构变更。
- 修改数据库 schema。

### Never（禁止）
- 禁止在 .kt / .tsx / .vue 等 UI 代码中硬编码用户可见中文文案（必须走字符串资源/i18n）。
- 禁止把签名 secrets / API token 等敏感信息写入代码或文档。
- 禁止随意修改本 AGENTS.md（如需修订先说明原因并保留原意）。

## 资产同步纪律

- AI 工作流相关改动 → 检查 prompts：工具新增/删除/重命名、参数签名变化、agent 行为变化要同步更新提示词。
- 功能/工具变化 → 检查 docs：对应使用说明文档（新功能、行为变化）。
- UI 变化 → 必须更新使用文档，确保用户可见说明与实际界面一致。
- UI 文案 → 必须同步字符串资源（中文 + 英文翻译），禁止硬编码。
- 代码结构变化 → 必须同步模块文档（docs/modules/ 下每个 feature/模块一份，六段式结构）。

## Git 提交规范

格式：type(scope): subject
type 选：feat | fix | refactor | docs | style | chore | ci | build | perf | test
示例：feat(agent): 支持流式工具调用 / fix(settings): 修复保存校验失败
"
        echo "⚠️  [W-6] 纪律文档 AGENTS.md 缺失——【必须先创建】后再开始写码。下方【文档模板】段已附带通用最小可用版本，直接 writeFile 即可（按工程特点按需裁剪章节）：含边界规则 Always/Ask First/Never、提交规范、资产同步纪律。"
        echo w >> "$TMPW"
        append_template "AGENTS.md" "$AGENTS_TMPL"
        ;;
      README.md)
        # 按识别的项目类型拼最小 README 模板（技术栈/运行方式/接口约定按类型提示）
        STACK_LINE=""
        $is_android       && STACK_LINE="Android / Gradle / Kotlin + Jetpack Compose"
        $is_web_frontend  && STACK_LINE="${STACK_LINE:+$STACK_LINE / }Web 前端（按 package.json 确定具体框架）"
        $is_node_backend  && STACK_LINE="${STACK_LINE:+$STACK_LINE / }Node 后端（按 package.json 确定具体框架）"
        $is_php           && STACK_LINE="${STACK_LINE:+$STACK_LINE / }PHP（按入口文件确定运行方式）"
        $is_python        && STACK_LINE="${STACK_LINE:+$STACK_LINE / }Python（按依赖文件确定框架）"
        $is_golang        && STACK_LINE="${STACK_LINE:+$STACK_LINE / }Go"
        $is_rust          && STACK_LINE="${STACK_LINE:+$STACK_LINE / }Rust"
        $is_java          && STACK_LINE="${STACK_LINE:+$STACK_LINE / }Java（Maven / Gradle 按配置）"
        $is_c_cpp         && STACK_LINE="${STACK_LINE:+$STACK_LINE / }C/C++（CMake/Make 按配置）"
        [ -z "$STACK_LINE" ] && STACK_LINE="（按实际使用的技术栈填写）"

        RUN_LINE=""
        $is_android       && RUN_LINE="1. 构建：\\\`./gradlew :app:assembleDebug\\\` / \\\`:app:assembleRelease\\\`  2. 真机安装 APK 运行"
        $is_web_frontend  && RUN_LINE="1. 安装依赖：\\\`npm install\\\`（或 pnpm/yarn）  2. 启动开发：\\\`npm run dev\\\`  3. 生产构建：\\\`npm run build\\\`"
        $is_node_backend  && RUN_LINE="1. 安装依赖：\\\`npm install\\\`  2. 启动服务：\\\`npm start\\\` / \\\`npm run dev\\\`  3. 端口与路由按实际配置"
        $is_php           && RUN_LINE="1. PHP 内置服务：\\\`php -S 0.0.0.0:8080\\\`（或 Nginx/Apache）  2. 访问 http://localhost:8080/ 入口文件  3. 如需扩展用 composer 管理依赖"
        $is_python        && RUN_LINE="1. 建虚拟环境并安装：\\\`pip install -r requirements.txt\\\`  2. 启动按框架实际命令（如 uvicorn / flask run / manage.py runserver）"
        $is_golang        && RUN_LINE="1. 构建：\\\`go build\\\`  2. 运行：\\\`go run .\\\`  3. 按 main.go 入口配置参数"
        $is_rust          && RUN_LINE="1. 构建：\\\`cargo build --release\\\`  2. 开发：\\\`cargo run\\\`  3. 测试：\\\`cargo test\\\`"
        $is_java          && RUN_LINE="1. Maven：\\\`mvn spring-boot:run\\\`（或按实际） / Gradle：\\\`./gradlew bootRun\\\`  2. 按框架入口启动"
        $is_c_cpp         && RUN_LINE="1. CMake：\\\`cmake -S . -B build && cmake --build build\\\` / Make：\\\`make\\\`  2. 按产物运行"
        [ -z "$RUN_LINE" ] && RUN_LINE="（按工程实际命令填写）"

        API_LINE=""
        $is_node_backend  && API_LINE="- 服务默认端口 / 鉴权：按 \`config.*\` 或环境变量配置\n- 接口前缀：如 \`/api/v1/\`（按实际）\n- 错误码格式（按实际）"
        $is_php           && API_LINE="- 接口入口：如 \`api.php\` / \`public/index.php\`\n- 鉴权与参数约定（按实际补）\n- 错误响应格式（按实际补）"
        $is_python        && API_LINE="- 服务默认端口 / 鉴权：按配置\n- 路由前缀 / 错误码 / 鉴权方式（按实际补）"
        $is_golang        && API_LINE="- 服务默认端口 / 路由前缀（按 main.go 或路由文件）\n- 鉴权 / 错误码约定（按实际补）"
        [ -z "$API_LINE" ] && API_LINE="- （如无后端接口，说明为纯前端/脚本工程即可）"

        README_TMPL="# 项目名称

> 一句话描述：此项目用于什么场景，解决什么问题。

## 技术栈

$STACK_LINE

## 运行方式

$RUN_LINE

## 接口约定

$API_LINE

## 目录结构

（列出关键目录与职责，如：\`src/\` 源码 / \`tests/\` 测试 / \`docs/\` 文档 / \`scripts/\` 脚本 / 按实际补）

## 依赖与环境

（列出需要的最低版本，如 Node 20+ / JDK 17+ / PHP 8.1+ / Go 1.22+ / Python 3.11+ 等）

## 开发规范

- 分支管理：main 为主线，feat/* 为功能分支
- 提交规范：Conventional Commits（feat/fix/docs/chore/...）
- 代码风格：（按工程实际补充 lint / formatter 配置）
- 测试：（按工程实际补充测试命令与覆盖率要求）
"
        echo "⚠️  [W-7] 说明文档 README.md 缺失——【必须先补】一份项目说明（技术栈/运行方式/接口约定/目录结构/环境要求）。下方【文档模板】段已附带按「$PROJECT_TYPE_LABEL」生成的通用最小可用版本，直接 writeFile 再按需补内容，便于理解与交接。"
        echo w >> "$TMPW"
        append_template "README.md" "$README_TMPL"
        ;;
      .gitignore)
        # 按识别的项目类型生成 .gitignore 模板
        GITIGNORE_TMPL="# ===== 通用 =====
.DS_Store
Thumbs.db
.env
.env.*
*.log
*.tmp
*.bak
*.swp
*.swo
node_modules/
dist/
build/
out/
.cache/
.idea/
.vscode/
*.sublime-workspace
"
        $is_android && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== Android / Gradle =====
*.iml
.gradle/
local.properties
captures/
.externalNativeBuild/
.cxx/
app/build/
*/build/
*.apk
*.aab
*.ap_
*.dex
*.class
"
        $is_java && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== Java / Maven =====
target/
*.class
*.jar
*.war
*.ear
*.nar
hs_err_pid*
replay_pid*
.mvn/wrapper/maven-wrapper.jar
!**/src/main/**/build/
!**/src/test/**/build/
"
        $is_python && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== Python =====
__pycache__/
*.py[cod]
*$py.class
*.so
.Python
venv/
.venv/
ENV/
pip-wheel-metadata/
*.egg-info/
.eggs/
.pytest_cache/
.mypy_cache/
.ruff_cache/
"
        $is_php && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== PHP =====
vendor/
composer.phar
composer.lock
.phpunit.result.cache
.php_cs.cache
Homestead.json
Homestead.yaml
.env
.phpunit.cache/
"
        $is_node_backend && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== Node（开发/运行产物）=====
npm-debug.log*
yarn-debug.log*
yarn-error.log*
pnpm-debug.log*
.npm
.yarn
.pnpm-store/
coverage/
.nyc_output/
.next/
.nuxt/
.svelte-kit/
.serverless/
"
        $is_golang && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== Go =====
*.exe
*.exe~
*.dll
*.so
*.dylib
*.test
*.out
go.work.sum
vendor/
bin/
"
        $is_rust && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== Rust =====
/target
**/*.rs.bk
Cargo.lock.bak
"
        $is_c_cpp && GITIGNORE_TMPL="$GITIGNORE_TMPL
# ===== C / C++ =====
*.o
*.obj
*.a
*.lib
*.so
*.dylib
*.dll
*.exe
*.out
*.app
cmake-build-*/
CMakeCache.txt
CMakeFiles/
Makefile
cmake_install.cmake
install_manifest.txt
"
        echo "⚠️  [W-8] Git 忽略配置 .gitignore 缺失——【必须先创建】。下方【文档模板】段已附带按「$PROJECT_TYPE_LABEL」生成的通用最小可用版本（含语言/构建 IDE/临时文件忽略项），直接 writeFile 即可。"
        echo w >> "$TMPW"
        append_template ".gitignore" "$GITIGNORE_TMPL"
        ;;
      .gitattributes)
        GITATTR_TMPL="# ===== 通用：文本文件自动 LF 规范化 =====
* text=auto eol=lf

# ===== 文本类显式指定 =====
*.md    text diff=markdown
*.txt   text
*.json  text
*.yml   text
*.yaml  text
*.toml  text
*.ini   text
*.cfg   text
*.conf  text
*.sh    text eol=lf
*.bash  text eol=lf
*.zsh   text eol=lf
*.ps1   text eol=crlf
*.bat   text eol=crlf
*.cmd   text eol=crlf

# ===== 源码（文本 + diff） =====
*.kt    text diff=kotlin
*.kts   text diff=kotlin
*.java  text diff=java
*.js    text diff=javascript
*.jsx   text diff=javascript
*.ts    text diff=javascript
*.tsx   text diff=javascript
*.vue   text diff=javascript
*.php   text diff=php
*.py    text diff=python
*.go    text diff=golang
*.rs    text diff=rust
*.c     text diff=c
*.h     text diff=c
*.cpp   text diff=cpp
*.hpp   text diff=cpp
*.cc    text diff=cpp
*.hh    text diff=cpp
*.css   text diff=css
*.scss  text diff=css
*.less  text diff=css
*.html  text diff=html
*.xml   text diff=html
*.sql   text diff=sql
*.svg   text

# ===== 显式二进制（不参与 diff，LF/CRLF 不转换）=====
*.png   binary
*.jpg   binary
*.jpeg  binary
*.gif   binary
*.ico   binary
*.webp  binary
*.bmp   binary
*.pdf   binary
*.zip   binary
*.tar   binary
*.gz    binary
*.7z    binary
*.tgz   binary
*.jar   binary
*.war   binary
*.class binary
*.so    binary
*.dylib binary
*.dll   binary
*.exe   binary
*.apk   binary
*.aab   binary
*.mp3   binary
*.mp4   binary
*.ogg   binary
*.ttf   binary
*.woff  binary
*.woff2 binary
*.eot   binary
*.otf   binary
"
        echo "⚠️  [W-9] Git 属性 .gitattributes 缺失——【必须先创建】。下方【文档模板】段已附带通用最小可用版本（文本 LF 规范化 + 源码 diff 语言识别 + 二进制声明），直接 writeFile 即可。"
        echo w >> "$TMPW"
        append_template ".gitattributes" "$GITATTR_TMPL"
        ;;
    esac
  fi
done

# ---- 汇总 ----
BLOCKER_COUNT=$(wc -l < "$TMPB" | tr -d ' ')
WARNING_COUNT=$(wc -l < "$TMPW" | tr -d ' ')
echo "---------------- 就绪判定 ----------------"
echo "❌ 阻断项: $BLOCKER_COUNT   ⚠️ 建议项（待补资产/需前置动作）: $WARNING_COUNT"
if [ "$WARNING_COUNT" -gt 0 ]; then
  echo ""
  echo "🛑 【软阻断·必须在写码前完成】：当前建议项 $WARNING_COUNT > 0，涉及缺失的纪律文档 AGENTS.md / 说明文档 README.md / .gitignore / .gitattributes 等基础资产。"
  echo "请按上方 W-* 条目的指引【先补全再处理用户请求】。下方【文档模板】段已附带每份缺失文档的通用最小可用版本——"
  echo "直接 writeFile 写入即可，无需凭空编造内容。未补全前不要开始用户请求的代码改动。"
fi

echo ""
echo "================ 计划建议（必须在写码前按序执行）================"
echo "1) 任务理解：复述需求、识别目标文件/模块，不确定的信息用 list/readFile/search 核实，不凭记忆。"
echo "2) 记忆加载：用 memory 工具查看项目记忆，加载相关「坑」类历史经验。"
echo "3) 模块文档：涉及 feature/模块/子系统时先读 docs/modules/<模块>.md 或对应架构文档了解约束。"
echo "4) 补齐基础资产：按 W-* 条目 + 下方【文档模板】先 writeFile 补全缺失的 AGENTS.md / README.md / .gitignore / .gitattributes；W-1 未初始化仓库先 git init。"
echo "5) 计划拆解：输出可执行步骤（每步含做什么/涉及文件/如何验证），步骤多时用待办清单登记。"
echo "6) 验收标准：明确「完成 = 可观测结果」（构建通过/测试全绿/接口返回/页面渲染/文档同步）。"
echo "7) 纪律自检：对照 AGENTS.md（Always/Ask First/Never），列出资产同步项与需先询问项。"
echo "======================================================================"

# ---- 文档模板段：仅当有缺失时输出，直接供模型 writeFile ----
if [ -s "$TMPTEMPLATES" ]; then
  echo ""
  echo "================ 【文档模板】缺失资产的最小可用模板（直接 writeFile 写入） ================"
  cat "$TMPTEMPLATES"
  echo "=========================================================================================="
fi

# ---- 退出码：有阻断项非 0；无阻断但有建议项也非 0（让调用方感知软阻断但仍展示完整报告） ----
rm -f "$TMPB" "$TMPW" "$TMPTEMPLATES"
if [ "$BLOCKER_COUNT" -gt 0 ]; then
  exit 2
fi
if [ "$WARNING_COUNT" -gt 0 ]; then
  exit 3
fi
exit 0
