package com.bluecopa.temporalservice.dsl

import io.temporal.failure.ApplicationFailure
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionEvaluatorTest {
    @Test
    fun `evaluates paths comparisons and interpolation`() {
        val context = mapOf("customer" to mapOf("tier" to "gold"), "total" to 125)

        assertEquals("gold", ExpressionEvaluator.evaluate("\${ .customer.tier }", context))
        assertTrue(ExpressionEvaluator.truthy(ExpressionEvaluator.evaluate("\${ .total >= 100 && .customer.tier == 'gold' }", context)))
        assertEquals("tier=gold", ExpressionEvaluator.evaluate("tier=\${ .customer.tier }", context))
    }

    @Test
    fun `reads nested paths and list indexes`() {
        val context = mapOf(
            "order" to mapOf("items" to listOf(mapOf("sku" to "A1"), mapOf("sku" to "B2")))
        )

        assertEquals("A1", ExpressionEvaluator.eval(".order.items[0].sku", context))
        assertEquals("B2", ExpressionEvaluator.eval(".order.items[1].sku", context))
        assertNull(ExpressionEvaluator.eval(".order.items[5].sku", context))
        assertNull(ExpressionEvaluator.eval(".order.missing", context))
    }

    @Test
    fun `evaluates boolean logic and negation`() {
        val context = mapOf("a" to true, "b" to false)

        assertEquals(true, ExpressionEvaluator.eval(".a || .b", context))
        assertEquals(false, ExpressionEvaluator.eval(".a && .b", context))
        assertEquals(false, ExpressionEvaluator.eval("!.a", context))
        assertEquals(true, ExpressionEvaluator.eval("!.b", context))
    }

    @Test
    fun `compares numeric values numerically and strings lexically`() {
        val context = mapOf("n" to 5, "s" to "apple")

        assertEquals(true, ExpressionEvaluator.eval(".n > 3", context))
        assertEquals(true, ExpressionEvaluator.eval(".n <= 5", context))
        assertEquals(false, ExpressionEvaluator.eval(".n == 6", context))
        assertEquals(true, ExpressionEvaluator.eval(".s == 'apple'", context))
        assertEquals(true, ExpressionEvaluator.eval(".s < 'banana'", context))
    }

    @Test
    fun `exists checks presence of a path`() {
        val context = mapOf("present" to "value")

        assertEquals(true, ExpressionEvaluator.eval("exists(.present)", context))
        assertEquals(false, ExpressionEvaluator.eval("exists(.absent)", context))
    }

    @Test
    fun `exact expression returns typed value while interpolation returns string`() {
        val context = mapOf("total" to 125)

        val exact = ExpressionEvaluator.evaluate("\${ .total }", context)
        assertTrue(exact is Number)
        assertEquals(0, BigDecimal("125").compareTo(BigDecimal((exact as Number).toString())))

        assertEquals("total=125", ExpressionEvaluator.evaluate("total=\${ .total }", context))
    }

    @Test
    fun `parenthesized boolean precedence is respected`() {
        val context = mapOf("a" to true, "b" to false, "c" to true)

        assertEquals(true, ExpressionEvaluator.eval(".a && (.b || .c)", context))
        assertEquals(false, ExpressionEvaluator.eval("(.a && .b) || (.b && .c)", context))
    }

    @Test
    fun `embedded interpolation substitutes resolved values and preserves unresolved tokens`() {
        val context = mapOf("name" to "Ada")

        assertEquals("hello Ada", ExpressionEvaluator.evaluate("hello \${ .name }", context))
        assertEquals("hello \${ .missing }", ExpressionEvaluator.evaluate("hello \${ .missing }", context))
    }

    @Test
    fun `embedded shell-style tokens that are unsupported expressions are preserved verbatim`() {
        assertEquals(
            "x=\${PIPESTATUS[0]} y",
            ExpressionEvaluator.evaluate("x=\${PIPESTATUS[0]} y", emptyMap())
        )
        assertEquals(
            "EXIT_STATUS=\${PIPESTATUS[0]}",
            ExpressionEvaluator.evaluate("EXIT_STATUS=\${PIPESTATUS[0]}", emptyMap())
        )
        assertEquals(
            "home is \${HOME}",
            ExpressionEvaluator.evaluate("home is \${HOME}", emptyMap())
        )
    }

    @Test
    fun `switch-style condition still throws strictly on unsupported standalone expression`() {
        val failure = assertFailsWith<ApplicationFailure> {
            ExpressionEvaluator.eval("not a valid dsl expression", emptyMap())
        }
        assertEquals("DslExpression", failure.type)
        assertTrue(failure.isNonRetryable)
    }

    @Test
    fun `unsupported expression raises a non-retryable application failure`() {
        val failure = assertFailsWith<ApplicationFailure> {
            ExpressionEvaluator.eval("foo bar baz", emptyMap())
        }
        assertEquals("DslExpression", failure.type)
        assertTrue(failure.isNonRetryable)
    }

    @Test
    fun `truthiness follows kotlin-like semantics`() {
        assertFalse(ExpressionEvaluator.truthy(null))
        assertFalse(ExpressionEvaluator.truthy(false))
        assertFalse(ExpressionEvaluator.truthy(0))
        assertFalse(ExpressionEvaluator.truthy(""))
        assertFalse(ExpressionEvaluator.truthy(emptyList<Any?>()))
        assertFalse(ExpressionEvaluator.truthy(emptyMap<String, Any?>()))
        assertTrue(ExpressionEvaluator.truthy(true))
        assertTrue(ExpressionEvaluator.truthy(1))
        assertTrue(ExpressionEvaluator.truthy("x"))
        assertTrue(ExpressionEvaluator.truthy(listOf(1)))
    }
}
