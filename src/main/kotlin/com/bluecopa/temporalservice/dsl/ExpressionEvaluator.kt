package com.bluecopa.temporalservice.dsl

import io.temporal.failure.ApplicationFailure
import java.math.BigDecimal

object ExpressionEvaluator {
    private val interpolation = Regex("\\$\\{([^}]+)}")

    fun evaluate(value: Any?, context: Map<String, Any?>): Any? =
        when (value) {
            is String -> evaluateString(value, context)
            is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to evaluate(nested, context) }
            is List<*> -> value.map { evaluate(it, context) }
            else -> value
        }

    fun truthy(value: Any?): Boolean =
        when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotBlank()
            is Collection<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> true
        }

    private fun evaluateString(value: String, context: Map<String, Any?>): Any? {
        val trimmed = value.trim()
        if (trimmed.startsWith("\${") && trimmed.endsWith("}") && interpolation.matches(trimmed)) {
            return eval(trimmed.substring(2, trimmed.length - 1), context)
        }
        return interpolation.replace(value) { match ->
            // Embedded interpolation is lenient (Argo-compatible): unresolved braced tokens such as
            // shell vars `${PIPESTATUS[0]}` or `${HOME}` are left verbatim rather than failing or
            // collapsing to an empty string. Only a non-null DSL value is substituted.
            val resolved = try {
                eval(match.groupValues[1], context)
            } catch (_: ApplicationFailure) {
                null
            }
            resolved?.toString() ?: match.value
        }
    }

    fun eval(expression: String, context: Map<String, Any?>): Any? {
        val expr = stripParens(expression.trim())
        if (expr.isEmpty()) return null

        // Precedence (loosest to tightest): || < && < comparisons (== != >= <= > <) < !/exists < literals/paths.
        // Because comparisons are split before the '!' prefix, '!a == b' parses as '(!a) == b'
        // (i.e. '!' binds tighter than '=='). Write '!(a == b)' for a negated equality.
        splitTopLevel(expr, "||")?.let { return truthy(eval(it.first, context)) || truthy(eval(it.second, context)) }
        splitTopLevel(expr, "&&")?.let { return truthy(eval(it.first, context)) && truthy(eval(it.second, context)) }
        splitTopLevel(expr, "==")?.let { return compare(eval(it.first, context), eval(it.second, context)) == 0 }
        splitTopLevel(expr, "!=")?.let { return compare(eval(it.first, context), eval(it.second, context)) != 0 }
        splitTopLevel(expr, ">=")?.let { return compare(eval(it.first, context), eval(it.second, context)) >= 0 }
        splitTopLevel(expr, "<=")?.let { return compare(eval(it.first, context), eval(it.second, context)) <= 0 }
        splitTopLevel(expr, ">")?.let { return compare(eval(it.first, context), eval(it.second, context)) > 0 }
        splitTopLevel(expr, "<")?.let { return compare(eval(it.first, context), eval(it.second, context)) < 0 }

        if (expr.startsWith("!")) return !truthy(eval(expr.drop(1), context))
        if (expr.startsWith("exists(") && expr.endsWith(")")) return eval(expr.substring(7, expr.length - 1), context) != null
        if (expr == "true") return true
        if (expr == "false") return false
        if (expr == "null") return null
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
            return expr.substring(1, expr.length - 1)
        }
        expr.toLongOrNull()?.let { return it }
        expr.toBigDecimalOrNull()?.let { return it }
        if (expr == "." || expr == "$") return context
        if (expr.startsWith(".") || expr.startsWith("$.")) return readPath(expr.removePrefix("$"), context)

        // Bare identifier with path syntax, e.g. PIPESTATUS[0] or foo.bar
        if (isBarePath(expr)) return readPath(".$expr", context)

        throw ApplicationFailure.newNonRetryableFailure("Unsupported expression: $expression", "DslExpression")
    }

    private fun readPath(path: String, context: Map<String, Any?>): Any? {
        var current: Any? = context
        val tokens = tokenizePath(path.removePrefix("."))
        for (token in tokens) {
            current = when {
                current is Map<*, *> && token is String -> current[token]
                current is List<*> && token is Int -> current.getOrNull(token)
                else -> null
            }
        }
        return current
    }

    private fun tokenizePath(path: String): List<Any> {
        if (path.isBlank()) return emptyList()
        val tokens = mutableListOf<Any>()
        var index = 0
        val current = StringBuilder()
        while (index < path.length) {
            when (val char = path[index]) {
                '.' -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                '[' -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                    val end = path.indexOf(']', index)
                    if (end <= index) {
                        throw ApplicationFailure.newNonRetryableFailure("Unterminated index in path: .$path", "DslExpression")
                    }
                    val rawIndex = path.substring(index + 1, end)
                    tokens += (rawIndex.toIntOrNull()
                        ?: throw ApplicationFailure.newNonRetryableFailure("Invalid list index '$rawIndex' in path: .$path", "DslExpression"))
                    index = end
                }
                else -> current.append(char)
            }
            index++
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun compare(left: Any?, right: Any?): Int {
        val leftNumber = number(left)
        val rightNumber = number(right)
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)
        return left.toString().compareTo(right.toString())
    }

    private fun number(value: Any?): BigDecimal? =
        when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal.valueOf(value.toDouble())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

    private fun splitTopLevel(expression: String, operator: String): Pair<String, String>? {
        var depth = 0
        var quote: Char? = null
        var index = expression.length - 1
        while (index >= 0) {
            val char = expression[index]
            if (quote != null) {
                if (char == quote) quote = null
                index--
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                ')' -> depth++
                '(' -> depth--
                else -> if (depth == 0 && index + operator.length <= expression.length && expression.startsWith(operator, index)) {
                    return expression.substring(0, index).trim() to expression.substring(index + operator.length).trim()
                }
            }
            index--
        }
        return null
    }

    private fun isBarePath(expression: String): Boolean {
        // Must start with a letter/underscore and contain at least one '[' or '.'
        if (expression.isEmpty()) return false
        val first = expression[0]
        if (!(first in 'a'..'z' || first in 'A'..'Z' || first == '_')) return false
        return expression.any { it == '[' || it == '.' }
    }

    private fun stripParens(expression: String): String {
        if (!expression.startsWith("(") || !expression.endsWith(")")) return expression
        var depth = 0
        for (i in expression.indices) {
            when (expression[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && i < expression.lastIndex) return expression
        }
        return stripParens(expression.substring(1, expression.length - 1).trim())
    }
}
