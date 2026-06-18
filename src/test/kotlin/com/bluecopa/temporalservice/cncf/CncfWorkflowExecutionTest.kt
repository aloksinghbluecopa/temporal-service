package com.bluecopa.temporalservice.cncf

import com.bluecopa.temporalservice.activity.AddActivityHandler
import com.bluecopa.temporalservice.activity.DslActivityHandler
import com.bluecopa.temporalservice.activity.EchoActivityHandler
import com.bluecopa.temporalservice.activity.RoutingDynamicActivity
import com.bluecopa.temporalservice.dsl.DslParser
import com.bluecopa.temporalservice.workflow.DslEngineSettings
import com.bluecopa.temporalservice.workflow.DslRetrySettings
import com.bluecopa.temporalservice.workflow.DslWorkflow
import com.bluecopa.temporalservice.workflow.DslWorkflowImpl
import com.bluecopa.temporalservice.workflow.DslWorkflowRequest
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CncfWorkflowExecutionTest {
    private lateinit var testEnvironment: TestWorkflowEnvironment
    private val translator = CncfDslTranslator()

    private fun handlers(): List<DslActivityHandler> = listOf(EchoActivityHandler(), AddActivityHandler())

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

    private fun run(cncfYaml: String, input: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val definition = translator.translate(CncfManifestParser.parse(cncfYaml), "cncf-test")
        val stub = testEnvironment.workflowClient.newWorkflowStub(
            DslWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build()
        )
        return stub.run(DslWorkflowRequest(definitionYaml = DslParser.toYaml(definition), input = input, settings = TEST_SETTINGS))
    }

    @Test
    fun `call do fork switch try wait set translate and run end to end`() {
        val result = run(
            """
            document:
              dsl: "1.0.0"
              namespace: demo
              name: full
              version: "1.0.0"
            do:
              - assign:
                  set:
                    threshold: 100
              - pause:
                  wait:
                    milliseconds: 5
              - group:
                  do:
                    - greet:
                        call: echo
                        with:
                          msg: "hi"
              - parallel:
                  fork:
                    branches:
                      - a:
                          call: echo
                          with: { branch: a }
                      - b:
                          call: echo
                          with: { branch: b }
              - decide:
                  switch:
                    - high:
                        when: "${'$'}{ .total >= 100 }"
                        then:
                          chosen:
                            call: echo
                            with: { picked: high }
                    - low:
                        then:
                          chosen:
                            call: echo
                            with: { picked: low }
              - guarded:
                  try:
                    - risky:
                        call: echo
                        with: { ok: true }
                  catch:
                    do:
                      - recover:
                          call: echo
                          with: { recovered: true }
            """.trimIndent(),
            input = mapOf("total" to 150)
        )

        assertEquals(100, result["threshold"])

        @Suppress("UNCHECKED_CAST")
        val greet = result["greet"] as Map<String, Any?>
        assertEquals("hi", greet["msg"])

        @Suppress("UNCHECKED_CAST")
        val parallel = result["parallel"] as Map<String, Any?>
        assertEquals(setOf("a", "b"), parallel.keys)

        @Suppress("UNCHECKED_CAST")
        val chosen = result["chosen"] as Map<String, Any?>
        assertEquals("high", chosen["picked"])

        @Suppress("UNCHECKED_CAST")
        val risky = result["risky"] as Map<String, Any?>
        assertEquals(true, risky["ok"])
    }

    @Test
    fun `low branch of switch runs when condition is false`() {
        val result = run(
            """
            do:
              - decide:
                  switch:
                    - high:
                        when: "${'$'}{ .total >= 100 }"
                        then:
                          chosen:
                            call: echo
                            with: { picked: high }
                    - low:
                        then:
                          chosen:
                            call: echo
                            with: { picked: low }
            """.trimIndent(),
            input = mapOf("total" to 5)
        )
        @Suppress("UNCHECKED_CAST")
        val chosen = result["chosen"] as Map<String, Any?>
        assertEquals("low", chosen["picked"])
    }

    @Test
    fun `set step on a translated cncf workflow merges into context`() {
        val result = run(
            """
            do:
              - assign:
                  set:
                    color: red
                    count: 3
            """.trimIndent()
        )
        assertEquals("red", result["color"])
        assertEquals(3, result["count"])
        assertTrue(!result.containsKey("assign"))
    }

    private companion object {
        const val TASK_QUEUE = "cncf-execution"
        val TEST_SETTINGS = DslEngineSettings(
            defaultActivityStartToClose = "30s",
            defaultRetry = DslRetrySettings(maxAttempts = 1, initialInterval = "1", maxInterval = null, backoffCoefficient = 1.0),
            branchNamePrefix = "branch",
            competeCancelReason = "compete branch lost"
        )
    }
}
