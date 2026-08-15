package com.deep.rcode.feature.agent.domain.permission

/**
 * 构建命令语义分类器：把一条 shell 命令分类为「构建类」「环境变更类」「其他」。
 *
 * 复用 [ShellCommandParser.analyze] 拆段（引号感知、`&&`/`;`/`|` 分隔、环境赋值跳过），
 * 逐段取首 token 比对已知工具列表，替代 ViewModel 中的简单正则匹配。
 *
 * 覆盖现有正则漏判场景（如 `./gradlew`、`make`、`cmake --build`、`&&` 串联命令），
 * 同时避免误判（如 `java -version` 正确归类为 BUILD，`ls` 正确归类为 OTHER）。
 */
object BuildCommandClassifier {

    /** 构建工具首 token 集合（不包含路径前缀，如 `./gradlew` 的 `./` 会被剥离）。 */
    private val BUILD_TOOLS = setOf(
        "gradle", "gradlew", "mvn", "mvnw", "java", "javac",
        "sdkmanager", "ndk-build", "make", "cmake", "gcc", "g++", "clang", "clang++"
    )

    /** 环境变更包管理器首 token 集合。 */
    private val ENV_MUTATORS = setOf(
        "apk", "apt", "apt-get", "pip", "pip3", "npm", "go"
    )

    /** 环境变更子命令（安装/卸载/升级/删除等）。 */
    private val MUTATION_SUBCOMMANDS = setOf(
        "add", "install", "uninstall", "upgrade", "update",
        "remove", "purge", "delete", "rm", "del"
    )

    /** 命令分类结果。 */
    enum class CommandClass { BUILD, ENV_MUTATION, OTHER }

    /**
     * 对命令全文进行分类。
     * 遍历所有段（`&&`/`;`/`|` 分隔的每一个子命令），
     * 只要任意一段命中 BUILD 或 ENV_MUTATION 即返回该分类（BUILD 优先于 ENV_MUTATION）。
     */
    fun classify(command: String): CommandClass {
        if (command.isBlank()) return CommandClass.OTHER

        val analysis = ShellCommandParser.analyze(command)
        var hasMutation = false

        for (segment in analysis.segments) {
            val effective = effectiveTokens(segment)
            if (effective.isEmpty()) continue

            val tool = effective.first().lowercase().removePrefix("./").removePrefix("/")

            // 构建工具：直接命中即返回 BUILD
            if (tool in BUILD_TOOLS) {
                // cmake 需要 --build 子命令才真正算构建
                if (tool == "cmake") {
                    if (effective.any { it == "--build" }) return CommandClass.BUILD
                    continue
                }
                return CommandClass.BUILD
            }

            // 环境变更工具：需检查子命令是否匹配
            if (tool in ENV_MUTATORS && effective.size >= 2) {
                val subcommand = effective[1].lowercase()
                if (subcommand in MUTATION_SUBCOMMANDS) {
                    hasMutation = true
                }
            }
        }

        return if (hasMutation) CommandClass.ENV_MUTATION else CommandClass.OTHER
    }

    /** 跳过段首环境赋值（`FOO=bar cmd`），取有效的程序名 token。 */
    private fun effectiveTokens(tokens: List<String>): List<String> {
        return tokens.dropWhile { ENV_ASSIGN.matches(it) }
    }

    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
}