package com.bluecopa.temporalservice.argo

import com.bluecopa.temporalservice.config.RegistryProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgoResourceRegistryTest {

    @Test
    fun `put get list and remove a workflow template`(@TempDir tempDir: Path) {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val manifest = templateManifest("alpha")

        registry.put(ArgoManifestParser.parse(manifest), manifest, "alpha")

        val fetched = registry.getWorkflowTemplate("alpha")
        assertEquals(ArgoKinds.WORKFLOW_TEMPLATE, fetched.kind)
        assertEquals("alpha", fetched.metadata.name)

        assertEquals(listOf("alpha"), registry.allWorkflowTemplates().map { it.name })

        registry.removeWorkflowTemplate("alpha")
        assertTrue(registry.allWorkflowTemplates().isEmpty())
    }

    @Test
    fun `put get list and remove a cron workflow`(@TempDir tempDir: Path) {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val manifest = cronManifest("nightly")

        registry.put(ArgoManifestParser.parse(manifest), manifest, "nightly")

        val stored = assertNotNull(registry.getCronWorkflow("nightly"))
        assertEquals(ArgoKinds.CRON_WORKFLOW, stored.kind)
        assertEquals("nightly", stored.name)

        assertEquals(listOf("nightly"), registry.allCronWorkflows().map { it.name })

        registry.removeCronWorkflow("nightly")
        assertNull(registry.getCronWorkflow("nightly"))
        assertTrue(registry.allCronWorkflows().isEmpty())
    }

    @Test
    fun `reload rehydrates persisted resources from disk`(@TempDir tempDir: Path) {
        val first = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val tpl = templateManifest("alpha")
        val cron = cronManifest("nightly")
        first.put(ArgoManifestParser.parse(tpl), tpl, "alpha")
        first.put(ArgoManifestParser.parse(cron), cron, "nightly")

        val reloaded = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        reloaded.reload()

        assertEquals(listOf("alpha"), reloaded.allWorkflowTemplates().map { it.name })
        assertEquals(listOf("nightly"), reloaded.allCronWorkflows().map { it.name })
        assertEquals("alpha", reloaded.getWorkflowTemplate("alpha").metadata.name)
        assertEquals(ArgoKinds.CRON_WORKFLOW, assertNotNull(reloaded.getCronWorkflow("nightly")).resource.kind)
    }

    private fun templateManifest(name: String): String =
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

    private fun cronManifest(name: String): String =
        """
        apiVersion: argoproj.io/v1alpha1
        kind: CronWorkflow
        metadata:
          name: $name
        spec:
          schedule: "0 0 * * *"
          workflowSpec:
            entrypoint: main
            templates:
              - name: main
                container:
                  image: busybox
        """.trimIndent()
}
