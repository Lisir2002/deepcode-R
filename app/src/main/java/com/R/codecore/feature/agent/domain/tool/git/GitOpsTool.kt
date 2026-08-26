package com.R.codecore.feature.agent.domain.tool.git

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.git.domain.GitRepository
import com.R.codecore.feature.git.domain.GitCommandFailureException
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Git 工程化工具：把仓库规范、提交纪律、发版流程、版本日志生成等 Git 相关经验
 * 沉淀为 Agent 可调用的工具。面向开发者日常与 AI Agent 自动调用双向场景。
 *
 * 子命令（action）：
 *   - check_commit(msg)      Conventional Commits 合规校验
 *   - suggest_commit()       基于 status + diff 生成提交信息建议
 *   - hooks_status()         本地 git hooks 启用状态
 *   - release_check(version) 发版前体检 + RC 判定建议
 *   - release_tag(version)   本地打 Tag（推送由外部 Bash 完成）
 *   - changelog(prevTag)     从 git log 自动生成版本日志草稿
 *
 * 经验来源（零重写，全复用）：
 *   - 提交规范：.githooks/commit-msg 的 type/scope 枚举与正则
 *   - 发版流程：AGENTS.md「发版流程（RC 判定）」 + docs/ci-release.md
 *   - 版本日志：CHANGELOG.md 六类分类约定（Keep a Changelog）
 *   - 命令安全：GitRepository 的 shellQuote 注入防护 + 读/写分离错误语义
 *
 * 推送说明：release_tag 仅在本地创建 tag，推送（git push origin <tag>）交给外部 Bash，
 *   凭据由 credential.helper=store 统一注入。本工具不重复实现推送鉴权。
 */
class GitOpsTool @Inject constructor(
    private val gitRepository: GitRepository,
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool() {

    private companion object {
        const val TAG = "GitOpsTool"

        /** Conventional Commits 允许的 type，对齐 .githooks/commit-msg。 */
        private val TYPES = listOf(
            "feat", "fix", "refactor", "docs", "style",
            "chore", "ci", "build", "perf", "test"
        )

        /** 建议 scope（与 AGENTS.md 提交规范对齐）。 */
        private val RECOMMENDED_SCOPES = listOf(
            "agent", "settings", "terminal", "workspace",
            "git", "ui", "mcp", "db", "core", "docs", "build", "deps"
        )

        /** Conventional Commits 正则：^<type>(<scope>)?[!]?: <subject>，:后至少一个非空字符。 */
        private val COMMIT_REGEX = Regex("^(${TYPES.joinToString("|")})(\\([A-Za-z0-9._-]+\\))?!?: .+")

        /** 自动生成的提交信息中，title 最大长度（subject 建议 <= 72）。 */
        private const val MAX_SUGGEST_SUBJECT_LENGTH = 72

        /** tag 名必须匹配 vX.Y.Z 或 vX.Y.Z-rcN/-beta/-alpha/-dev 语义化版本。 */
        private val TAG_REGEX = Regex("""^v\d+\.\d+\.\d+(?:-(?:rc|beta|alpha|dev)\d+)?$""")

        /** CHANGELOG.md 六类分类。 */
        private val CHANGELOG_SECTIONS = listOf(
            "Added（新增）",
            "Improved（改进）",
            "Fixed（修复）",
            "Changed（变更）",
            "Removed（删除）",
            "Adjusted（调整）"
        )
    }

    override val name = "gitops"
    override val description = buildString {
        append("Git 工程化工具：提交规范校验、建议生成、hooks 状态、发版前体检/打 Tag、版本日志生成。")
        append("action 取值：check_commit / suggest_commit / hooks_status / release_check / release_tag / changelog。")
        append("示例：{\"action\":\"check_commit\",\"message\":\"feat(agent): 新增流式工具调用\"}。")
    }
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "要执行的操作：check_commit / suggest_commit / hooks_status / release_check / release_tag / changelog",
            enum = listOf(
                "check_commit", "suggest_commit", "hooks_status",
                "release_check", "release_tag", "changelog"
            )
        ),
        "message" to ToolParameter(
            "message", ParameterType.STRING,
            "check_commit 时的提交信息，release_tag 时的 tag 名（如 v1.2.3）",
            required = false
        ),
        "version" to ToolParameter(
            "version", ParameterType.STRING,
            "release_check / release_tag 时的目标版本号（如 v1.2.3 或 v1.2.3-rc1）",
            required = false
        ),
        "prev_tag" to ToolParameter(
            "prev_tag", ParameterType.STRING,
            "changelog 时的起始 tag（含），缺省自动取最近的历史 tag",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("缺少 action", "MISSING_ACTION")
        return try {
            when (action) {
                "check_commit" -> doCheckCommit(args)
                "suggest_commit" -> doSuggestCommit()
                "hooks_status" -> doHooksStatus()
                "release_check" -> doReleaseCheck(args)
                "release_tag" -> doReleaseTag(args)
                "changelog" -> doChangelog(args)
                else -> errorResult("未知 action：$action", "UNSUPPORTED_ACTION")
            }
        } catch (e: GitCommandFailureException) {
            FileLogger.w(TAG, "gitops[$action] 写命令失败", e)
            errorResult("git 命令失败：${e.message}", "GIT_COMMAND_FAILED")
        } catch (e: Exception) {
            FileLogger.w(TAG, "gitops[$action] 未知错误", e)
            errorResult("执行失败：${e.message}", "GITOPS_FAILED")
        }
    }

    // ─────────────────────── A. 提交规范化 ───────────────────────

    /** A1: Conventional Commits 校验，对齐 .githooks/commit-msg 规则。 */
    private suspend fun doCheckCommit(args: Map<String, JsonElement>): ToolResult {
        val message = args["message"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("check_commit 缺少 message", "MISSING_MESSAGE")
        val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isBlank()) return errorResult("提交信息为空", "EMPTY_MESSAGE")

        // 跳过自动生成的提交（merge/revert/fixup/squash）
        val skipped = firstLine.startsWith("Merge ") ||
                firstLine.startsWith("Revert ") ||
                firstLine.startsWith("fixup!") ||
                firstLine.startsWith("squash!")
        if (skipped) {
            return success(
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("skipped", JsonPrimitive(true))
                    put("reason", JsonPrimitive("自动生成的提交（merge/revert/fixup/squash），跳过校验"))
                }
            )
        }

        val matched = COMMIT_REGEX.matches(firstLine)
        val type = firstLine.substringBefore("(", firstLine.substringBefore(":"))
            .let { if (it.contains("(")) it.substringBefore("(") else it }
            .takeIf { it in TYPES }
        val subject = firstLine.substringAfter(":", "").trim()
        val subjectOk = subject.isNotEmpty()

        return success(
            buildJsonObject {
                put("ok", JsonPrimitive(matched && subjectOk))
                put("first_line", JsonPrimitive(firstLine))
                put("matched", JsonPrimitive(matched))
                put("type", JsonPrimitive(type ?: ""))
                put("allowed_types", JsonArray(TYPES.map { JsonPrimitive(it) }))
                put("recommended_scopes", JsonArray(RECOMMENDED_SCOPES.map { JsonPrimitive(it) }))
                if (!matched) {
                    put(
                        "hint",
                        JsonPrimitive("不符合 <type>(<scope>): <subject>；可用 type 见 allowed_types；scope 建议参考 recommended_scopes")
                    )
                } else if (!subjectOk) {
                    put("hint", JsonPrimitive("':' 后必须至少跟一个非空字符作为 subject"))
                }
            }
        )
    }

    /** A2: 基于 status + diff 生成提交信息建议。 */
    private suspend fun doSuggestCommit(): ToolResult {
        if (!gitRepository.isRepo()) {
            return errorResult("当前工作区不是 git 仓库", "NOT_A_GIT_REPO")
        }
        val status = gitRepository.status()
        if (!status.hasChanges) {
            return success(
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("has_changes", JsonPrimitive(false))
                    put("suggestion", JsonPrimitive("无待提交改动，无需提交"))
                }
            )
        }

        val added = (status.staged + status.unstaged).filter { it.statusCode == "A" }.size
        val deleted = (status.staged + status.unstaged).filter { it.statusCode == "D" }.size
        val modified = (status.staged + status.unstaged).filter { it.statusCode == "M" }.size

        // 推断 type：新增为主 → feat；修改/修复为主 → fix；纯删除 → fix；否则 refactor
        val type = when {
            added > 0 && modified == 0 && deleted == 0 -> "feat"
            deleted > 0 -> "fix"
            modified >= added && modified >= deleted -> "fix"
            added > modified -> "feat"
            else -> "refactor"
        }

        val scope = inferScope(status.staged + status.unstaged)
        val changeSummary = buildString {
            if (added > 0) append("+$added")
            if (added > 0 && (modified > 0 || deleted > 0)) append(",")
            if (modified > 0) append("~$modified")
            if (modified > 0 && deleted > 0) append(",")
            if (deleted > 0) append("-$deleted")
        }

        val rawSubject = "更新改动（+$added 新增, ~$modified 修改, -$deleted 删除）"
        val subject = rawSubject.take(MAX_SUGGEST_SUBJECT_LENGTH)
        val suggested = if (scope.isNotBlank()) "$type($scope): $subject" else "$type: $subject"

        val statusSummary = buildJsonObject {
            put("branch", JsonPrimitive(status.branch))
            put("ahead", JsonPrimitive(status.ahead))
            put("behind", JsonPrimitive(status.behind))
            put("staged", JsonPrimitive(status.staged.size))
            put("unstaged", JsonPrimitive(status.unstaged.size))
            put("untracked", JsonPrimitive(status.untracked.size))
            put("summary", JsonPrimitive(changeSummary))
        }

        return success(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("suggested_type", JsonPrimitive(type))
                put("suggested_scope", JsonPrimitive(scope))
                put("suggested", JsonPrimitive(suggested))
                put("status", statusSummary)
                put("next_steps", JsonArray(listOf(
                    JsonPrimitive("1. 校验建议提交信息：gitops check_commit --message '$suggested'"),
                    JsonPrimitive("2. 按需要调整 type/scope/subject 后执行 git commit -m '<msg>'"),
                    JsonPrimitive("3. 推送前跑单测：./gradlew :app:testReleaseUnitTest")
                )))
            }
        )
    }

    /**
     * 基于改动的文件路径集合推断 scope。命中 feature/<name>/ 就用 <name>，
     * 否则取最常见的一级 feature 名；都不命中返回空（表示不建议写 scope）。
     */
    private fun inferScope(changes: List<com.R.codecore.feature.git.domain.model.GitFileChange>): String {
        val featureRegex = Regex("""feature/([^/]+)/""")
        val scopes = mutableMapOf<String, Int>()
        for (c in changes) {
            featureRegex.find(c.path)?.groupValues?.get(1)?.let { s ->
                scopes[s] = (scopes[s] ?: 0) + 1
            }
        }
        if (scopes.isEmpty()) return ""
        return scopes.maxByOrNull { it.value }?.key.orEmpty()
    }

    /** A3: 本地 git hooks 启用状态。 */
    private suspend fun doHooksStatus(): ToolResult {
        if (!gitRepository.isRepo()) {
            return errorResult("当前工作区不是 git 仓库", "NOT_A_GIT_REPO")
        }
        val hooksPath = runCatching {
            commandEngine.runCommandSync(
                "git config --get core.hooksPath",
                workspaceRepository.currentPath()
            ).trim()
        }.getOrDefault("")

        val expected = ".githooks"
        val enabled = hooksPath.endsWith(expected) || hooksPath == expected
        val advice = if (enabled) {
            "hooks 已启用（core.hooksPath=$hooksPath）"
        } else {
            "未检测到 hooks 启用。在仓库根执行：git config core.hooksPath .githooks"
        }

        return success(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("enabled", JsonPrimitive(enabled))
                put("hooks_path", JsonPrimitive(hooksPath))
                put("expected", JsonPrimitive(expected))
                put("advice", JsonPrimitive(advice))
                put("hooks", JsonArray(listOf(
                    buildJsonObject {
                        put("name", JsonPrimitive("commit-msg"))
                        put("purpose", JsonPrimitive("Conventional Commits 校验"))
                        put("enabled", JsonPrimitive(enabled))
                    },
                    buildJsonObject {
                        put("name", JsonPrimitive("pre-commit"))
                        put("purpose", JsonPrimitive("feature ↔ 模块文档同步 + Spec 规范驱动预检"))
                        put("enabled", JsonPrimitive(enabled))
                    }
                )))
            }
        )
    }

    // ─────────────────────── B. 发版自动化 ───────────────────────

    /**
     * B1: 发版前体检。依据 AGENTS.md「发版流程（RC 判定）」：
     *   - 分支必须是 main（非 main 拒绝）
     *   - 工作区必须干净
     *   - 输出最近一个 tag 与变更条数
     *   - 按 version 后缀（rcN / 无后缀）与改动面自动判定 RC/正式发版
     */
    private suspend fun doReleaseCheck(args: Map<String, JsonElement>): ToolResult {
        if (!gitRepository.isRepo()) {
            return errorResult("当前工作区不是 git 仓库", "NOT_A_GIT_REPO")
        }
        val version = args["version"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("release_check 缺少 version（如 v1.2.3）", "MISSING_VERSION")
        if (!TAG_REGEX.matches(version)) {
            return errorResult(
                "version 格式不符（需 vX.Y.Z 或 vX.Y.Z-rcN/-beta/-alpha/-dev）：$version",
                "INVALID_VERSION"
            )
        }

        val status = gitRepository.status()
        val currentBranch = status.branch
        val mainOk = currentBranch == "main"
        val cleanOk = !status.hasChanges

        // 上一 tag（纯字符串倒序，轻量）
        val tags = gitRepository.listTags().map { it.name }
        val prevTag = tags.firstOrNull() ?: "(无历史 tag，将包含全部提交)"

        // 变更条数
        val changeCount = runCatching {
            commandEngine.runCommandSync(
                "git rev-list --count ${shellQuote(prevTag)}..HEAD",
                workspaceRepository.currentPath()
            ).trim().toIntOrNull() ?: 0
        }.getOrDefault(0)

        // 改动面判定：是否触碰启动/容器/构建链路
        val diffNames = runCatching {
            commandEngine.runCommandSync(
                "git diff --name-only ${shellQuote(prevTag)}..HEAD",
                workspaceRepository.currentPath()
            ).lines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val sensitivePatterns = listOf(
            "AndroidManifest\\.xml",
            "AIEditorApp\\.kt",
            "feature/(terminal|container|settings)/",
            "LinuxContainerEngine",
            "ContainerInstaller",
            "app/build\\.gradle\\.kts",
            "gradle/libs\\.versions\\.toml",
            ".github/workflows"
        )
        val touchedSensitive = diffNames.any { path ->
            sensitivePatterns.any { Regex(it).containsMatchIn(path) }
        }

        // 纯文档 / 资源文案 / 纯 .md 改动
        val nonCodeOnly = diffNames.isNotEmpty() &&
                diffNames.all { p ->
                    p.endsWith(".md") || p.endsWith(".xml") && p.contains("values/strings")
                }

        val isRcRequested = version.contains("-rc") || version.contains("-beta") ||
                version.contains("-alpha") || version.contains("-dev")

        // RC 判定建议（AGENTS.md 规则）
        val rcReason = when {
            isRcRequested -> "tag 含预发布后缀，按 RC 处理"
            touchedSensitive -> "改动触及启动/容器/构建链路，建议先发 RC 预览版"
            !nonCodeOnly && changeCount > 0 && !isRcRequested -> "含功能代码改动，建议先发 RC 预览版"
            nonCodeOnly -> "纯文档/资源文案改动，可直接发正式版"
            changeCount == 0 -> "无提交变更，可直接发正式版（需人工确认 tag 语义）"
            else -> "改动面较小且未触碰关键链路，可直接发正式版"
        }
        val rcRecommended = rcReason.contains("建议先发 RC") ||
                isRcRequested || touchedSensitive

        val canRelease = mainOk && cleanOk
        val blockers = mutableListOf<String>()
        if (!mainOk) blockers.add("当前分支为 '$currentBranch'，发版必须在 main 分支")
        if (!cleanOk) blockers.add("工作区有未提交改动（${status.staged.size} staged / ${status.unstaged.size} unstaged / ${status.untracked.size} untracked）")

        return success(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("can_release", JsonPrimitive(canRelease))
                put("blockers", JsonArray(blockers.map { JsonPrimitive(it) }))
                put("checks", buildJsonObject {
                    put("branch", JsonPrimitive(currentBranch))
                    put("branch_ok", JsonPrimitive(mainOk))
                    put("working_tree_clean", JsonPrimitive(cleanOk))
                    put("staged", JsonPrimitive(status.staged.size))
                    put("unstaged", JsonPrimitive(status.unstaged.size))
                    put("untracked", JsonPrimitive(status.untracked.size))
                })
                put("target_version", JsonPrimitive(version))
                put("prev_tag", JsonPrimitive(prevTag))
                put("change_count_since_prev_tag", JsonPrimitive(changeCount))
                put("touched_sensitive", JsonPrimitive(touchedSensitive))
                put("is_non_code_only", JsonPrimitive(nonCodeOnly))
                put("recommended_release", JsonPrimitive(if (rcRecommended) "RC" else "STABLE"))
                put("rc_reason", JsonPrimitive(rcReason))
                put("next_steps", JsonArray(buildNextSteps(canRelease, version, prevTag, rcRecommended)))
            }
        )
    }

    private fun buildNextSteps(canRelease: Boolean, version: String, prevTag: String, rcRecommended: Boolean): List<JsonPrimitive> {
        if (!canRelease) {
            return listOf(
                JsonPrimitive("先消除 blockers：切到 main 分支 + 提交/暂存全部改动"),
                JsonPrimitive("gitops hooks_status 确认 hooks 启用"),
                JsonPrimitive("gitops check_commit 校验提交信息")
            )
        }
        val tagPush = listOf(
            JsonPrimitive("1. 本地打 tag：git tag -a $version -m 'Release $version'"),
            JsonPrimitive("2. 推送 tag：git push origin $version")
        )
        val rcOnly = if (rcRecommended) listOf(
            JsonPrimitive("3. 真机装 RC 包：跑通 AI 对话 + 终端 + 容器启动三条主线"),
            JsonPrimitive("4. RC 期间发现问题 → 从该 RC tag 拉 hotfix/xxx 分支修复 → 升 rc 序号打 tag 推送 → 合回 main")
        ) else emptyList()
        val stableOnly = if (!rcRecommended) listOf(
            JsonPrimitive("3. CI 构建产物校验：ABI（arm64+x86_64 双通用包）/ 签名/SHA256"),
            JsonPrimitive("4. 发版完成，在 Release 页核对版本日志与下载链接")
        ) else emptyList()
        return (tagPush + rcOnly + stableOnly).map { JsonPrimitive(it) }
    }

    /**
     * B2: 在本地创建 tag（推送交给外部 Bash，凭据由 credential.helper 注入）。
     * 仅当 release_check 通过（branch=main + 工作区干净 + tag 名合法）时才允许创建。
     */
    private suspend fun doReleaseTag(args: Map<String, JsonElement>): ToolResult {
        if (!gitRepository.isRepo()) {
            return errorResult("当前工作区不是 git 仓库", "NOT_A_GIT_REPO")
        }
        val version = args["version"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("release_tag 缺少 version（如 v1.2.3）", "MISSING_VERSION")
        if (!TAG_REGEX.matches(version)) {
            return errorResult(
                "version 格式不符（需 vX.Y.Z 或 vX.Y.Z-rcN/-beta/-alpha/-dev）：$version",
                "INVALID_VERSION"
            )
        }

        val status = gitRepository.status()
        if (status.branch != "main") {
            return errorResult(
                "仅允许在 main 分支打 tag（当前分支：${status.branch}）。如为 RC 热修复，应基于对应 RC tag 拉 hotfix 分支。",
                "BRANCH_NOT_MAIN"
            )
        }
        if (status.hasChanges) {
            return errorResult(
                "工作区有未提交改动（${status.staged.size} staged / ${status.unstaged.size} unstaged / ${status.untracked.size} untracked），先提交干净再打 tag。",
                "DIRTY_WORKTREE"
            )
        }

        // tag 已存在直接拒绝，避免误覆盖。
        val existing = gitRepository.listTags().any { it.name == version }
        if (existing) {
            return errorResult("tag '$version' 已存在，拒绝覆盖", "TAG_ALREADY_EXISTS")
        }

        val result = gitRepository.createTag(version)
        return success(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("tag", JsonPrimitive(version))
                put("created", JsonPrimitive(result.isNotBlank()))
                put("next_step", JsonPrimitive("推送 tag：git push origin $version（凭据由 credential.helper 自动注入）"))
            }
        )
    }

    // ─────────────────────── C. 版本日志生成 ───────────────────────

    /**
     * 从 `git log <prevTag>..HEAD` 拉 Conventional Commits，按 CHANGELOG 六类归类生成草稿。
     *   - Added: feat(type=feat)
     *   - Improved: feat（refactor 已改行为不算）/ perf
     *   - Fixed: fix
     *   - Changed: refactor（语义变化） / revert
     *   - Removed: refactor(删除) / docs(删除)
     *   - Adjusted: style / docs（其它）/ chore
     */
    private suspend fun doChangelog(args: Map<String, JsonElement>): ToolResult {
        if (!gitRepository.isRepo()) {
            return errorResult("当前工作区不是 git 仓库", "NOT_A_GIT_REPO")
        }

        // 自动定位上一 tag：若传 prev_tag 则用之；否则取最新 tag
        val requestedPrev = args["prev_tag"]?.jsonPrimitive?.contentOrNull
        val prevTag = requestedPrev ?: run {
            gitRepository.listTags().firstOrNull()?.name
        }

        // 拉提交
        val logRaw = if (prevTag != null) {
            commandEngine.runCommandSync(
                "git log ${shellQuote(prevTag)}..HEAD --pretty=format:%H|%s",
                workspaceRepository.currentPath()
            )
        } else {
            commandEngine.runCommandSync(
                "git log --pretty=format:%H|%s",
                workspaceRepository.currentPath()
            )
        }

        val commits = logRaw.lines().mapNotNull { line ->
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank() || l.startsWith("fatal:")) null
            else {
                val idx = l.indexOf('|')
                val hash = if (idx >= 0) l.substring(0, idx) else ""
                val subject = if (idx >= 0) l.substring(idx + 1) else l
                if (hash.isBlank() && subject.isBlank()) null
                else hash to subject
            }
        }

        if (commits.isEmpty()) {
            return success(
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("prev_tag", JsonPrimitive(prevTag ?: "(无历史)"))
                    put("commit_count", JsonPrimitive(0))
                    put("draft", JsonPrimitive("无提交需要归类"))
                }
            )
        }

        // 按 type 分组到六类
        val sections = linkedMapOf(
            "Added（新增）" to mutableListOf<String>(),
            "Improved（改进）" to mutableListOf<String>(),
            "Fixed（修复）" to mutableListOf<String>(),
            "Changed（变更）" to mutableListOf<String>(),
            "Removed（删除）" to mutableListOf<String>(),
            "Adjusted（调整）" to mutableListOf<String>()
        )
        val unclassified = mutableListOf<String>()
        for ((hash, subject) in commits) {
            val firstLine = subject.lineSequence().firstOrNull()?.trim().orEmpty()
            val type = firstLine.substringBefore("(", firstLine.substringBefore(":"))
                .let { if (it.contains("(")) it.substringBefore("(") else it }
                .trim()
            val entry = "- `${firstLine}`"
            when (type) {
                "feat" -> sections["Added（新增）"]!!.add(entry)
                "perf" -> sections["Improved（改进）"]!!.add(entry)
                "fix" -> sections["Fixed（修复）"]!!.add(entry)
                "refactor" -> sections["Changed（变更）"]!!.add(entry)
                "docs" -> sections["Adjusted（调整）"]!!.add(entry)
                "style" -> sections["Adjusted（调整）"]!!.add(entry)
                "chore" -> sections["Adjusted（调整）"]!!.add(entry)
                "ci" -> sections["Adjusted（调整）"]!!.add(entry)
                "build" -> sections["Adjusted（调整）"]!!.add(entry)
                "test" -> sections["Adjusted（调整）"]!!.add(entry)
                else -> unclassified.add(entry)
            }
        }

        val draftLines = mutableListOf<String>()
        for ((name, items) in sections) {
            if (items.isEmpty()) continue
            draftLines.add("### $name")
            draftLines.add("")
            draftLines.addAll(items)
            draftLines.add("")
        }
        if (unclassified.isNotEmpty()) {
            draftLines.add("### Unclassified（待归类）")
            draftLines.add("")
            draftLines.addAll(unclassified)
            draftLines.add("")
        }

        return success(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("prev_tag", JsonPrimitive(prevTag ?: "(无历史)"))
                put("commit_count", JsonPrimitive(commits.size))
                put("sections", JsonArray(
                    sections.map { (name, items) ->
                        buildJsonObject {
                            put("name", JsonPrimitive(name))
                            put("count", JsonPrimitive(items.size))
                            put("items", JsonArray(items.map { JsonPrimitive(it) }))
                        }
                    }
                ))
                put("unclassified", JsonArray(unclassified.map { JsonPrimitive(it) }))
                put("draft", JsonPrimitive(draftLines.joinToString("\n")))
                put("next_steps", JsonArray(listOf(
                    JsonPrimitive("1. 复核 draft 条目是否准确，手动润色为用户可见的简洁描述"),
                    JsonPrimitive("2. 把 draft 追加到 CHANGELOG.md 的 [Unreleased] 或新版本节"),
                    JsonPrimitive("3. 在对应模块文档 docs/modules/<module>.md 的「版本演进记录」追加开发维度演进")
                )))
            }
        )
    }

    // ─────────────────────── 工具方法 ───────────────────────

    private fun success(obj: JsonObject): ToolResult = ToolResult.Success(obj)
    private fun errorResult(message: String, code: String): ToolResult =
        ToolResult.Error(message, code)

    /**
     * 对单个 shell 参数做单引号转义，对齐 GitRepository.shellQuote。
     * 仅用于本工具内部拼接 `git log <ref>..HEAD` 等命令，复用相同的安全策略。
     */
    private fun shellQuote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}
