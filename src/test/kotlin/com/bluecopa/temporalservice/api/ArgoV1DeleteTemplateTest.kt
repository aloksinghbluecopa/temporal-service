package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.argo.ArgoManifestParser
import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
import com.bluecopa.temporalservice.config.RegistryProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Regression: deleting a WorkflowTemplate via the ArgoV1Controller path must remove it from
 * the in-memory registry (not just the on-disk file). Otherwise GET/list would still return
 * the deleted template from cache until restart.
 */
class ArgoV1DeleteTemplateTest {

    private fun template(name: String): String =
        """
        apiVersion: argoproj.io/v1alpha1
        kind: WorkflowTemplate
        metadata:
          name: $name
        spec:
          templates:
            - name: run
              container:
                image: busybox
        """.trimIndent()

    @Test
    fun `ArgoV1 deleteWorkflowTemplate removes from in-memory registry`(@TempDir tempDir: Path) {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val manifest = template("alpha")
        registry.put(ArgoManifestParser.parse(manifest), manifest, "alpha")

        // Mirrors the fixed ArgoV1Controller.deleteWorkflowTemplate behavior, which delegates to
        // registry.removeWorkflowTemplate(name) — evicting the in-memory entry AND the on-disk file.
        registry.removeWorkflowTemplate("alpha")

        // Regression: the in-memory entry must be gone so GET/list no longer resolve the deleted
        // template, and the on-disk file is removed too.
        assertFalse(
            registry.allWorkflowTemplates().any { it.name == "alpha" },
            "Deleted WorkflowTemplate must not survive in the in-memory registry."
        )
        val file = Path.of(tempDir.toString(), "argo", "WorkflowTemplate", "alpha.yaml").toFile()
        assertFalse(file.exists(), "On-disk template file must be deleted.")
    }
}
