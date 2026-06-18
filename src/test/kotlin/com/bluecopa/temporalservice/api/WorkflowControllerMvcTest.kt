package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.argo.ArgoDslTranslator
import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
import com.bluecopa.temporalservice.config.ArgoCompatProperties
import com.bluecopa.temporalservice.config.DslEngineProperties
import com.bluecopa.temporalservice.config.DslRetryProperties
import com.bluecopa.temporalservice.config.KubernetesProperties
import com.bluecopa.temporalservice.config.RegistryProperties
import com.bluecopa.temporalservice.config.TemporalProperties
import com.bluecopa.temporalservice.config.VisibilityProperties
import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowStub
import io.temporal.serviceclient.WorkflowServiceStubs
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowControllerMvcTest {

    private val temporalProperties = TemporalProperties("127.0.0.1:7233", "default", "dsl-task-queue")

    private fun engineProperties() = DslEngineProperties(
        "dsl", "inline", "30s",
        DslRetryProperties(5, "1s", null, 2.0), "branch", "compete branch lost"
    )

    private fun argoProperties() = ArgoCompatProperties(
        leafActivityName = "argo.template.execute",
        defaultEntrypoint = "main"
    )

    private class RecordingLauncher(client: WorkflowClient) : WorkflowLauncher(
        client,
        TemporalProperties("127.0.0.1:7233", "default", "dsl-task-queue"),
        DslEngineProperties("dsl", "inline", "30s", DslRetryProperties(5, "1s", null, 2.0), "branch", "compete branch lost")
    ) {
        var startedId: String? = null

        override fun start(
            workflowId: String,
            definition: DslDefinition,
            input: Map<String, Any?>,
            callbackUrl: String?,
            labels: Map<String, String>,
            definitionId: String?
        ): WorkflowExecution {
            startedId = workflowId
            return WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId("run-1").build()
        }
    }

    private fun setup(tempDir: Path): SetupResult {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val translator = ArgoDslTranslator(registry, argoProperties(), KubernetesProperties())
        val client = mock(WorkflowClient::class.java)
        val serviceStubs = mock(WorkflowServiceStubs::class.java)
        val launcher = RecordingLauncher(client)
        val controller = WorkflowController(registry, translator, client, serviceStubs, temporalProperties, launcher, VisibilityProperties())
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
        return SetupResult(mvc, registry, client, serviceStubs, launcher)
    }

    private data class SetupResult(
        val mvc: MockMvc,
        val registry: ArgoResourceRegistry,
        val client: WorkflowClient,
        val serviceStubs: WorkflowServiceStubs,
        val launcher: RecordingLauncher
    )

    @Test
    fun `submit translates manifest and starts workflow with labels`(@TempDir tempDir: Path) {
        val s = setup(tempDir)
        val manifest =
            """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: wf-submit
              labels:
                team: data
            spec:
              entrypoint: main
              templates:
                - name: main
                  container:
                    image: busybox
            """.trimIndent()

        s.mvc.perform(post("/workflows").contentType(MediaType.TEXT_PLAIN).content(manifest))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("wf-submit"))
            .andExpect(jsonPath("$.status").value("Running"))
            .andExpect(jsonPath("$.labels.team").value("data"))

        assertEquals("wf-submit", s.launcher.startedId)
        assertEquals(mapOf("team" to "data"), s.registry.workflowLabels["wf-submit"])
    }

    @Test
    fun `submit rejects non-workflow kind`(@TempDir tempDir: Path) {
        val s = setup(tempDir)
        val manifest =
            """
            apiVersion: argoproj.io/v1alpha1
            kind: WorkflowTemplate
            metadata:
              name: tpl
            spec:
              templates:
                - name: main
                  container:
                    image: busybox
            """.trimIndent()

        s.mvc.perform(post("/workflows").contentType(MediaType.TEXT_PLAIN).content(manifest))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `list returns open executions filtered by label`(@TempDir tempDir: Path) {
        val s = setup(tempDir)
        val blockingStub = mock(io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub::class.java)
        `when`(s.serviceStubs.blockingStub()).thenReturn(blockingStub)
        `when`(blockingStub.listOpenWorkflowExecutions(any(ListOpenWorkflowExecutionsRequest::class.java)))
            .thenReturn(ListOpenWorkflowExecutionsResponse.newBuilder().build())

        s.mvc.perform(get("/workflows"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
    }

    @Test
    fun `terminate stops the workflow`(@TempDir tempDir: Path) {
        val s = setup(tempDir)
        val stub = mock(WorkflowStub::class.java)
        `when`(s.client.newUntypedWorkflowStub("wf-1", Optional.empty(), Optional.empty())).thenReturn(stub)

        s.mvc.perform(delete("/workflows/wf-1"))
            .andExpect(status().isNoContent)
    }
}
