package com.bluecopa.temporalservice.cncf

import com.bluecopa.temporalservice.api.DslValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CncfDslTranslatorTest {

    private val translator = CncfDslTranslator()

    private fun translate(yaml: String, name: String = "wf") =
        translator.translate(CncfManifestParser.parse(yaml), name)

    @Test
    fun `call translates to an activity step with with-args and result`() {
        val definition = translate(
            """
            document:
              dsl: "1.0.0"
              namespace: demo
              name: greet
              version: "1.0.0"
            do:
              - sayHello:
                  call: echo
                  with:
                    message: "hello"
            """.trimIndent()
        )

        assertEquals("greet", definition.id)
        assertEquals("1.0.0", definition.version)
        val step = definition.steps.single()
        assertEquals("sayHello", step.name)
        assertEquals("echo", step.call)
        assertEquals("hello", step.arguments["message"])
        assertEquals("sayHello", step.result)
    }

    @Test
    fun `call with taskQueue passes the queue through`() {
        val step = translate(
            """
            do:
              - external:
                  call: FetchProfile
                  taskQueue: profile-worker
                  with: { userId: "1" }
            """.trimIndent()
        ).steps.single()

        assertEquals("FetchProfile", step.call)
        assertEquals("profile-worker", step.taskQueue)
    }

    @Test
    fun `http call variant is rejected`() {
        val ex = assertFailsWith<DslValidationException> {
            translate(
                """
                do:
                  - fetch:
                      call: http
                      with: { method: get }
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("http"))
    }

    @Test
    fun `do translates to nested then steps`() {
        val step = translate(
            """
            do:
              - group:
                  do:
                    - inner:
                        call: echo
                        with: { a: 1 }
            """.trimIndent()
        ).steps.single()

        assertEquals("group", step.name)
        val inner = step.then.single()
        assertEquals("inner", inner.name)
        assertEquals("echo", inner.call)
    }

    @Test
    fun `fork translates to a fork def with branches and compete`() {
        val step = translate(
            """
            do:
              - race:
                  fork:
                    compete: true
                    branches:
                      - left:
                          call: echo
                          with: { side: left }
                      - right:
                          call: echo
                          with: { side: right }
            """.trimIndent()
        ).steps.single()

        val fork = assertNotNull(step.fork)
        assertTrue(fork.compete)
        assertEquals(listOf("left", "right"), fork.branches.map { it.name })
    }

    @Test
    fun `switch translates inline cases and otherwise`() {
        val step = translate(
            """
            do:
              - decide:
                  switch:
                    - high:
                        when: "${'$'}{ .total >= 100 }"
                        then:
                          review:
                            call: echo
                            with: { status: review }
                    - low:
                        then:
                          approve:
                            call: echo
                            with: { status: approve }
            """.trimIndent()
        ).steps.single()

        assertEquals(2, step.switchCases.size)
        val first = step.switchCases.first()
        assertEquals("\${ .total >= 100 }", first.condition)
        assertEquals("review", first.then.single().name)
        assertTrue(step.switchCases.last().otherwise)
    }

    @Test
    fun `switch goto-style then jump is rejected`() {
        val ex = assertFailsWith<DslValidationException> {
            translate(
                """
                do:
                  - decide:
                      switch:
                        - high:
                            when: "${'$'}{ .total >= 100 }"
                            then: anotherTask
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("goto-style"))
    }

    @Test
    fun `try and catch translate to trySteps and catchSteps`() {
        val step = translate(
            """
            do:
              - guarded:
                  try:
                    - risky:
                        call: echo
                        with: { x: 1 }
                  catch:
                    do:
                      - recover:
                          call: echo
                          with: { recovered: true }
            """.trimIndent()
        ).steps.single()

        assertEquals("risky", step.trySteps.single().name)
        assertEquals("recover", step.catchSteps.single().name)
    }

    @Test
    fun `wait duration translates to milliseconds`() {
        val step = translate(
            """
            do:
              - pause:
                  wait:
                    seconds: 2
            """.trimIndent()
        ).steps.single()

        assertEquals("2000", step.wait)
    }

    @Test
    fun `wait until timestamp is rejected`() {
        val ex = assertFailsWith<DslValidationException> {
            translate(
                """
                do:
                  - pause:
                      wait:
                        until: "2030-01-01T00:00:00Z"
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("until"))
    }

    @Test
    fun `set translates to a set step`() {
        val step = translate(
            """
            do:
              - assign:
                  set:
                    color: red
            """.trimIndent()
        ).steps.single()

        assertEquals(mapOf("color" to "red"), step.set)
    }

    @Test
    fun `raise translates the error detail to a raise step`() {
        val step = translate(
            """
            do:
              - fail:
                  raise:
                    error:
                      detail: "boom"
            """.trimIndent()
        ).steps.single()

        assertEquals("boom", step.raise)
    }

    @Test
    fun `run translates to a child workflow step`() {
        val step = translate(
            """
            do:
              - sub:
                  run:
                    workflow:
                      name: childflow
                      input: { seed: 1 }
            """.trimIndent()
        ).steps.single()

        assertEquals("childflow", step.run)
        assertEquals(1, step.input["seed"])
    }

    @Test
    fun `for construct is rejected naming for`() {
        val ex = assertFailsWith<DslValidationException> {
            translate(
                """
                do:
                  - loop:
                      for:
                        each: item
                        in: "${'$'}{ .items }"
                      do:
                        - inner:
                            call: echo
                            with: { a: 1 }
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("for"), "Message should name the unsupported construct: ${ex.message}")
    }

    @Test
    fun `listen construct is rejected`() {
        val ex = assertFailsWith<DslValidationException> {
            translate(
                """
                do:
                  - waitForEvent:
                      listen:
                        to:
                          one: {}
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("listen"))
    }

    @Test
    fun `empty do is rejected`() {
        assertFailsWith<DslValidationException> {
            translate(
                """
                document:
                  name: empty
                """.trimIndent()
            )
        }
    }
}
