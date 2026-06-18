package com.bluecopa.temporalservice.dsl

import io.temporal.failure.ApplicationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionEvaluatorEdgeTest {

    @Test
    fun `empty expression yields null`() {
        assertNull(ExpressionEvaluator.eval("", emptyMap()))
        assertNull(ExpressionEvaluator.eval("   ", emptyMap()))
    }

    @Test
    fun `negative number literal parses`() {
        assertEquals(-5L, ExpressionEvaluator.eval("-5", emptyMap()))
    }

    @Test
    fun `comparison against negative number`() {
        val ctx = mapOf("n" to -10)
        assertEquals(true, ExpressionEvaluator.eval(".n < -3", ctx))
    }

    @Test
    fun `comparison of null path to value`() {
        val ctx = mapOf("present" to "x")
        // .missing == 'x' -> null compared to string. Should not throw.
        val result = ExpressionEvaluator.eval(".missing == 'x'", ctx)
        assertEquals(false, result)
    }

    @Test
    fun `equality of two missing paths`() {
        // Both null. compare(null,null): number()==null both -> "null".compareTo("null") == 0 -> equal.
        val result = ExpressionEvaluator.eval(".a == .b", emptyMap())
        assertEquals(true, result, "Two nulls compare equal via toString fallback")
    }

    @Test
    fun `numeric string vs number compare equal`() {
        val ctx = mapOf("n" to 5)
        assertEquals(true, ExpressionEvaluator.eval(".n == '5'", ctx))
    }

    @Test
    fun `list index out of range yields null not exception`() {
        val ctx = mapOf("xs" to listOf(1, 2))
        assertNull(ExpressionEvaluator.eval(".xs[9]", ctx))
    }

    @Test
    fun `non-numeric list index raises DslExpression failure`() {
        val ctx = mapOf("xs" to listOf(1, 2))
        val f = assertFailsWith<ApplicationFailure> { ExpressionEvaluator.eval(".xs[abc]", ctx) }
        assertEquals("DslExpression", f.type)
        assertTrue(f.isNonRetryable)
    }

    @Test
    fun `unterminated index bracket raises DslExpression failure`() {
        val ctx = mapOf("xs" to listOf(1, 2))
        val f = assertFailsWith<ApplicationFailure> { ExpressionEvaluator.eval(".xs[0", ctx) }
        assertEquals("DslExpression", f.type)
        assertTrue(f.isNonRetryable)
    }

    @Test
    fun `unsupported expression is non-retryable ApplicationFailure`() {
        val f = assertFailsWith<ApplicationFailure> { ExpressionEvaluator.eval("@@@", emptyMap()) }
        assertEquals("DslExpression", f.type)
        assertTrue(f.isNonRetryable)
    }

    @Test
    fun `not operator binds tighter than comparison`() {
        val ctx = mapOf("n" to 5)
        // '!' binds tighter than '==' (C/Java semantics), so "!.n == 5" parses as "(!.n) == 5":
        // eval("!.n") -> !truthy(5) -> false; compare(false, 5) -> "false" vs "5" -> not equal -> false.
        assertEquals(false, ExpressionEvaluator.eval("!.n == 5", ctx))
    }

    @Test
    fun `exact typed return preserves boolean`() {
        val ctx = mapOf("flag" to true)
        val v = ExpressionEvaluator.evaluate("\${ .flag }", ctx)
        assertEquals(true, v)
    }

    @Test
    fun `nested map and list evaluation recurses`() {
        val ctx = mapOf("name" to "Ada")
        val template = mapOf("greeting" to "hi \${ .name }", "items" to listOf("\${ .name }"))
        @Suppress("UNCHECKED_CAST")
        val out = ExpressionEvaluator.evaluate(template, ctx) as Map<String, Any?>
        assertEquals("hi Ada", out["greeting"])
        assertEquals(listOf("Ada"), out["items"])
    }

    @Test
    fun `interpolation with multiple placeholders`() {
        val ctx = mapOf("a" to 1, "b" to 2)
        assertEquals("1-2", ExpressionEvaluator.evaluate("\${ .a }-\${ .b }", ctx))
    }

    @Test
    fun `bare identifier with array index syntax`() {
        val ctx = mapOf("PIPESTATUS" to listOf(0, 1, 2))
        assertEquals(0, ExpressionEvaluator.eval("PIPESTATUS[0]", ctx))
        assertEquals(1, ExpressionEvaluator.eval("PIPESTATUS[1]", ctx))
        assertEquals(2, ExpressionEvaluator.eval("PIPESTATUS[2]", ctx))
        assertNull(ExpressionEvaluator.eval("PIPESTATUS[9]", ctx))
    }

    @Test
    fun `root dollar and dot reference whole context`() {
        val ctx = mapOf("a" to 1)
        assertEquals(ctx, ExpressionEvaluator.eval(".", ctx))
        assertEquals(ctx, ExpressionEvaluator.eval("$", ctx))
    }
}
