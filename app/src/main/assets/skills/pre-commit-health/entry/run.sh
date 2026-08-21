#!/bin/sh
# pre-commit-health 技能入口：对工作区待提交改动做提交前规范体检，输出 UTF-8 报告。
# 契约：SKILL_PROJECT_PATH 为容器内项目路径（宿主导入 /root/workspace，见 SkillExecutor）。
# 只读，不改任何文件；有阻断项（C-*）时退出码非 0。
#
# 兼容性：目标运行环境为 Alpine（busybox ash / busybox awk / busybox grep）。
#   严禁使用 gawk 专属正则（\x{...}、\s、\d）与 GNU 扩展，避免解析即崩。
# 分层：通用检查（敏感信息含敏感赋值/敏感文件/合并冲突/构建产物含临时备份/超大文件/调试残留/Git 中间
#   状态/技能资产 frontmatter/二进制文件/高熵密钥/提交信息格式/分支纪律/diff 预算/待办标记/原子性/
#   文件卫生含 CRLF/大小写冲突/损坏符号链接/AI 引用残留/超长行/游离 HEAD/依赖锁定/.gitignore 缺口/
#   子模块嵌套仓库/硬编码绝对路径/shebang 一致性/编码与结构化文件雷区/依赖版本未锁定/大删除确认/
#   .gitattributes 归一化/工作流供应链安全/内网私有 IP）对任意 git 项目生效；
#   Android 专属检查（模块文档/strings.xml/版本号/targetSdk/prompts|docs 资产/迁移 SQL）仅当识别为
#   Android 项目（存在 app/build.gradle.kts 或 app/build.gradle）时执行，否则降级提示跳过。
#
# 需容器内具备: git / grep / sed / awk / tail（内置 Alpine 已含）。个别特性缺失时降级为部分检查。

set -u
export LC_ALL=C.UTF-8 2>/dev/null || export LC_ALL=en_US.UTF-8 2>/dev/null || export LC_ALL=C

P="${SKILL_PROJECT_PATH:-}"
BLOCKERS=0
WARNINGS=0
ROOT=""
IS_GIT=false
IS_ANDROID=false
# 管道子 shell 无法回写外层计数，用临时文件累计阻断项/建议项（mktemp 失败时回退固定路径）。
# 初始化累计文件：mktemp 成功时空文件无需清，回退固定路径时可能残留旧内容，故统一在此清空一次；
# 后续所有写入一律 >>（append），任何检查项都不再清空，避免「只累计到最后一个检查项」的计数丢失 bug。
TMPB="$(mktemp 2>/dev/null || echo /tmp/pch.blockers)"
TMPW="$(mktemp 2>/dev/null || echo /tmp/pch.warnings)"
: > "$TMPB"
: > "$TMPW"

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
# 修复：原用 `git status --porcelain | awk '{print $2}'`，对重命名（一行两路径）与 core.quotepath
# 转义/带空格路径解析不可靠；改用 `git diff --name-only HEAD`（已跟踪的已暂存+未暂存改动）+
# `git diff --cached --name-only`（覆盖无 HEAD 的新建仓库里已暂存的新文件）+
# `git ls-files --others --exclude-standard`（未跟踪文件，且逐个列出不折叠目录），正确且更快。
CHANGED=""
if $IS_GIT; then
  CHANGED="$( { \
    git -c core.quotepath=false -C "$ROOT" diff --name-only HEAD 2>/dev/null; \
    git -c core.quotepath=false -C "$ROOT" diff --cached --name-only 2>/dev/null; \
    git -c core.quotepath=false -C "$ROOT" ls-files --others --exclude-standard 2>/dev/null; \
  } | sed '/^$/d' | sort -u )"
fi
if [ -z "$CHANGED" ]; then
  # 非 git 仓库：退化为整树探测（限定常见关键目录）
  if [ -d "$ROOT/app/src/main/java/com/R/codecore/feature" ]; then
    CHANGED="$(find "$ROOT/app/src/main/java/com/R/codecore/feature" -type f -name '*.kt' | sed "s|^$ROOT/||")"
  fi
fi
FCNT="$(printf '%s\n' "$CHANGED" | sed '/^$/d' | wc -l | tr -d ' ')"
# 改动面细分（已暂存/未暂存/未跟踪），便于 AI 判断 commit 将真正包含哪些文件。
STG=0; UST=0; UNTR=0
if $IS_GIT; then
  STG="$(git -c core.quotepath=false -C "$ROOT" diff --cached --name-only 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  UST="$(git -c core.quotepath=false -C "$ROOT" diff --name-only 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  UNTR="$(git -c core.quotepath=false -C "$ROOT" ls-files --others --exclude-standard 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
fi
echo "[改动面] 本次待提交/变更文件数: $FCNT（已暂存 $STG / 未暂存 $UST / 未跟踪 $UNTR）"
if [ -z "$CHANGED" ]; then
  echo "（未发现待提交/变更文件，以下检查范围为全仓关键文件探测）"
fi

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
    [ -f "$doc" ] || continue   # 空目录时 glob 不展开为字面量，须跳过
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

# ================= 阻断项 C-4：敏感信息（已知特征 + 敏感赋值）（通用） =================
# 借鉴 gitleaks/detect-secrets 的「正则特征 + 命名启发」双层：a) 已知供应商 token/私钥前缀
# （GitHub/GitLab/OpenAI/Stripe(含 rk_)/Slack/SendGrid/Telegram/Heroku/Shopify/Alibaba/Google/AWS/
#   npm/JWT/Anthropic/HuggingFace/GCP service account/age/证书与 PGP 块/Authorization: Bearer/
#   内嵌凭据的 DB URL 等）；b) 变量名含 KEY/SECRET/TOKEN/PASSWORD 且被赋长值（>=24 字符，低误报）。
# 二者均为确定性特征，作硬阻断；随机形态的高熵密钥由 C-13 熵检测兜底。
# 特征拆分为多个 -e 短模式（避免单行超长），示例/占位值予以豁免。
# 豁免词取 example|sample|placeholder|your|dummy|changeme|xxxx（刻意不含 test：
# sk_test_ 等真实测试密钥不能因含 "test" 被整行放行，否则 Stripe 类测试密钥会被漏检）。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/assets/skills/*) continue ;;   # 技能资产本身含安全正则字面量，跳过避免自引用误报（同 W-7）
  esac
  case "$f" in
    *.kt|*.kts|*.java|*.md|*.pro|*.xml|*.properties|*.json|*.sql|*.sh|*.yml|*.yaml|*.js|*.ts|*.py|*.go|*.rs|*.css|*.html|*.gradle|*.toml|*.env) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE \
    -e 'gh[opsur]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{22,}' \
    -e "api[_-]?key[[:space:]]*=[[:space:]]*['\"][A-Za-z0-9]{12,}" \
    -e 'BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY|PuTTY-User-Key-File-2' \
    -e 'sk-[A-Za-z0-9]{16,}|sk_live_[A-Za-z0-9]{16,}|sk_test_[A-Za-z0-9]{16,}' \
    -e 'whsec_[A-Za-z0-9]{16,}|sk-ant-[A-Za-z0-9_-]{24,}|hf_[A-Za-z0-9]{24,}' \
    -e 'xox[baprs]-[A-Za-z0-9-]{10,}|xapp-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{20,}' \
    -e 'hooks\.slack\.com/services/T[A-Z0-9]{8,}/B[A-Z0-9]{8,}/[A-Za-z0-9]{24,}' \
    -e '"type"[[:space:]]*:[[:space:]]*"service_account"' \
    -e '(AKIA|A3T|ASIA|ABIA|ACCA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA)[A-Z2-7]{16}' \
    -e 'aws_(access_key_id|secret_access_key)|npm_[A-Za-z0-9]{30,}' \
    -e '(postgres|postgresql|mysql|mariadb|mongodb|redis|amqp)://[A-Za-z0-9_.-]+:[^@[:space:]]+@' \
    -e 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' \
    -e 'glpat-[A-Za-z0-9_-]{20,}' \
    -e 'shp(at|ca|ss|pa)_[0-9a-zA-Z]{32}' \
    -e 'rk_(live|test)_[0-9a-zA-Z]{16,}' \
    -e 'SG\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}' \
    -e '[0-9]{8,10}:[A-Za-z0-9_-]{30,}' \
    -e '[hH][rR][sS][0-9A-Z]{24}' \
    -e 'LTAI[A-Za-z0-9]{20}' \
    -e 'SK[0-9a-fA-F]{32}' \
    -e 'AGE-SECRET-KEY-1[QPZRY9X8GF2TVDW0S3JN54KHCE6MUA7L]{58}' \
    -e 'BEGIN CERTIFICATE|BEGIN PGP (PUBLIC KEY BLOCK|PRIVATE KEY BLOCK)' \
    -e 'Authorization:[[:space:]]*Bearer[[:space:]][A-Za-z0-9._~+/=-]{20,}' \
    -e '[_A-Za-z0-9]*(KEY|SECRET|TOKEN|PASSWORD|PASSWD)[_A-Za-z0-9]*[[:space:]]*[=:][[:space:]]*["'"'"'][A-Za-z0-9+/=_-]{24,}["'"'"']' \
    "$ROOT/$f" 2>/dev/null | grep -vE 'example|sample|placeholder|your|dummy|changeme|xxxx' | head -20 | grep -q .; then
    echo "❌ [C-4] $f 出现疑似敏感信息（已知 token 特征或敏感命名赋长值），请勿提交"
    echo x >> "$TMPB"
  fi
done

# ================= 阻断项 C-6：合并冲突标记（通用） =================
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    *.kt|*.kts|*.java|*.md|*.xml|*.json|*.sql|*.sh|*.js|*.ts|*.py|*.go|*.rs|*.yml|*.yaml|*.gradle|*.properties) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE '^(<<<<<<<|>>>>>>>)' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
    echo "❌ [C-6] $f 残留合并冲突标记（<<<<<<< / >>>>>>>），需先解决冲突再提交"
    echo x >> "$TMPB"
  fi
done

# ================= 阻断项 C-7：构建产物 / 超大文件 / 临时备份 / IDE 残留（通用） =================
# 借鉴 pre-commit-hooks 的 check-added-large-files：产物/二进制/编辑器残留（*.orig/*.rej 为
# merge/rebase 冲突遗留、*.tmp/*.bak/*~/*.swp 为编辑器临时文件）一律禁提交；
# 另拦截 IDE 配置（.idea/）、机器相关本地配置（local.properties）、日志（*.log）与依赖/构建目录。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    build/*|*/build/*|.gradle/*|.idea/*|node_modules/*|.next/*|*.apk|*.aab|*.iml|local.properties|*.log|Thumbs.db|.DS_Store|*.zip|*.tar|*.tar.gz|*.tgz|*.bin|*.so \
      |*.jar|*.class|*.o|*.a|*.dll|*.dylib|*.exe|*.pyc|*.pyo|*.tmp|*.bak|*.orig|*.rej|*.swp|*~)
      echo "❌ [C-7] $f 疑似构建产物/二进制/临时/IDE/日志文件被纳入提交（应加入 .gitignore）"
      echo x >> "$TMPB"
      continue
    ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  size=$(wc -c < "$ROOT/$f" 2>/dev/null | tr -d ' ')
  if [ -n "$size" ] && [ "$size" -gt 5242880 ]; then
    echo "❌ [C-7] $f 体积过大（${size}B > 5MB），疑似误提交二进制/资源，应加入 .gitignore 或改用 LFS"
    echo x >> "$TMPB"
  fi
done

# ================= 阻断项 C-8：敏感文件类型（通用） =================
# 凭据/私钥/密钥库/认证配置文件一律禁提交。注意：不用 *secret*/*credentials* 通配——那会误伤
# 合法的 SecretService.kt 等源码文件；源码内硬编码凭据由 C-13 高熵/敏感赋值检测兜底。
# 修复：补充 .env 环境变体（.env.local/.env.production 等）与云凭据目录（.aws/credentials）——
# 原 *.env 只能命中 "xxx.env"，匹配不到 .env.local（后缀是 .local）导致漏拦。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    .env|.env.*|*.env|*.env.*|.aws/credentials|.credentials|.secrets|*.pem|*.key|*.keystore|*.jks|*.p12|*.pfx|*id_rsa*|*id_ed25519*|.npmrc|*.pypirc|.htpasswd|.netrc)
      echo "❌ [C-8] $f 为敏感文件类型（凭据/私钥/密钥库/认证配置），禁止提交"
      echo x >> "$TMPB"
    ;;
  esac
done

# ================= 阻断项 C-9：调试残留（通用） =================
# Kotlin 主源码排除测试目录（FileLogger 为项目标准日志，不算调试残留）；
# JS/TS 排除控制台调试语句。均限流输出避免刷屏。
printf '%s\n' "$CHANGED" | while read -r f; do
  [ -f "$ROOT/$f" ] || continue
  case "$f" in
    *.kt)
      case "$f" in
        */src/test/*|*/src/androidTest/*|*/src/testFixtures/*) continue ;;
      esac
      if LC_ALL=C grep -nE 'Log\.([dvi])\(|println\(|debugger;' "$ROOT/$f" 2>/dev/null | grep -v 'FileLogger' | head -5 | grep -q .; then
        echo "❌ [C-9] $f 疑似调试残留（Log.d/v/i / println / debugger），正式代码应使用 FileLogger 或移除"
        echo x >> "$TMPB"
      fi
    ;;
    *.js|*.ts|*.jsx|*.tsx)
      if LC_ALL=C grep -nE 'debugger;|console\.(log|debug)\(|alert\(' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
        echo "❌ [C-9] $f 疑似调试残留（console.log / debugger / alert）"
        echo x >> "$TMPB"
      fi
    ;;
  esac
done

# ================= 阻断项 C-10：Git 仓库中间状态（通用） =================
# 借鉴 claude-commit-skill 的 safety gates：merge/rebase/cherry-pick/revert 进行中时提交，
# 会静默把合并/变基改写为普通提交（MERGE_HEAD 被清掉即丢失合并意图），必须先完成或中止。
if $IS_GIT; then
  GITDIR="$(git -C "$ROOT" rev-parse --git-dir 2>/dev/null || true)"
  [ -n "$GITDIR" ] || GITDIR="$ROOT/.git"
  if [ -f "$GITDIR/MERGE_HEAD" ] || [ -f "$GITDIR/CHERRY_PICK_HEAD" ] || [ -f "$GITDIR/REVERT_HEAD" ] \
     || [ -f "$GITDIR/rebase-merge/HEAD" ] || [ -f "$GITDIR/rebase-apply/HEAD" ] \
     || [ -d "$GITDIR/rebase-merge" ] || [ -d "$GITDIR/rebase-apply" ]; then
    echo "❌ [C-10] 仓库正处于 merge/rebase/cherry-pick/revert 中间状态，此刻提交会破坏合并/变基历史；请先完成（git merge --continue / rebase --continue）或中止（git merge --abort / rebase --abort）"
    BLOCKERS=$((BLOCKERS+1))
  fi
fi

# ================= 阻断项 C-11：技能资产 frontmatter 完整性（通用） =================
# 借鉴 lint-skills：新增/修改 SKILL.md|CLAUDE.md 必须含 name/description frontmatter，
# 否则 SkillParser 解析为缺名/空描述，技能无法被 AI 正确识别与触发。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    */SKILL.md|SKILL.md|*/CLAUDE.md|CLAUDE.md) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  head -30 "$ROOT/$f" 2>/dev/null | LC_ALL=C grep -q '^---' || {
    echo "❌ [C-11] $f 缺少 frontmatter 分隔符（须以 --- 开头并含 name/description）"
    echo x >> "$TMPB"
    continue
  }
  if ! head -30 "$ROOT/$f" 2>/dev/null | LC_ALL=C grep -qE '^[[:space:]]*(name|description):'; then
    echo "❌ [C-11] $f frontmatter 缺 name 或 description（技能无法被正确识别/触发）"
    echo x >> "$TMPB"
  fi
done

# ================= 阻断项 C-12：二进制 / 不可 diff 文件（通用） =================
# 借鉴 pre-commit-hooks / git diff --stat 经验：二进制文件无法在提交 diff 中评审，且常为误提交的
# 编译产物/资源。已跟踪改动用 git diff --numstat（二进制文件两列均为 '-'）；未跟踪新文件用
# grep -I 判二进制（grep 对二进制视为"无匹配"，非空且 grep -I 无输出即为二进制）。
# 媒体/字体等合法二进制资产列入白名单，避免误伤设计资源。
NUMSTAT="$(git -C "$ROOT" diff --numstat HEAD 2>/dev/null || true)"
binlines=$(printf '%s\n' "$NUMSTAT" | LC_ALL=C awk -F'\t' '$1=="-" || $2=="-" {print $3}' | sed '/^$/d' | LC_ALL=C grep -vE '\.(png|jpe?g|gif|webp|ico|pdf|ttf|woff2?|otf|eot|mp4|mp3|wav|ogg)$')
if [ -n "$binlines" ]; then
  while read -r b; do
    echo "❌ [C-12] $b 为二进制/不可 diff 文件（无法在提交中评审）：编译产物应加入 .gitignore，必要资源改用 LFS 或文本化"
    echo x >> "$TMPB"
  done <<EOF
$binlines
EOF
fi
printf '%s\n' "$CHANGED" | while read -r f; do
  [ -f "$ROOT/$f" ] || continue
  case "$f" in
    *.png|*.jpg|*.jpeg|*.gif|*.webp|*.ico|*.pdf|*.ttf|*.woff|*.woff2|*.otf|*.eot|*.mp4|*.mp3|*.wav|*.ogg) continue ;;  # 合法二进制资产白名单
  esac
  # 提速：grep -I 语义不变（文本=匹配、二进制=视为无匹配退出 1），但只采样前 8KB 判定，
  # 避免对超大文本文件整读（与 git 的 NUL 检测口径一致，UTF-8 高字节不算二进制）。
  if [ -s "$ROOT/$f" ] && ! LC_ALL=C head -c 8192 "$ROOT/$f" 2>/dev/null | LC_ALL=C grep -Iq . 2>/dev/null; then
    echo "❌ [C-12] $f 为二进制/不可 diff 文件（无法在提交中评审），建议加入 .gitignore 或改用文本/外部资源"
    echo x >> "$TMPB"
  fi
done

# ================= 建议项 C-13：高熵密钥（随机形态兜底）（通用） =================
# 借鉴 gitleaks/detect-secrets 的「正则 + 熵」双层思想：正则只防已知前缀（C-4），随机生成、
# 无已知前缀的密钥需熵检测兜底。高熵长串（>=28 字符且 Shannon 熵 >=4.6）为随机密钥常见形态，
# 但也可能是 base64/长 URL/UUID，故仅作警告提示人工确认。先 grep 快速过滤无长串文件再跑熵计算（提效）。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/assets/skills/*) continue ;;   # 技能资产本身含安全正则字面量，跳过避免自引用误报
    package-lock.json|yarn.lock|pnpm-lock.yaml|poetry.lock|Pipfile.lock|Cargo.lock|composer.lock|Gemfile.lock|*.min.js|*.min.css|*.min.js.map) continue ;;  # 锁定文件含高熵 integrity/URL、压缩产物含长串，均非密钥
  esac
  case "$f" in
    */src/test/*|*/src/androidTest/*|*/test/*|*/tests/*|*.test.js|*.test.ts|*.spec.js|*.spec.ts) continue ;;  # 测试随机数据常触发高熵，跳过降噪
  esac
  case "$f" in
    *.kt|*.kts|*.java|*.js|*.ts|*.py|*.go|*.rs|*.sh|*.properties|*.json|*.yml|*.yaml|*.xml|*.gradle|*.toml|*.env) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  # 快速预筛：无 28+ 长串的文件直接跳过，避免对每个文件都跑 awk 熵计算
  if ! LC_ALL=C grep -qE '[A-Za-z0-9+/=_-]{28,}' "$ROOT/$f" 2>/dev/null; then continue; fi
  # busybox awk 计算 Shannon 熵，限 3 处命中即停止
  if LC_ALL=C awk '
    {
      while (match($0, /[A-Za-z0-9+=\/_-]{28,}/)) {
        t = substr($0, RSTART, RLENGTH)
        len = length(t)
        delete f
        for (i = 1; i <= len; i++) { c = substr(t, i, 1); f[c]++ }
        e = 0
        for (ch in f) { p = f[ch] / len; e -= p * log(p) / log(2) }
        if (e >= 4.6) { hit = 1; if (++n >= 3) exit }
        $0 = substr($0, RSTART + RLENGTH)
      }
    }
    END { exit !hit }
  ' "$ROOT/$f" 2>/dev/null; then
    echo "⚠️  [C-13] $f 存在高熵长串（疑似随机生成密钥），若为真实凭据请移入密钥管理，若为示例/数据请忽略"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-4：提交信息格式建议（通用） =================
# 优先级阶梯（高→低）：feature 代码→feat；纯测试→test；CI 配置→ci；纯文档→docs；
# 构建配置→build；其余源码→fix；否则 chore。修复「先 feat 后又被 fix 覆盖」的顺序 bug。
suggested_type="chore"
if $IS_ANDROID; then
  if has_pref '^app/src/main/java/com/R/codecore/feature/'; then
    suggested_type="feat"
  elif has_pref '(^app/src/test/|^app/src/androidTest/)'; then
    suggested_type="test"
  elif has_pref '(^\.github/|^\.githooks/)'; then
    suggested_type="ci"
  elif has_pref '^docs/' && ! has_pref '\.(kt|kts|java)$'; then
    suggested_type="docs"
  elif has_pref '(^app/build\.gradle\.kts$|^build\.gradle\.kts$|^gradle/|^gradle\.libs\.toml$|^settings\.gradle\.kts$|^app/src/main/assets/migrations/)'; then
    suggested_type="build"
  elif has_pref '\.kt$'; then
    suggested_type="fix"
  fi
else
  if has_pref '^\.github/'; then
    suggested_type="ci"
  elif has_pref '^docs/' && ! has_pref '\.(js|ts|py|go|rs|c|cpp|sh|rb)$'; then
    suggested_type="docs"
  elif has_pref '(\.test\.|\.spec\.|/tests/|/__tests__/)'; then
    suggested_type="test"
  elif printf '%s\n' "$CHANGED" | LC_ALL=C grep -E '\.(js|ts|jsx|tsx|css|html|vue)$' | grep -q .; then
    suggested_type="feat"
  elif has_pref '\.(py|go|rs|c|cpp|sh|rb)$'; then
    suggested_type="fix"
  fi
fi
scop=""
if [ -n "${mods:-}" ]; then scop=$(printf '%s' "$mods" | head -1); fi
if [ -n "$scop" ]; then
  echo "⚠️  [W-4] 建议提交信息: $suggested_type($scop): 简述本次改动（type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test；subject 用祈使语气、≤72 字符、句末不加句号；破坏性变更在 type 后加 ! 并在正文写 BREAKING CHANGE: ...；footer 可加 Closes #N 关联 issue）"
else
  echo "⚠️  [W-4] 建议提交信息: $suggested_type: 简述本次改动（Conventional Commits；subject 用祈使语气、≤72 字符、不加句号；可加 body 说明 why、footer 关联 issue）"
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

# ================= 建议项 W-6：大 diff 预算（通用） =================
if $IS_GIT; then
  fcnt="$FCNT"
  lcnt=$(printf '%s\n' "$NUMSTAT" | awk '{s+=$1+$2} END {print s+0}')
  if [ -n "$lcnt" ] && [ "$lcnt" -gt 400 ]; then
    echo "⚠️  [W-6] 本次改动约 $lcnt 行（>400 行会显著降低 review 质量），建议拆分为多个原子提交"
    WARNINGS=$((WARNINGS+1))
  elif [ -n "$fcnt" ] && [ "$fcnt" -gt 40 ]; then
    echo "⚠️  [W-6] 本次改动文件数 $fcnt（>40），建议拆分提交以聚焦 review"
    WARNINGS=$((WARNINGS+1))
  fi
fi

# ================= 建议项 W-7：待办/标记残留（通用） =================
# 跳过技能自身资产目录（run.sh 含正则字面量 TODO|FIXME|HACK|XXX，属自引用误报）
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/assets/skills/*) continue ;;
  esac
  case "$f" in
    *.kt|*.kts|*.java|*.js|*.ts|*.py|*.go|*.rs|*.sh|*.c|*.cpp|*.swift|*.sql) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE 'TODO|FIXME|HACK|XXX' "$ROOT/$f" 2>/dev/null | head -8 | grep -q .; then
    echo "⚠️  [W-7] $f 残留待办标记（TODO/FIXME/HACK/XXX），提交前请确认是否已处理"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-8：提交原子性（通用） =================
if $IS_ANDROID; then
  modcnt=$(printf '%s\n' "$CHANGED" | while read -r f; do
    m=$(printf '%s\n' "$f" | sed -n 's|.*/com/R/codecore/feature/\([^/]*\)/.*|\1|p')
    [ -n "$m" ] && printf '%s\n' "$m"
  done | sort -u | sed '/^$/d' | wc -l | tr -d ' ')
  if [ -n "$modcnt" ] && [ "$modcnt" -ge 3 ]; then
    echo "⚠️  [W-8] 本次改动横跨 $modcnt 个 feature 模块，建议拆分原子提交（一提交一主题），便于 review 与回滚"
    WARNINGS=$((WARNINGS+1))
  fi
fi

# ================= 建议项 W-9 / W-14：文件卫生（行尾空白 / EOF 无换行 / CRLF 混用）（通用） =================
# 借鉴 pre-commit-hooks 的 trailing-whitespace / end-of-file-fixer / mixed-line-ending；仅限文本源码
# 避免二进制误报。W-14（CRLF 混用）与 W-9 合并为同一次遍历，每文件只读一次、减少重复 IO。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    *.kt|*.kts|*.java|*.md|*.xml|*.json|*.sql|*.sh|*.yml|*.yaml|*.js|*.ts|*.py|*.go|*.rs|*.c|*.cpp|*.properties|*.gradle|*.toml|*.css|*.html) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -n '[[:blank:]]$' "$ROOT/$f" 2>/dev/null | head -3 | grep -q .; then
    echo "⚠️  [W-9] $f 存在行尾空白（trailing whitespace），建议清理"
    echo x >> "$TMPW"
  fi
  # 末尾字节不是换行符即视为缺 EOF 换行（含 UTF-8 多字节结尾也准确：有换行时最后字节必为 \n）
  if [ -s "$ROOT/$f" ] && [ "$(tail -c 1 "$ROOT/$f" 2>/dev/null)" != "$(printf '\n')" ]; then
    echo "⚠️  [W-9] $f 文件末尾缺少换行符，建议补上（POSIX 文本文件约定）"
    echo x >> "$TMPW"
  fi
  # W-14：CRLF/Windows 行尾混入会让 shell 脚本/构建产物出诡异 bug（\r 被当作命令/文本一部分），应统一为 LF
  if LC_ALL=C grep -n "$(printf '\r')$" "$ROOT/$f" 2>/dev/null | head -3 | grep -q .; then
    echo "⚠️  [W-14] $f 存在 CRLF/Windows 行尾，建议统一为 LF（\r 混入可能导致脚本/构建异常）"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-10：超长行（可读性）（通用） =================
# 借鉴 flake8 max-line-length；仅对纯 ASCII 行判定（含中文的多字节行长不误报），阈值 240 较宽松，
# 规避长 URL / 长字符串常见场景。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    *.kt|*.kts|*.java|*.js|*.ts|*.py|*.go|*.rs|*.c|*.cpp|*.sh|*.swift|*.sql) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C awk 'length($0) > 240 && $0 ~ /^[ -~]+$/ {c++; if (c>=3) exit} END {exit !c}' "$ROOT/$f" 2>/dev/null; then
    echo "⚠️  [W-10] $f 存在超过 240 字符的纯 ASCII 超长行（影响可读性/部分工具兼容），建议拆分"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-11：游离 HEAD（通用） =================
# detached HEAD 下提交会落在无引用的提交上，分支丢失后难以找回（claude-commit-skill safety gate）。
if $IS_GIT; then
  if ! git -C "$ROOT" symbolic-ref -q HEAD >/dev/null 2>&1; then
    echo "⚠️  [W-11] 当前处于 detached HEAD（游离提交），提交会落在无引用的提交上容易丢失；建议先 git checkout <branch> 再提交"
    WARNINGS=$((WARNINGS+1))
  fi
fi

# ================= 建议项 W-12：依赖锁定文件同步（通用） =================
# 借鉴 hackforla 自检清单/hook 的依赖管理：锁定文件须随 manifest 一并提交，否则他人 install 依赖
# 版本漂移。两个方向都查：a) 锁文件在改动中→确认与清单同步；b) 清单改动但锁文件未随本次提交更新→提示。
LOCKRE='(^|/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|poetry\.lock|Pipfile\.lock|Cargo\.lock|go\.sum|composer\.lock|Gemfile\.lock|gradle\.lockfile)$'
if printf '%s\n' "$CHANGED" | LC_ALL=C grep -E "$LOCKRE" | grep -q .; then
  echo "⚠️  [W-12] 提交含依赖锁定文件：请确认依赖清单（package.json/pyproject.toml/Cargo.toml/go.mod 等）与锁定文件同步并一并提交，避免依赖版本漂移"
  WARNINGS=$((WARNINGS+1))
fi
# b) manifest 改了但对应锁文件存在于仓库且未随本次提交一起更新
printf '%s\n' "$CHANGED" | while read -r mf; do
  case "$mf" in
    */*) d="${mf%/*}" ;;
    *) d="" ;;
  esac
  case "$mf" in
    */package.json|package.json) lkn="package-lock.json" ;;
    */pyproject.toml|pyproject.toml) lkn="poetry.lock" ;;
    */Cargo.toml|Cargo.toml) lkn="Cargo.lock" ;;
    */go.mod|go.mod) lkn="go.sum" ;;
    */Gemfile|Gemfile) lkn="Gemfile.lock" ;;
    *) continue ;;
  esac
  if [ -n "$d" ]; then lock="$d/$lkn"; else lock="$lkn"; fi
  if [ -f "$ROOT/$lock" ] && ! printf '%s\n' "$CHANGED" | LC_ALL=C grep -Fxq "$lock"; then
    echo "⚠️  [W-12] 依赖清单 $mf 已改动，但锁定文件 $lock 未随本次提交同步更新，建议一并更新提交（依赖版本漂移风险）"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-13：产物/敏感文件未被 .gitignore 覆盖（通用） =================
# 借鉴「分层防御」：C-7/C-8 阻断过的产物/密钥若未纳入 .gitignore，下轮仍会误提交；用 git check-ignore 精确判定。
if $IS_GIT; then
  printf '%s\n' "$CHANGED" | while read -r f; do
    case "$f" in
      build/*|*/build/*|.gradle/*|.idea/*|node_modules/*|.next/*|*.apk|*.aab|*.iml|local.properties|*.log|Thumbs.db|.DS_Store|*.zip|*.tar|*.tar.gz|*.tgz|*.bin|*.so \
        |*.jar|*.class|*.o|*.a|*.dll|*.dylib|*.exe|*.pyc|*.pyo|*.tmp|*.bak|*.orig|*.rej|*.swp|*~ \
        |.env|.env.*|*.env|*.env.*|.aws/credentials|.credentials|.secrets|*.pem|*.key|*.keystore|*.jks|*.p12|*.pfx|*id_rsa*|*id_ed25519*|.npmrc|*.pypirc|.htpasswd|.netrc) : ;;
      *) continue ;;
    esac
    if ! git -C "$ROOT" check-ignore -q "$f" 2>/dev/null; then
      echo "⚠️  [W-13] $f 属于易误提交的产物/敏感文件且未被 .gitignore 忽略，建议补充对应规则（git check-ignore 判定）"
      echo x >> "$TMPW"
    fi
  done
fi

# ================= 建议项 W-15：大小写冲突（通用） =================
# 借鉴 pre-commit-hooks 的 check-case-conflict：同路径仅大小写不同的文件对，在 macOS/Windows
# 大小写不敏感文件系统上会互相覆盖/无法 checkout。
caseconf=$(printf '%s\n' "$CHANGED" | LC_ALL=C sort -f | LC_ALL=C uniq -di)
if [ -n "$caseconf" ]; then
  echo "⚠️  [W-15] 改动中存在仅大小写不同的文件（在大小写不敏感文件系统会冲突）："
  printf '%s\n' "$caseconf" | while read -r f; do
    [ -n "$f" ] && echo "    - $f"
  done
  echo x >> "$TMPW"
fi

# ================= 建议项 W-16：损坏符号链接（通用） =================
# 借鉴 pre-commit-hooks 的 check-symlinks：指向不存在目标的 symlink 会破坏构建/打包/部署。
printf '%s\n' "$CHANGED" | while read -r f; do
  if [ -L "$ROOT/$f" ] && [ ! -e "$ROOT/$f" ]; then
    echo "⚠️  [W-16] $f 为指向不存在目标的符号链接（broken symlink），建议改为真实文件或修正目标"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-17：AI 引用/剪贴残留（通用） =================
# 借鉴 IBM mcp-context-forge 的 forbid 系列钩子：从 AI 工具复制内容时常见的 oaicite/filecite
# 引用与 :contentReference 标记，属不可解析的源追踪残留，提交前应删除。仅扫文档类文件，跳过技能/prompts 资产。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/assets/skills/*|app/src/main/assets/prompts/*|app/src/main/assets/docs/*) continue ;;
  esac
  case "$f" in
    *.md|*.markdown|*.txt|*.html) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE '\[oaicite:[0-9?]|\[filecite:|:contentReference' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
    echo "⚠️  [W-17] $f 残留 AI 引用/剪贴标记（[oaicite:/[filecite:/:contentReference），建议删除后再提交"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-18：子模块 / 嵌套仓库（通用） =================
# 借鉴 pre-commit-hooks 的 forbid-new-submodules：.gitmodules 变更 = 子模块集合/指针变化，需连同父仓库
# 一起提交；改动文件位于嵌套 git 仓库内则说明子模块未正确登记，clone 后内容会缺失。
if $IS_GIT; then
  if has_pref '(^|/)\.gitmodules$'; then
    echo "⚠️  [W-18] 改动含 .gitmodules（子模块集合/指针变化）：请确认父仓库引用已一并更新并提交"
    echo x >> "$TMPW"
  fi
  printf '%s\n' "$CHANGED" | while read -r f; do
    case "$f" in */*) : ;; *) continue ;; esac   # 仅路径含子目录才可能是嵌套仓库
    d="${f%/*}"
    while [ -n "$d" ]; do
      if [ -e "$ROOT/$d/.git" ]; then
        echo "⚠️  [W-18] $f 位于嵌套 git 仓库 $d/ 内（子模块未登记/嵌套仓库），建议改为 submodule 或加入 .gitignore"
        echo x >> "$TMPW"
        break
      fi
      case "$d" in */*) d="${d%/*}" ;; *) d="" ;; esac
    done
  done
fi

# ================= 建议项 W-19：硬编码绝对路径（通用） =================
# 借鉴 commit-audit 的 hardcoded paths 检查：本机绝对路径泄漏用户名且不可移植，打包/部署到其他环境
# 即失效。仅扫源码类，跳过 .md/.txt 文档（文档常合法引用示例路径）与技能资产（run.sh 含 /root/workspace 契约）。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/assets/skills/*) continue ;;
  esac
  case "$f" in
    *.kt|*.kts|*.java|*.js|*.ts|*.py|*.go|*.rs|*.sh|*.c|*.cpp|*.swift|*.gradle|*.yml|*.yaml|*.toml|*.properties|*.json|*.xml) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE '(/home/[A-Za-z0-9_.-]+|/Users/[A-Za-z0-9_.-]+|/Users/Shared/|/private/var/|/root/|C:\\\\Users\\\\)' "$ROOT/$f" 2>/dev/null | head -3 | grep -q .; then
    echo "⚠️  [W-19] $f 疑似硬编码本机绝对路径（/home/|/Users/|/root/|C:\\Users\\ 等），建议改为相对路径或环境变量（可移植性/防泄漏用户名）"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-20：shebang 一致性（通用） =================
# 借鉴 pre-commit-hooks 的 check-executables-have-shebangs / check-shebang-scripts-are-executable：
# 可执行脚本缺 shebang（内核无法定位解释器直接执行），或带 shebang 的脚本未设可执行位（无法 ./ 运行）。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    *.sh|*.bash|*.py|*.pl|*.rb|*.awk) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  first="$(head -1 "$ROOT/$f" 2>/dev/null)"
  if [ -x "$ROOT/$f" ]; then
    case "$first" in
      \#!*) : ;;
      *) echo "⚠️  [W-20] $f 具有可执行位但缺少 shebang（#!/...），直接执行会失败，建议补 shebang 或去除执行位"
         echo x >> "$TMPW" ;;
    esac
  else
    case "$first" in
      \#!*)
        echo "⚠️  [W-20] $f 带 shebang 但未设可执行位（chmod +x），无法作为脚本直接运行"
        echo x >> "$TMPW" ;;
    esac
  fi
done

# ================= 建议项 W-21：编码/结构化文件雷区（通用，零依赖启发式） =================
# 借鉴 pre-commit-hooks 的 fix-encoding-pragma / check-json / check-yaml：a) UTF-8 BOM 头会让
# shebang/解析器误判；b) JSON 尾逗号（,} / ,]）违反 JSON 规范导致解析失败（极常见笔误）；
# c) YAML 缩进用 Tab（YAML 规范禁止，busybox/工具解析报错）。均为纯正则/字节启发式，零额外依赖。
BOM="$(printf '\357\273\277')"   # EF BB BF（POSIX printf 八进制转义，busybox 兼容）
printf '%s\n' "$CHANGED" | while read -r f; do
  [ -f "$ROOT/$f" ] || continue
  case "$f" in
    *.json|*.yaml|*.yml|*.md|*.kt|*.kts|*.java|*.js|*.ts|*.py|*.sh|*.gradle|*.toml|*.xml|*.properties) : ;;
    *) continue ;;
  esac
  if [ "$(head -c 3 "$ROOT/$f" 2>/dev/null)" = "$BOM" ]; then
    echo "⚠️  [W-21] $f 带 UTF-8 BOM 头（EF BB BF），可能导致 shebang/解析器误判，建议去除"
    echo x >> "$TMPW"
  fi
  case "$f" in
    *.json)
      if LC_ALL=C grep -nE ',[[:space:]]*[}\]]' "$ROOT/$f" 2>/dev/null | head -3 | grep -q .; then
        echo "⚠️  [W-21] $f 疑似 JSON 尾逗号（,} 或 ,]），JSON 规范不允许，解析会失败"
        echo x >> "$TMPW"
      fi
    ;;
    *.yaml|*.yml)
      TAB="$(printf '\t')"
      if LC_ALL=C grep -n "$TAB" "$ROOT/$f" 2>/dev/null | head -3 | grep -q .; then
        echo "⚠️  [W-21] $f 含 Tab 字符（YAML 禁止 Tab 缩进，解析可能报错），建议改为空格缩进"
        echo x >> "$TMPW"
      fi
    ;;
  esac
done

# ================= 建议项 W-22：依赖版本未锁定（可复现性）（通用） =================
# 借鉴 pre-commit / hackforla 自检的「锁定依赖版本」：package.json 用 ^/~/* 前缀、Gradle 用 + 动态
# 版本、requirements.txt/Pipfile 未用 == 锁定，都会导致「今天能装、明天装不上或装到不同版本」，
# 破坏可复现构建与审计。与 W-12（锁定文件同步）互补：W-12 看锁文件是否存在/同步，W-22 看清单是否锁版本。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    */package.json|package.json)
      [ -f "$ROOT/$f" ] || continue
      if LC_ALL=C grep -nE '"[A-Za-z0-9_@./-]+"[[:space:]]*:[[:space:]]*"[~^*][0-9A-Za-z.+-]+"' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
        echo "⚠️  [W-22] $f 存在未精确锁定的依赖版本（^/~/* 前缀，如 \"^4.17.0\"），建议锁定精确版本以保证可复现构建"
        echo x >> "$TMPW"
      fi
    ;;
    *.gradle.kts|*.gradle)
      [ -f "$ROOT/$f" ] || continue
      if LC_ALL=C grep -nE 'version[[:space:]]*=[[:space:]]*"[^"]*[+][^"]*"|version[[:space:]]*=[[:space:]]*"[^"]*latest[.][^"]*"' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
        echo "⚠️  [W-22] $f 使用动态依赖版本（+ 后缀或 latest.*），建议锁定具体版本号保证可复现构建"
        echo x >> "$TMPW"
      fi
    ;;
    *gradle/libs.versions.toml|gradle/libs.versions.toml|libs.versions.toml)
      [ -f "$ROOT/$f" ] || continue
      if LC_ALL=C grep -nE '"[^"]*[+][^"]*"' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
        echo "⚠️  [W-22] $f 的版本目录存在动态版本（+ 后缀），建议锁定具体版本号保证可复现构建"
        echo x >> "$TMPW"
      fi
    ;;
    */requirements.txt|requirements.txt|*/Pipfile|Pipfile)
      [ -f "$ROOT/$f" ] || continue
      if LC_ALL=C grep -nE '^[A-Za-z0-9_.-]+[[:space:]]*$|^[A-Za-z0-9_.-]+[[:space:]]*(>=|>|~=|~)[0-9]' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
        echo "⚠️  [W-22] $f 存在未用 == 精确锁定的 Python 依赖，建议改用 == 锁定版本保证可复现"
        echo x >> "$TMPW"
      fi
    ;;
  esac
done

# ================= 建议项 W-23：大删除/大改面确认（通用） =================
# 借鉴「review before committing」：一次删除大量行/文件（重写、迁移、误删）是提交前最需人工确认的
# 高风险动作——整文件替换常掩盖意图，误删可能破坏引用。给出量化提示，不强制阻断。
if $IS_GIT; then
  deltot="$(printf '%s\n' "$NUMSTAT" | LC_ALL=C awk '{s+=$2} END {print s+0}')"
  if [ -n "$deltot" ] && [ "$deltot" -gt 300 ]; then
    echo "⚠️  [W-23] 本次改动共删除约 $deltot 行（删除量大）：请确认是有意的重构/迁移而非误删，必要时拆分提交"
    echo x >> "$TMPW"
  fi
  delfiles="$(git -c core.quotepath=false -C "$ROOT" diff --name-only --diff-filter=D HEAD 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  if [ -n "$delfiles" ] && [ "$delfiles" -ge 10 ]; then
    echo "⚠️  [W-23] 本次改动删除 $delfiles 个文件（整文件删除）：请确认是有意移除，避免误删仍被引用的文件"
    echo x >> "$TMPW"
  fi
fi

# ================= 建议项 W-24：.gitattributes 换行/二进制归一化缺失（通用） =================
# 借鉴 .gitattributes 最佳实践：换行归一化（* text=auto eol=lf）与二进制声明（*.png binary）能避免
# 跨平台噪音 diff 与误合并。仅当仓库已有 .gitignore（说明在意仓库卫生）却缺 .gitattributes 时提示，
# 属可选建议，不强求。
if $IS_GIT && [ -f "$ROOT/.gitignore" ] && [ ! -f "$ROOT/.gitattributes" ]; then
  echo "⚠️  [W-24] 仓库有 .gitignore 但缺 .gitattributes：建议添加换行归一化（* text=auto eol=lf）与二进制声明（*.png binary 等），避免跨平台噪音 diff（可选）"
  WARNINGS=$((WARNINGS+1))
fi

# ================= 建议项 W-25：GitHub Actions 工作流供应链安全（通用） =================
# 借鉴 zizmor / actionlint / safeguard.sh 的 workflow 安全审计与 pre-commit 的「pin rev」纪律：
# a) uses: owner/repo@main|@master|@vN 未固定到完整 commit SHA，上游改动静默影响全团队；
# b) pull_request_target 事件 + actions/checkout 未指定 ref:，会在可信上下文中检出不可信 PR 代码执行
#    （最危险的 workflow 模式之一）；
# c) run: 内 curl|wget 直接管道给 sh/bash 执行远程脚本，是最常见供应链投毒入口。
# 仅扫 .github/workflows/ 下的 YAML，纯正则启发式、零额外依赖。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    .github/workflows/*.yml|.github/workflows/*.yaml) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  # a) action 引用未固定到完整 SHA（main/master/vN/短 SHA 均视为未固定）
  if LC_ALL=C grep -nE 'uses:[[:space:]]+[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@(main|master|dev|[vV][0-9]+(\.[0-9]+)*|release|[0-9a-f]{7})' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
    echo "⚠️  [W-25] $f 引用 action 未固定到完整 commit SHA（uses: ...@main/@v1/@短SHA），建议固定到完整 40 位 SHA，防上游改动影响构建（供应链安全）"
    echo x >> "$TMPW"
  fi
  # b) pull_request_target + actions/checkout 但无 ref: 显式锁定（高危）
  # YAML 事件名后为冒号（pull_request_target:），事件列表场景为换行，两种都要匹配。
  if LC_ALL=C grep -qE '^[[:space:]]*pull_request_target([[:space:]:]|$)' "$ROOT/$f" 2>/dev/null \
     && LC_ALL=C grep -qE 'uses:[[:space:]]+actions/checkout' "$ROOT/$f" 2>/dev/null \
     && ! LC_ALL=C grep -qE '[[:space:]]ref:[[:space:]]+' "$ROOT/$f" 2>/dev/null; then
    echo "⚠️  [W-25] $f 在 pull_request_target 事件中使用 actions/checkout 且未指定 ref:，会在可信上下文中执行不可信 PR 代码（高危，建议用 ref: 固定分支或改用 pull_request 事件）"
    echo x >> "$TMPW"
  fi
  # c) 远程脚本管道给 shell 执行
  if LC_ALL=C grep -nE '\|[[:space:]]*(sudo[[:space:]]+)?(ba)?sh([[:space:]]|$)|wget[[:space:]]+(-qO-|-O-)[[:space:]]+' "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
    echo "⚠️  [W-25] $f 存在将远程脚本直接管道给 shell 执行（curl|sh 等），供应链投毒高风险，建议下载后校验哈希/签名再执行"
    echo x >> "$TMPW"
  fi
done

# ================= 建议项 W-26：硬编码内网/私有 IP（通用） =================
# 借鉴 hook 实战的「内网 IP 也是敏感信息」：源码硬编码 10.x / 192.168.x / 172.16-31.x / 127.0.0.1 /
# 169.254.x 等内网地址，会泄漏网络拓扑、换环境即失效，且易被扫描器当作资产信息收集。
# 仅扫源码类文件（跳过 .md 文档，文档常示例 IP），左右加数字边界避免误匹配版本号/时间戳。
printf '%s\n' "$CHANGED" | while read -r f; do
  case "$f" in
    app/src/main/assets/skills/*) continue ;;
  esac
  case "$f" in
    *.kt|*.kts|*.java|*.js|*.ts|*.py|*.go|*.rs|*.sh|*.c|*.cpp|*.gradle|*.yml|*.yaml|*.toml|*.properties|*.json|*.xml|*.sql) : ;;
    *) continue ;;
  esac
  [ -f "$ROOT/$f" ] || continue
  if LC_ALL=C grep -nE \
    -e '(^|[^0-9])10\.([0-9]{1,3}\.){2}[0-9]{1,3}([^0-9]|$)' \
    -e '(^|[^0-9])192\.168\.([0-9]{1,3}\.)[0-9]{1,3}([^0-9]|$)' \
    -e '(^|[^0-9])172\.(1[6-9]|2[0-9]|3[01])\.([0-9]{1,3}\.)[0-9]{1,3}([^0-9]|$)' \
    -e '(^|[^0-9])127\.0\.0\.1([^0-9]|$)' \
    -e '(^|[^0-9])169\.254\.([0-9]{1,3}\.)[0-9]{1,3}([^0-9]|$)' \
    "$ROOT/$f" 2>/dev/null | head -5 | grep -q .; then
    echo "⚠️  [W-26] $f 疑似硬编码内网/私有 IP（10./192.168./172.16-31./127.0.0.1/169.254.），建议改为配置/环境变量（防拓扑泄漏与环境迁移失效）"
    echo x >> "$TMPW"
  fi
done

echo "=================================================="
# 合并管道子 shell 累计的阻断项/建议项（C-2/C-4/C-6/C-7/C-8/C-9/C-11/C-12 走 TMPB；
# C-13/W-7/W-9/W-10/W-12/W-13/W-14/W-15/W-16/W-17/W-21/W-22/W-23/W-25/W-26 走 TMPW；其余走外层 BLOCKERS/WARNINGS 计数）
if [ -f "$TMPB" ]; then
  tb=$(grep -c '' "$TMPB" 2>/dev/null || true)
  BLOCKERS=$((BLOCKERS + tb))
fi
if [ -f "$TMPW" ]; then
  tw=$(grep -c '' "$TMPW" 2>/dev/null || true)
  WARNINGS=$((WARNINGS + tw))
fi
rm -f "$TMPB" "$TMPW"
echo "汇总: 阻断项=$BLOCKERS, 建议项=$WARNINGS"
if [ "$BLOCKERS" -gt 0 ]; then
  echo "结论: 存在阻断项，请按报告逐条修复后重跑本技能，直至阻断项=0 再提交。"
  exit 1
else
  echo "结论: 无阻断项，可提交。提交信息格式: <type>(<scope>): <subject>"
fi
exit 0
