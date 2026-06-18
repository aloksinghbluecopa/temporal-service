package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
import com.bluecopa.temporalservice.config.RegistryProperties
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import kotlin.test.Test

class WorkflowTemplateControllerMvcTest {

    private fun setup(tempDir: Path) =
        MockMvcBuilders.standaloneSetup(
            WorkflowTemplateController(ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString())))
        ).setControllerAdvice(GlobalExceptionHandler()).build()

    private fun template(name: String): String =
        """
        apiVersion: argoproj.io/v1alpha1
        kind: WorkflowTemplate
        metadata:
          name: $name
          labels:
            team: data
        spec:
          templates:
            - name: run
              container:
                image: busybox
        """.trimIndent()

    @Test
    fun `get of unknown template returns 404`(@TempDir tempDir: Path) {
        setup(tempDir).perform(get("/workflow-templates/nope"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `create then get then delete then get 404`(@TempDir tempDir: Path) {
        val mvc = setup(tempDir)
        mvc.perform(post("/workflow-templates").contentType(MediaType.TEXT_PLAIN).content(template("alpha")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("alpha"))

        mvc.perform(get("/workflow-templates/alpha"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.labels.team").value("data"))

        mvc.perform(delete("/workflow-templates/alpha"))
            .andExpect(status().isNoContent)

        mvc.perform(get("/workflow-templates/alpha"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `create rejects wrong kind with 400`(@TempDir tempDir: Path) {
        val wrong = template("alpha").replace("WorkflowTemplate", "CronWorkflow")
        setup(tempDir).perform(post("/workflow-templates").contentType(MediaType.TEXT_PLAIN).content(wrong))
            .andExpect(status().isBadRequest)
    }
}
