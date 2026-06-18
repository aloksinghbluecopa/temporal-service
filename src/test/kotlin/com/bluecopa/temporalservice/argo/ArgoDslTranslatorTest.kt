package com.bluecopa.temporalservice.argo

import com.bluecopa.temporalservice.config.ArgoCompatProperties
import com.bluecopa.temporalservice.config.KubernetesProperties
import com.bluecopa.temporalservice.config.RegistryProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgoDslTranslatorTest {

    @Test
    fun `single step entrypoint translates to a leaf activity step`(@TempDir tempDir: Path) {
        val translator = newTranslator(tempDir)
        val resource = ArgoManifestParser.parse(
            """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: single
            spec:
              entrypoint: main
              templates:
                - name: main
                  steps:
                    - - name: only
                        template: work
                - name: work
                  container:
                    image: busybox
            """.trimIndent()
        )

        val definition = translator.translate(resource, "single")

        assertEquals("single", definition.id)
        val step = definition.steps.single()
        assertEquals("only", step.name)
        assertTrue(step.switchCases.isEmpty())
        assertNull(step.fork)
        val leaf = step.then.single()
        assertEquals("work", leaf.name)
        assertEquals("argo.template.execute", leaf.call)
        assertEquals("work", leaf.arguments["templateName"])
    }

    @Test
    fun `parallel step group translates to a fork`(@TempDir tempDir: Path) {
        val translator = newTranslator(tempDir)
        val resource = ArgoManifestParser.parse(
            """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: parallel
            spec:
              entrypoint: main
              templates:
                - name: main
                  steps:
                    - - name: left
                        template: work
                      - name: right
                        template: work
                - name: work
                  container:
                    image: busybox
            """.trimIndent()
        )

        val definition = translator.translate(resource, "parallel")

        val step = definition.steps.single()
        assertEquals("left-right", step.name)
        val fork = assertNotNull(step.fork)
        assertEquals(listOf("left", "right"), fork.branches.map { it.name })
    }

    @Test
    fun `when condition translates to a switch`(@TempDir tempDir: Path) {
        val translator = newTranslator(tempDir)
        val resource = ArgoManifestParser.parse(
            """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: conditional
            spec:
              entrypoint: main
              templates:
                - name: main
                  steps:
                    - - name: maybe
                        template: work
                        when: "{{workflow.parameters.flag}} == true"
                - name: work
                  container:
                    image: busybox
            """.trimIndent()
        )

        val definition = translator.translate(resource, "conditional")

        val step = definition.steps.single()
        assertEquals("maybe", step.name)
        assertEquals(2, step.switchCases.size)
        val first = step.switchCases.first()
        assertEquals("\${ .flag } == true", first.condition)
        assertTrue(step.switchCases.last().otherwise)
    }

    @Test
    fun `templateRef resolves a registered workflow template`(@TempDir tempDir: Path) {
        val registry = newRegistry(tempDir)
        val translator = newTranslator(tempDir, registry)
        val templateManifest =
            """
            apiVersion: argoproj.io/v1alpha1
            kind: WorkflowTemplate
            metadata:
              name: shared
            spec:
              templates:
                - name: run
                  container:
                    image: busybox
            """.trimIndent()
        registry.put(ArgoManifestParser.parse(templateManifest), templateManifest, "shared")

        val resource = ArgoManifestParser.parse(
            """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: uses-ref
            spec:
              entrypoint: main
              templates:
                - name: main
                  steps:
                    - - name: delegate
                        templateRef:
                          name: shared
                          template: run
            """.trimIndent()
        )

        val definition = translator.translate(resource, "uses-ref")

        val step = definition.steps.single()
        assertEquals("delegate", step.name)
        val leaf = step.then.single()
        assertEquals("run", leaf.name)
        assertEquals("argo.template.execute", leaf.call)
    }

    private fun newRegistry(tempDir: Path): ArgoResourceRegistry =
        ArgoResourceRegistry(RegistryProperties(storagePath = tempDir.toString()))

    private fun newTranslator(
        tempDir: Path,
        registry: ArgoResourceRegistry = newRegistry(tempDir)
    ): ArgoDslTranslator = ArgoDslTranslator(registry, properties(), KubernetesProperties())

    private fun properties(): ArgoCompatProperties = ArgoCompatProperties(
        leafActivityName = "argo.template.execute",
        defaultEntrypoint = "main"
    )
}
