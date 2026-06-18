package com.bluecopa.temporalservice.workflow

import com.bluecopa.temporalservice.activity.DslActivityHandler
import com.bluecopa.temporalservice.activity.RoutingDynamicActivity
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

class DslWorkflowTest {
    private lateinit var testEnvironment: TestWorkflowEnvironment

    @BeforeTest
    fun setUp() {
        // Mirror production (TemporalConfig.dataConverter): the payload converter must
        // register the Kotlin module, otherwise the worker cannot deserialize Kotlin
        // data classes like DslWorkflowRequest (no default constructor).
        val dataConverter = DefaultDataConverter(
            JacksonJsonPayloadConverter(
                JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
            )
        )
        testEnvironment = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setDataConverter(dataConverter)
                        .build()
                )
                .build()
        )
        val worker = testEnvironment.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DslWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(RoutingDynamicActivity(listOf(TestEchoHandler())))
        testEnvironment.start()
    }

    @AfterTest
    fun tearDown() {
        testEnvironment.close()
    }

    @Test
    fun `runs yaml workflow through dynamic activity`() {
        val stub = testEnvironment.workflowClient.newWorkflowStub(
            DslWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build()
        )

        val result = stub.run(
            DslWorkflowRequest(
                definitionYaml = """
                    id: sample
                    tasks:
                      - name: hello
                        call: echo
                        with:
                          message: "hello ${'$'}{ .name }"
                        result: greeting
                """.trimIndent(),
                input = mapOf("name" to "Ada"),
                settings = TEST_SETTINGS
            )
        )

        assertEquals("hello Ada", (result["greeting"] as Map<*, *>)["message"])
    }

    @Test
    fun `runs switch and fork constructs`() {
        val stub = testEnvironment.workflowClient.newWorkflowStub(
            DslWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build()
        )

        val result = stub.run(
            DslWorkflowRequest(
                definitionYaml = """
                    id: sample
                    tasks:
                      - name: approval
                        switch:
                          - when: "${'$'}{ .total >= 100 }"
                            then:
                              - call: echo
                                with:
                                  status: manual
                                result: approval
                          - otherwise: true
                            then:
                              - call: echo
                                with:
                                  status: auto
                                result: approval
                      - name: parallel
                        fork:
                          branches:
                            - name: left
                              tasks:
                                - call: echo
                                  with:
                                    side: left
                            - name: right
                              tasks:
                                - call: echo
                                  with:
                                    side: right
                        result: branches
                """.trimIndent(),
                input = mapOf("total" to 125),
                settings = TEST_SETTINGS
            )
        )

        assertEquals("manual", (result["approval"] as Map<*, *>)["status"])
        assertEquals(setOf("left", "right"), (result["branches"] as Map<*, *>).keys)
    }

    private class TestEchoHandler : DslActivityHandler {
        override val name: String = "echo"
        override fun handle(input: Map<String, Any?>): Any? = input
    }

    private companion object {
        const val TASK_QUEUE = "dsl-test"
        val TEST_SETTINGS = DslEngineSettings(
            defaultActivityStartToClose = "30s",
            defaultRetry = DslRetrySettings(
                maxAttempts = 1,
                initialInterval = "1s",
                maxInterval = null,
                backoffCoefficient = 2.0
            ),
            branchNamePrefix = "branch",
            competeCancelReason = "compete branch lost"
        )
    }
}
