package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
import com.bluecopa.temporalservice.config.RegistryProperties
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import kotlin.test.Test

class RegistryControllerMvcTest {

    private fun mockMvc(tempDir: Path): Pair<MockMvc, ArgoResourceRegistry> {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val mvc = MockMvcBuilders
            .standaloneSetup(WorkflowTemplateController(registry), CronWorkflowController(registry))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
        return mvc to registry
    }

    @Test
    fun `workflow template crud`(@TempDir tempDir: Path) {
        val (mvc, _) = mockMvc(tempDir)
        val manifest =
            """
            apiVersion: argoproj.io/v1alpha1
            kind: WorkflowTemplate
            metadata:
              name: tpl
              labels:
                team: data
            spec:
              templates:
                - name: run
                  container:
                    image: busybox
            """.trimIndent()

        mvc.perform(post("/workflow-templates").contentType(MediaType.TEXT_PLAIN).content(manifest))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("tpl"))
            .andExpect(jsonPath("$.labels.team").value("data"))

        mvc.perform(get("/workflow-templates/tpl"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("tpl"))

        mvc.perform(get("/workflow-templates"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].name").value("tpl"))

        mvc.perform(get("/workflow-templates").param("label", "team=data"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))

        mvc.perform(get("/workflow-templates").param("label", "team=ops"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))

        mvc.perform(delete("/workflow-templates/tpl"))
            .andExpect(status().isNoContent)

        mvc.perform(get("/workflow-templates"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
    }

    @Test
    fun `cron workflow crud`(@TempDir tempDir: Path) {
        val (mvc, _) = mockMvc(tempDir)
        val manifest =
            """
            apiVersion: argoproj.io/v1alpha1
            kind: CronWorkflow
            metadata:
              name: nightly
            spec:
              schedule: "0 0 * * *"
              workflowSpec:
                entrypoint: main
                templates:
                  - name: main
                    container:
                      image: busybox
            """.trimIndent()

        mvc.perform(post("/cron-workflows").contentType(MediaType.TEXT_PLAIN).content(manifest))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("nightly"))
            .andExpect(jsonPath("$.schedule").value("0 0 * * *"))
            .andExpect(jsonPath("$.suspended").value(false))

        mvc.perform(get("/cron-workflows/nightly"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schedule").value("0 0 * * *"))

        mvc.perform(post("/cron-workflows/nightly/suspend"))
            .andExpect(status().isNoContent)

        mvc.perform(get("/cron-workflows/nightly"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.suspended").value(true))

        mvc.perform(post("/cron-workflows/nightly/resume"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.suspended").value(false))

        mvc.perform(get("/cron-workflows"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))

        mvc.perform(delete("/cron-workflows/nightly"))
            .andExpect(status().isNoContent)

        mvc.perform(get("/cron-workflows"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
    }
}
