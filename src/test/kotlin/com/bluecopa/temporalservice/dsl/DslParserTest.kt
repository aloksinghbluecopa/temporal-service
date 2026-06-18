package com.bluecopa.temporalservice.dsl

import kotlin.test.Test
import kotlin.test.assertEquals

class DslParserTest {
    @Test
    fun `parses workflow yaml`() {
        val definition = DslParser.parse(
            """
            id: sample
            tasks:
              - name: echo
                call: echo
                with:
                  message: hello
                result: echoed
            """.trimIndent()
        )

        assertEquals("sample", definition.id)
        assertEquals("echo", definition.steps.single().call)
        assertEquals("echoed", definition.steps.single().result)
    }
}
