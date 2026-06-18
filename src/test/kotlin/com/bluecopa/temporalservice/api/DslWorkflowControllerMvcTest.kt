package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.config.DslEngineProperties
import com.bluecopa.temporalservice.config.DslRetryProperties
import com.bluecopa.temporalservice.config.RegistryProperties
import com.bluecopa.temporalservice.config.TemporalProperties
import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.dsl.InputSchemaValidator
import com.bluecopa.temporalservice.registry.WorkflowDefinitionRegistry
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DslWorkflowControllerMvcTest {

    private val engineProperties = DslEngineProperties(
        workflowIdPrefix = "dsl",
        inlineDefinitionId = "inline",
        defaultActivityStartToClose = "30s",
        defaultRetry = DslRetryProperties(
            maxAttempts = 5,
            initialInterval = "1s",
            maxInterval = null,
            backoffCoefficient = 2.0
        ),
        branchNamePrefix = "branch",
        competeCancelReason = "compete branch lost"
    )

    private class RecordingLauncher : WorkflowLauncher(
        mock(WorkflowClient::class.java),
        TemporalProperties("127.0.0.1:7233", "default", "dsl-task-queue"),
        DslEngineProperties(
            "dsl", "inline", "30s",
            DslRetryProperties(5, "1s", null, 2.0), "branch", "compete branch lost"
        )
    ) {
        var startedId: String? = null
        var startedDefinition: DslDefinition? = null
        var startedInput: Map<String, Any?>? = null
        var ranId: String? = null
        var runResult: Map<String, Any?> = emptyMap()

        override fun start(
            workflowId: String,
            definition: DslDefinition,
            input: Map<String, Any?>,
            callbackUrl: String?,
            labels: Map<String, String>,
            definitionId: String?
        ): WorkflowExecution {
            startedId = workflowId
            startedDefinition = definition
            startedInput = input
            return WorkflowExecution.newBuilder().setWorkflowId("wf-1").setRunId("run-1").build()
        }

        override fun run(
            workflowId: String,
            definition: DslDefinition,
            input: Map<String, Any?>,
            callbackUrl: String?,
            labels: Map<String, String>,
            definitionId: String?
        ): Map<String, Any?> {
            ranId = workflowId
            return runResult
        }
    }

    private fun setup(tempDir: Path): Triple<MockMvc, RecordingLauncher, WorkflowDefinitionRegistry> {
        val launcher = RecordingLauncher()
        val registry = WorkflowDefinitionRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val controller = DslWorkflowController(
            engineProperties, registry, launcher, InputSchemaValidator(ObjectMapper())
        )
        // Standalone MockMvc has no Spring environment, so the controller's
        // ${dsl.api.*} mapping placeholders must be supplied explicitly or the
        // path-pattern parser fails on the literal "${...}" at build() time.
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("dsl.api.base-path", "/dsl")
            .addPlaceholderValue("dsl.api.definitions-path", "/definitions/{id}")
            .addPlaceholderValue("dsl.api.start-path", "/workflows/start")
            .addPlaceholderValue("dsl.api.run-path", "/workflows/run")
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
        return Triple(mvc, launcher, registry)
    }

    @Test
    fun `start parses inline yaml and launches with generated id`(@TempDir tempDir: Path) {
        val (mvc, launcher, _) = setup(tempDir)

        val body = ObjectMapper().writeValueAsString(
            mapOf(
                "definitionYaml" to "id: sample\ntasks:\n  - call: echo\n    with:\n      message: hi\n",
                "input" to mapOf("name" to "Ada")
            )
        )

        mvc.perform(post("/dsl/workflows/start").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workflowId").value("wf-1"))
            .andExpect(jsonPath("$.runId").value("run-1"))

        assertEquals("sample", launcher.startedDefinition?.id)
        assertEquals("Ada", launcher.startedInput?.get("name"))
        assertNotNull(launcher.startedId)
        assertEquals(true, launcher.startedId!!.startsWith("dsl-inline-"))
    }

    @Test
    fun `run resolves registered definition by id`(@TempDir tempDir: Path) {
        val (mvc, launcher, registry) = setup(tempDir)
        registry.put("greet", "id: greet\ntasks:\n  - call: echo\n    with:\n      message: hi\n")
        launcher.runResult = mapOf("greeting" to "hi")

        val body = ObjectMapper().writeValueAsString(mapOf("definitionId" to "greet"))

        mvc.perform(post("/dsl/workflows/run").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.greeting").value("hi"))

        assertNotNull(launcher.ranId)
        assertEquals(true, launcher.ranId!!.startsWith("dsl-greet-"))
    }

    @Test
    fun `start with empty definition returns bad request`(@TempDir tempDir: Path) {
        val (mvc, _, _) = setup(tempDir)
        val body = ObjectMapper().writeValueAsString(mapOf("definitionYaml" to "id: empty\n"))

        mvc.perform(post("/dsl/workflows/start").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    private val schemaYaml = """
        id: schema-sample
        inputSchema:
          type: object
          required: [name]
          properties:
            name:
              type: string
        tasks:
          - call: echo
            with:
              message: hi
    """.trimIndent()

    @Test
    fun `start with input missing required field returns validation failed`(@TempDir tempDir: Path) {
        val (mvc, _, _) = setup(tempDir)
        val body = ObjectMapper().writeValueAsString(
            mapOf("definitionYaml" to schemaYaml, "input" to emptyMap<String, Any?>())
        )

        mvc.perform(post("/dsl/workflows/start").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `start with valid input passes schema validation`(@TempDir tempDir: Path) {
        val (mvc, launcher, _) = setup(tempDir)
        val body = ObjectMapper().writeValueAsString(
            mapOf("definitionYaml" to schemaYaml, "input" to mapOf("name" to "Ada"))
        )

        mvc.perform(post("/dsl/workflows/start").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workflowId").value("wf-1"))

        assertEquals("Ada", launcher.startedInput?.get("name"))
    }
}
