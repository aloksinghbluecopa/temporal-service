package com.bluecopa.temporalservice.workflow

import com.bluecopa.temporalservice.activity.DslActivityHandler
import com.bluecopa.temporalservice.activity.EchoActivityHandler
import com.bluecopa.temporalservice.activity.RoutingDynamicActivity
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DslExternalTaskQueueTest {
    private lateinit var testEnvironment: TestWorkflowEnvironment

    // Stands in for an activity registered by a SEPARATE worker (any language) on its own
    // task queue. It is only reachable via that queue, never the workflow worker's queue.
    private val externalInvocations = AtomicInteger(0)

    private inner class FetchProfileHandler : DslActivityHandler {
        override val name: String = "FetchProfile"
        override fun handle(input: Map<String, Any?>): Any? {
            externalInvocations.incrementAndGet()
            return mapOf("userId" to input["userId"], "tier" to "gold")
        }
    }

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

        // The workflow worker: runs DslWorkflow and the in-process activities only.
        val workflowWorker = testEnvironment.newWorker(WORKFLOW_TASK_QUEUE)
        workflowWorker.registerWorkflowImplementationTypes(DslWorkflowImpl::class.java)
        workflowWorker.registerActivitiesImplementations(RoutingDynamicActivity(listOf(EchoActivityHandler())))

        // A SEPARATE worker polling a different queue, exposing only FetchProfile.
        val externalWorker = testEnvironment.newWorker(EXTERNAL_TASK_QUEUE)
        externalWorker.registerActivitiesImplementations(RoutingDynamicActivity(listOf(FetchProfileHandler())))

        testEnvironment.start()
    }

    @AfterTest
    fun tearDown() {
        testEnvironment.close()
    }

    private fun run(yaml: String, input: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val stub = testEnvironment.workflowClient.newWorkflowStub(
            DslWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build()
        )
        return stub.run(DslWorkflowRequest(definitionYaml = yaml, input = input, settings = TEST_SETTINGS))
    }

    @Test
    fun `call with taskQueue dispatches to the external worker`() {
        val result = run(
            """
            id: ext
            tasks:
              - name: fetchProfile
                call: FetchProfile
                taskQueue: $EXTERNAL_TASK_QUEUE
                with: { userId: "${'$'}{ .userId }" }
                result: profile
            """.trimIndent(),
            input = mapOf("userId" to "u-42")
        )

        assertEquals(1, externalInvocations.get(), "External activity should have run exactly once on its own task queue.")
        @Suppress("UNCHECKED_CAST")
        val profile = result["profile"] as Map<String, Any?>
        assertEquals("u-42", profile["userId"])
        assertEquals("gold", profile["tier"])
    }

    @Test
    fun `call without taskQueue still runs in-process unchanged`() {
        val result = run(
            """
            id: inproc
            tasks:
              - name: greet
                call: echo
                with: { msg: hi }
                result: greeting
            """.trimIndent()
        )

        assertEquals(0, externalInvocations.get(), "In-process call must not reach the external task queue.")
        @Suppress("UNCHECKED_CAST")
        val greeting = result["greeting"] as Map<String, Any?>
        assertEquals("hi", greeting["msg"])
    }

    private companion object {
        const val WORKFLOW_TASK_QUEUE = "dsl-external-workflow"
        const val EXTERNAL_TASK_QUEUE = "profile-worker"
        val TEST_SETTINGS = DslEngineSettings(
            defaultActivityStartToClose = "30s",
            defaultRetry = DslRetrySettings(maxAttempts = 1, initialInterval = "1", maxInterval = null, backoffCoefficient = 1.0),
            branchNamePrefix = "branch",
            competeCancelReason = "compete branch lost"
        )
    }
}
