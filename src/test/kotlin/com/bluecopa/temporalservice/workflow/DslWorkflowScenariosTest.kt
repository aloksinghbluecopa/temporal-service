package com.bluecopa.temporalservice.workflow

import com.bluecopa.temporalservice.activity.AddActivityHandler
import com.bluecopa.temporalservice.activity.DslActivityHandler
import com.bluecopa.temporalservice.activity.EchoActivityHandler
import com.bluecopa.temporalservice.activity.RoutingDynamicActivity
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowFailedException
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class DslWorkflowScenariosTest {
    private lateinit var testEnvironment: TestWorkflowEnvironment

    // Shared mutable counters used by failing handlers.
    private val attemptCounts = ConcurrentHashMap<String, AtomicInteger>()

    private fun counter(name: String) = attemptCounts.getOrPut(name) { AtomicInteger(0) }

    /** Fails the first [failTimes] attempts then succeeds, counting every invocation. */
    private inner class FlakyHandler(
        override val name: String,
        private val failTimes: Int
    ) : DslActivityHandler {
        override fun handle(input: Map<String, Any?>): Any? {
            val n = counter(name).incrementAndGet()
            if (n <= failTimes) {
                throw ApplicationFailure.newFailure("transient failure #$n", "Transient")
            }
            return mapOf("attempts" to n)
        }
    }

    private inner class AlwaysFailHandler(override val name: String) : DslActivityHandler {
        override fun handle(input: Map<String, Any?>): Any? {
            counter(name).incrementAndGet()
            throw ApplicationFailure.newFailure("always fails", "Boom")
        }
    }

    private inner class RecordingHandler(override val name: String) : DslActivityHandler {
        val invocations = mutableListOf<Map<String, Any?>>()
        override fun handle(input: Map<String, Any?>): Any? {
            invocations.add(input)
            return input
        }
    }

    private val compensationOrder = java.util.Collections.synchronizedList(mutableListOf<String>())

    private inner class CompensationHandler(override val name: String) : DslActivityHandler {
        override fun handle(input: Map<String, Any?>): Any? {
            compensationOrder.add(input["step"]?.toString() ?: name)
            return input
        }
    }

    private fun handlers(): List<DslActivityHandler> = listOf(
        EchoActivityHandler(),
        AddActivityHandler(),
        FlakyHandler("flaky2", failTimes = 2),
        FlakyHandler("flaky10", failTimes = 10),
        AlwaysFailHandler("boom"),
        RecordingHandler("record"),
        CompensationHandler("compensate")
    )

    @BeforeTest
    fun setUp() {
        val dataConverter = DefaultDataConverter(
            JacksonJsonPayloadConverter(
                JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
            )
        )
        testEnvironment = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(dataConverter).build()
                )
                .build()
        )
        val worker = testEnvironment.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DslWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(RoutingDynamicActivity(handlers()))
        testEnvironment.start()
    }

    @AfterTest
    fun tearDown() {
        testEnvironment.close()
    }

    private fun run(yaml: String, input: Map<String, Any?> = emptyMap(), settings: DslEngineSettings = TEST_SETTINGS): Map<String, Any?> {
        val stub = testEnvironment.workflowClient.newWorkflowStub(
            DslWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build()
        )
        return stub.run(DslWorkflowRequest(definitionYaml = yaml, input = input, settings = settings))
    }

    // ---------------- echo + math.add ----------------

    @Test
    fun `echo returns input identity`() {
        val result = run(
            """
            id: e
            tasks:
              - name: e
                call: echo
                with:
                  a: 1
                  b: hello
                result: out
            """.trimIndent()
        )
        @Suppress("UNCHECKED_CAST")
        val out = result["out"] as Map<String, Any?>
        assertEquals("hello", out["b"])
    }

    @Test
    fun `blank retry intervals fall back to defaults instead of failing`() {
        // Reproduces the shipped application.yml where `max-interval:` is empty (Spring binds "").
        // Building RetryOptions must not call parseDuration("") and crash the workflow.
        val blankIntervals = TEST_SETTINGS.copy(
            defaultRetry = DslRetrySettings(
                maxAttempts = 1,
                initialInterval = "",
                maxInterval = "",
                backoffCoefficient = 2.0
            )
        )
        val result = run(
            """
            id: e
            tasks:
              - name: e
                call: echo
                with: { a: 1 }
                result: out
            """.trimIndent(),
            settings = blankIntervals
        )
        @Suppress("UNCHECKED_CAST")
        val out = result["out"] as Map<String, Any?>
        assertEquals(1, out["a"])
    }

    @Test
    fun `math add sums integers and returns sum key`() {
        val result = run(
            """
            id: m
            tasks:
              - name: m
                call: math.add
                with:
                  values: [1, 2, 3]
                result: out
            """.trimIndent()
        )
        @Suppress("UNCHECKED_CAST")
        val out = result["out"] as Map<String, Any?>
        assertEquals(0, java.math.BigDecimal("6").compareTo(java.math.BigDecimal(out["sum"].toString())))
    }

    @Test
    fun `math add sums string-encoded numbers`() {
        val result = run(
            """
            id: m
            tasks:
              - name: m
                call: math.add
                with:
                  values: ["1.5", "2.5"]
                result: out
            """.trimIndent()
        )
        @Suppress("UNCHECKED_CAST")
        val out = result["out"] as Map<String, Any?>
        assertEquals(0, java.math.BigDecimal("4.0").compareTo(java.math.BigDecimal(out["sum"].toString())))
    }

    @Test
    fun `math add on non-numeric fails workflow`() {
        assertFailsWith<WorkflowFailedException> {
            run(
                """
                id: m
                tasks:
                  - name: m
                    call: math.add
                    with:
                      values: ["abc"]
                    result: out
                """.trimIndent()
            )
        }
    }

    // ---------------- RETRY SEMANTICS (critical) ----------------

    @Test
    fun `step with maxAttempts 3 runs activity at most 3 times`() {
        // flaky10 always fails within 3 attempts -> workflow must fail, having tried EXACTLY 3 times.
        assertFailsWith<WorkflowFailedException> {
            run(
                """
                id: r
                tasks:
                  - name: r
                    call: flaky10
                    retry:
                      maxAttempts: 3
                      initialInterval: "1"
                    result: out
                """.trimIndent()
            )
        }
        val attempts = counter("flaky10").get()
        assertEquals(3, attempts, "Expected exactly 3 activity attempts (maxAttempts=3), got $attempts. " +
            "If this is 15 or some multiple, double-retry regression is present.")
    }

    @Test
    fun `step with maxAttempts 3 succeeds on third attempt`() {
        val result = run(
            """
            id: r
            tasks:
              - name: r
                call: flaky2
                retry:
                  maxAttempts: 3
                  initialInterval: "1"
                result: out
            """.trimIndent()
        )
        @Suppress("UNCHECKED_CAST")
        val out = result["out"] as Map<String, Any?>
        assertEquals(3, counter("flaky2").get())
        assertEquals(0, java.math.BigDecimal("3").compareTo(java.math.BigDecimal(out["attempts"].toString())))
    }

    @Test
    fun `step without explicit retry uses default retry maxAttempts`() {
        // defaultRetry.maxAttempts = 2 in DEFAULT_RETRY_SETTINGS below.
        assertFailsWith<WorkflowFailedException> {
            run(
                yaml = """
                id: r
                tasks:
                  - name: r
                    call: boom
                    result: out
                """.trimIndent(),
                settings = DEFAULT_RETRY_SETTINGS
            )
        }
        assertEquals(2, counter("boom").get(),
            "Expected default retry maxAttempts=2 to be applied to a step without explicit retry.")
    }

    // ---------------- switch ----------------

    @Test
    fun `switch no match falls through without writing result`() {
        val result = run(
            """
            id: s
            tasks:
              - name: decision
                switch:
                  - when: "${'$'}{ .total >= 1000 }"
                    then:
                      - call: echo
                        with: { x: high }
                        result: decision
            """.trimIndent(),
            input = mapOf("total" to 5)
        )
        assertTrue(!result.containsKey("decision"), "No case matched; decision should be absent. Got: $result")
    }

    // ---------------- fork ----------------

    @Test
    fun `fork compete returns single winner`() {
        val result = run(
            """
            id: f
            tasks:
              - name: race
                fork:
                  compete: true
                  branches:
                    - name: only
                      tasks:
                        - call: echo
                          with: { side: only }
                result: race
            """.trimIndent()
        )
        @Suppress("UNCHECKED_CAST")
        val race = result["race"] as Map<String, Any?>
        assertEquals(setOf("only"), race.keys)
    }

    @Test
    fun `fork branch that throws fails the workflow`() {
        assertFailsWith<WorkflowFailedException> {
            run(
                """
                id: f
                tasks:
                  - name: par
                    fork:
                      branches:
                        - name: ok
                          tasks:
                            - call: echo
                              with: { x: 1 }
                        - name: bad
                          tasks:
                            - call: boom
                    result: par
                """.trimIndent()
            )
        }
    }

    @Test
    fun `empty fork branches is a no-op`() {
        val result = run(
            """
            id: f
            tasks:
              - name: par
                fork:
                  branches: []
                result: par
              - name: after
                call: echo
                with: { done: true }
                result: after
            """.trimIndent()
        )
        assertTrue(result.containsKey("after"))
    }

    // ---------------- wait ----------------

    @Test
    fun `wait accepts iso8601 and unit durations`() {
        // Pure smoke test: workflow with a wait completes (skip-time test env advances clock).
        val result = run(
            """
            id: w
            tasks:
              - name: w1
                wait: "1s"
              - name: w2
                wait: "PT1S"
              - name: w3
                wait: "1m"
              - name: w4
                wait: "1h"
              - name: w5
                wait: "1d"
              - name: done
                call: echo
                with: { ok: true }
                result: done
            """.trimIndent()
        )
        assertTrue(result.containsKey("done"))
    }

    @Test
    fun `wait with multi-digit bare millis is accepted`() {
        // Regression: parseDuration("500") must be treated as bare milliseconds, not rejected.
        val result = run(
            """
            id: w
            tasks:
              - name: w1
                wait: "500"
              - name: done
                call: echo
                with: { ok: true }
                result: done
            """.trimIndent()
        )
        assertTrue(result.containsKey("done"))
    }

    @Test
    fun `wait with ms suffix is accepted`() {
        val result = run(
            """
            id: w
            tasks:
              - name: w1
                wait: "250ms"
              - name: done
                call: echo
                with: { ok: true }
                result: done
            """.trimIndent()
        )
        assertTrue(result.containsKey("done"))
    }

    @Test
    fun `wait with single-digit bare millis works`() {
        val result = run(
            """
            id: w
            tasks:
              - name: w1
                wait: "5"
              - name: done
                call: echo
                with: { ok: true }
                result: done
            """.trimIndent()
        )
        assertTrue(result.containsKey("done"))
    }

    @Test
    fun `wait with invalid format fails workflow`() {
        assertFailsWith<WorkflowFailedException> {
            run(
                """
                id: w
                tasks:
                  - name: w1
                    wait: "5x"
                """.trimIndent()
            )
        }
    }

    @Test
    fun `wait with malformed iso8601 fails workflow (does not hang)`() {
        // Before the fix a malformed ISO duration threw a raw DateTimeParseException inside
        // workflow code, which retries the workflow task forever (hang). With the typed
        // non-retryable DslDuration failure it fails fast as a WorkflowFailedException.
        assertFailsWith<WorkflowFailedException> {
            run(
                """
                id: w
                tasks:
                  - name: w1
                    wait: "Pxyz"
                """.trimIndent()
            )
        }
    }

    // ---------------- try / catch / compensate ----------------

    @Test
    fun `compensations run in reverse order then catch runs`() {
        compensationOrder.clear()
        val result = run(
            """
            id: t
            tasks:
              - name: tx
                try:
                  - name: s1
                    call: echo
                    with: { x: 1 }
                    compensate:
                      - call: compensate
                        with: { step: s1 }
                  - name: s2
                    call: echo
                    with: { x: 2 }
                    compensate:
                      - call: compensate
                        with: { step: s2 }
                  - name: s3
                    call: boom
                catch:
                  - name: recovered
                    call: echo
                    with: { recovered: true }
                    result: recovered
            """.trimIndent()
        )
        // s3 failed; compensations for s1 and s2 should run in reverse: s2 then s1.
        assertEquals(listOf("s2", "s1"), compensationOrder.toList(),
            "Compensations should run in reverse order of succeeded steps.")
        assertTrue(result.containsKey("recovered"), "Catch branch should run after compensation.")
    }

    @Test
    fun `try without catch rethrows after compensating`() {
        compensationOrder.clear()
        assertFailsWith<WorkflowFailedException> {
            run(
                """
                id: t
                tasks:
                  - name: tx
                    try:
                      - name: s1
                        call: echo
                        with: { x: 1 }
                        compensate:
                          - call: compensate
                            with: { step: s1 }
                      - name: s2
                        call: boom
                """.trimIndent()
            )
        }
        assertEquals(listOf("s1"), compensationOrder.toList())
    }

    // ---------------- then nesting + result defaulting ----------------

    @Test
    fun `result defaults to step name`() {
        val result = run(
            """
            id: n
            tasks:
              - name: greeting
                call: echo
                with: { msg: hi }
            """.trimIndent()
        )
        assertTrue(result.containsKey("greeting"), "result key should default to step name. Got: ${result.keys}")
    }

    @Test
    fun `then steps execute after parent`() {
        val result = run(
            """
            id: n
            tasks:
              - name: parent
                call: echo
                with: { a: 1 }
                then:
                  - name: child
                    call: echo
                    with: { b: 2 }
            """.trimIndent()
        )
        assertTrue(result.containsKey("parent") && result.containsKey("child"))
    }

    // ---------------- child workflows ----------------

    @Test
    fun `named sub-workflow runs as child`() {
        val result = run(
            """
            id: top
            tasks:
              - name: callChild
                run: childflow
                input: { seed: 1 }
                result: childResult
            workflows:
              childflow:
                tasks:
                  - name: inner
                    call: echo
                    with: { fromChild: true }
                    result: inner
            """.trimIndent()
        )
        @Suppress("UNCHECKED_CAST")
        val childResult = result["childResult"] as Map<String, Any?>
        assertTrue(childResult.containsKey("inner"), "Child workflow result should contain inner step output. Got: $childResult")
    }

    private companion object {
        const val TASK_QUEUE = "dsl-scenarios"
        val TEST_SETTINGS = DslEngineSettings(
            defaultActivityStartToClose = "30s",
            defaultRetry = DslRetrySettings(maxAttempts = 1, initialInterval = "1", maxInterval = null, backoffCoefficient = 1.0),
            branchNamePrefix = "branch",
            competeCancelReason = "compete branch lost"
        )
        val DEFAULT_RETRY_SETTINGS = TEST_SETTINGS.copy(
            defaultRetry = DslRetrySettings(maxAttempts = 2, initialInterval = "1", maxInterval = null, backoffCoefficient = 1.0)
        )
    }
}
