package com.bluecopa.temporalservice.argo

import com.bluecopa.temporalservice.config.RegistryProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ArgoRegistryCacheTest {

    private fun template(name: String, image: String): String =
        """
        apiVersion: argoproj.io/v1alpha1
        kind: WorkflowTemplate
        metadata:
          name: $name
        spec:
          templates:
            - name: run
              container:
                image: $image
        """.trimIndent()

    @Test
    fun `put with same name updates cached resource (no stale cache)`(@TempDir tempDir: Path) {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val v1 = template("alpha", "busybox:1")
        registry.put(ArgoManifestParser.parse(v1), v1, "alpha")
        assertEquals("busybox:1", registry.getWorkflowTemplate("alpha").spec.templates.first().container?.get("image"))

        val v2 = template("alpha", "busybox:2")
        registry.put(ArgoManifestParser.parse(v2), v2, "alpha")
        assertEquals(
            "busybox:2",
            registry.getWorkflowTemplate("alpha").spec.templates.first().container?.get("image"),
            "After update, cached resource must reflect the new manifest."
        )
    }

    @Test
    fun `reload deletes nothing and rehydrates updated content`(@TempDir tempDir: Path) {
        val registry = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        val v1 = template("alpha", "busybox:1")
        registry.put(ArgoManifestParser.parse(v1), v1, "alpha")
        val v2 = template("alpha", "busybox:2")
        registry.put(ArgoManifestParser.parse(v2), v2, "alpha")

        val reloaded = ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))
        reloaded.reload()
        assertEquals("busybox:2", reloaded.getWorkflowTemplate("alpha").spec.templates.first().container?.get("image"))
    }
}
