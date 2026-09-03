package com.core.deepcode.feature.agent.domain.skill

import com.core.deepcode.core.util.FileLogger

/**
 * S-3 运行时预检「完整布尔 DSL」解析器。
 *
 * 把 `requires_runtime` 的 `expr` 字符串解析为 [RuntimeProbeExpr] 求值树。
 * 只做结构解析、**绝不 eval**；叶子（[RuntimeProbe]）仍由 [SkillRuntimeProbe] 白名单校验、参数化探测，
 * 组合逻辑只影响「怎么判断结果」，不引入任何新的命令执行面。
 *
 * 语法（递归下降，无正则魔法）：
 * ```
 * expr    := or
 * or      := and ( '||' and )*
 * and     := unary ( '&&' unary )*
 * unary   := '!' unary | '(' expr ')' | atom
 * atom    := [check ':'] name [ ( '>=' | '<=' ) version ]*   // 版本段最多两段：一段下界 + 一段上界
 * check   := cmd | mod | npmpkg | dpkg | file（缺省按 cmd）
 * version := 纯数字与点，如 18 / 3.9 / 1.2.3
 * ```
 *
 * 示例：`cmd:node>=18<=22 && (mod:numpy || cmd:python3)`。
 * 旧格式（YAML 对象列表 / 字符串列表 / 逗号串）由 [fromLeaves] 归一为 [RuntimeProbeExpr.And]。
 */
object SkillProbeExprParser {

    private const val TAG = "SkillProbeExprParser"

    private val VALID_CHECKS = setOf(
        RuntimeProbe.CHECK_CMD, RuntimeProbe.CHECK_MOD, RuntimeProbe.CHECK_NPM,
        RuntimeProbe.CHECK_DPKG, RuntimeProbe.CHECK_FILE
    )

    /** 词法 token。 */
    sealed class Tok {
        object LPAREN : Tok()
        object RPAREN : Tok()
        object NOT : Tok()
        object AND : Tok()
        object OR : Tok()
        data class ATOM(val segment: String) : Tok()
    }

    /**
     * 解析完整布尔表达式为求值树；语法/语义错误返回 null（不抛异常）。
     * 空串/纯空白也返回 null（视为无表达式）。
     */
    fun parse(expr: String): RuntimeProbeExpr? {
        val tokens = tokenize(expr) ?: return null
        if (tokens.isEmpty()) return null
        val parser = Parser(tokens)
        val result = parser.parseOr() ?: return null
        if (!parser.atEnd()) {
            FileLogger.w(TAG, "表达式存在多余 token（可能缺操作符或括号不匹配）: $expr")
            return null
        }
        return result
    }

    /**
     * 旧格式归一：探针列表 → `And(Leaf(...))`（全 AND 语义）；空列表 → null（无预检）。
     * 供 [SkillParser] 兼容 YAML 对象列表 / 字符串列表 / 逗号串等旧声明形式。
     */
    fun fromLeaves(probes: List<RuntimeProbe>): RuntimeProbeExpr? = when {
        probes.isEmpty() -> null
        probes.size == 1 -> RuntimeProbeExpr.Leaf(probes[0])
        else -> RuntimeProbeExpr.And(probes.map { RuntimeProbeExpr.Leaf(it) })
    }

    /**
     * 从单个 atom 段解析为探针：`[check:]name[>=v][<=v]`。
     * - 缺省 check 按 cmd（兼容旧 UI 的 `node` / `node>=18` 写法）；
     * - 版本段最多两段：一段下界（`>=`，映射 [RuntimeProbe.minVersion]）+ 一段上界（`<=`，映射 [RuntimeProbe.maxVersion]）；
     * - 非法（重复下界/上界、版本非纯数字、残留非法字符）返回 null。
     */
    internal fun parseAtomSegment(seg: String): RuntimeProbe? {
        var s = seg.trim()
        if (s.isEmpty()) return null

        var check = RuntimeProbe.CHECK_CMD
        val colon = s.indexOf(':')
        if (colon > 0) {
            val head = s.substring(0, colon).trim().lowercase()
            if (head in VALID_CHECKS) {
                check = head
                s = s.substring(colon + 1)
            } else {
                // 冒号前缀不是合法 check 类型（如 `foo:bar`），无法解释 → 拒绝
                return null
            }
        }

        var name = s
        var minVersion: String? = null
        var maxVersion: String? = null
        val firstGe = s.indexOf(">=")
        val firstLe = s.indexOf("<=")
        val opsStart = when {
            firstGe < 0 -> firstLe
            firstLe < 0 -> firstGe
            else -> minOf(firstGe, firstLe)
        }
        if (opsStart >= 0) {
            name = s.substring(0, opsStart).trim()
            var j = opsStart
            while (j < s.length) {
                when {
                    s.startsWith(">=", j) -> {
                        val v = readVersion(s, j + 2) ?: return null
                        if (minVersion != null) return null // 已有一段下界
                        minVersion = v
                        j += 2 + v.length
                    }
                    s.startsWith("<=", j) -> {
                        val v = readVersion(s, j + 2) ?: return null
                        if (maxVersion != null) return null // 已有一段上界
                        maxVersion = v
                        j += 2 + v.length
                    }
                    else -> return null
                }
            }
        }
        if (name.isEmpty()) return null
        return RuntimeProbe(check, name, minVersion, maxVersion)
    }

    /** 读取纯数字/点版本段（如 18 / 3.9 / 1.2.3）；非法返回 null。 */
    private fun readVersion(s: String, start: Int): String? {
        var j = start
        while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
        if (j == start) return null
        return s.substring(start, j)
    }

    private fun tokenize(s: String): List<Tok>? {
        val toks = mutableListOf<Tok>()
        var i = 0
        val n = s.length
        while (i < n) {
            when {
                s[i].isWhitespace() -> i++
                s[i] == '(' -> { toks += Tok.LPAREN; i++ }
                s[i] == ')' -> { toks += Tok.RPAREN; i++ }
                s[i] == '!' -> { toks += Tok.NOT; i++ }
                s[i] == '&' -> {
                    if (i + 1 < n && s[i + 1] == '&') { toks += Tok.AND; i += 2 } else return null
                }
                s[i] == '|' -> {
                    if (i + 1 < n && s[i + 1] == '|') { toks += Tok.OR; i += 2 } else return null
                }
                else -> {
                    val start = i
                    while (i < n && !s[i].isWhitespace() && s[i] != '(' && s[i] != ')' &&
                        s[i] != '!' && s[i] != '&' && s[i] != '|'
                    ) {
                        i++
                    }
                    toks += Tok.ATOM(s.substring(start, i))
                }
            }
        }
        return toks
    }

    private class Parser(private val tokens: List<Tok>) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= tokens.size

        private fun peek(): Tok? = tokens.getOrNull(pos)

        private fun match(t: Tok): Boolean {
            if (peek() == t) {
                pos++
                return true
            }
            return false
        }

        fun parseOr(): RuntimeProbeExpr? {
            val first = parseAnd() ?: return null
            val children = mutableListOf(first)
            while (match(Tok.OR)) {
                children += parseAnd() ?: return null
            }
            return if (children.size == 1) children[0] else RuntimeProbeExpr.Or(children)
        }

        private fun parseAnd(): RuntimeProbeExpr? {
            val first = parseUnary() ?: return null
            val children = mutableListOf(first)
            while (match(Tok.AND)) {
                children += parseUnary() ?: return null
            }
            return if (children.size == 1) children[0] else RuntimeProbeExpr.And(children)
        }

        private fun parseUnary(): RuntimeProbeExpr? {
            if (match(Tok.NOT)) {
                val child = parseUnary() ?: return null
                return RuntimeProbeExpr.Not(child)
            }
            if (match(Tok.LPAREN)) {
                val inner = parseOr() ?: return null
                if (!match(Tok.RPAREN)) return null
                return inner
            }
            val atomTok = peek() as? Tok.ATOM ?: return null
            val probe = parseAtomSegment(atomTok.segment) ?: return null
            pos++
            return RuntimeProbeExpr.Leaf(probe)
        }
    }
}
