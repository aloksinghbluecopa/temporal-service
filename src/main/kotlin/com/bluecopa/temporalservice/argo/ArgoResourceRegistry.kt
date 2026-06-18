package com.bluecopa.temporalservice.argo

import com.bluecopa.temporalservice.api.ResourceNotFoundException
import com.bluecopa.temporalservice.config.RegistryProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Component
class ArgoResourceRegistry(
    private val properties: RegistryProperties
) {
    private val workflowTemplates = ConcurrentHashMap<String, ArgoStoredResource>()
    private val cronWorkflows = ConcurrentHashMap<String, ArgoStoredResource>()
    val workflowLabels = ConcurrentHashMap<String, Map<String, String>>()
    val suspendedCrons = ConcurrentHashMap.newKeySet<String>()

    @PostConstruct
    fun reload() {
        val argoDir = storageDir()
        if (!Files.exists(argoDir)) return
        Files.walk(argoDir, 2)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }
            .forEach { path ->
                runCatching {
                    val yaml = path.toFile().readText()
                    val resource = ArgoManifestParser.parse(yaml)
                    val name = path.toFile().nameWithoutExtension
                    val stored = ArgoStoredResource(resource.kind, name, yaml, resource)
                    when (resource.kind) {
                        ArgoKinds.WORKFLOW_TEMPLATE -> workflowTemplates[name] = stored
                        ArgoKinds.CRON_WORKFLOW -> cronWorkflows[name] = stored
                    }
                }
            }
    }

    fun put(resource: ArgoResource, manifestYaml: String, name: String): ArgoStoredResource {
        val stored = ArgoStoredResource(resource.kind, name, manifestYaml, resource)
        when (resource.kind) {
            ArgoKinds.WORKFLOW_TEMPLATE -> {
                workflowTemplates[name] = stored
                persist(ArgoKinds.WORKFLOW_TEMPLATE, name, manifestYaml)
            }
            ArgoKinds.CRON_WORKFLOW -> {
                cronWorkflows[name] = stored
                persist(ArgoKinds.CRON_WORKFLOW, name, manifestYaml)
            }
        }
        return stored
    }

    fun getWorkflowTemplate(name: String): ArgoResource {
        val stored = workflowTemplates[name]
            ?: throw ResourceNotFoundException("Argo WorkflowTemplate '$name' is not registered.")
        return stored.resource
    }

    fun getCronWorkflow(name: String): ArgoStoredResource? = cronWorkflows[name]

    fun allWorkflowTemplates(): List<ArgoStoredResource> = workflowTemplates.values.toList()

    fun allCronWorkflows(): List<ArgoStoredResource> = cronWorkflows.values.toList()

    fun removeWorkflowTemplate(name: String) {
        workflowTemplates.remove(name)
        val file = storageDir().resolve(ArgoKinds.WORKFLOW_TEMPLATE).resolve("$name.yaml").toFile()
        if (file.exists()) file.delete()
    }

    fun removeCronWorkflow(name: String) {
        cronWorkflows.remove(name)
        suspendedCrons.remove(name)
        val file = storageDir().resolve(ArgoKinds.CRON_WORKFLOW).resolve("$name.yaml").toFile()
        if (file.exists()) file.delete()
    }

    private fun persist(kind: String, name: String, yaml: String) {
        val dir = storageDir().resolve(kind).toFile()
        dir.mkdirs()
        dir.resolve("$name.yaml").writeText(yaml)
    }

    private fun storageDir(): Path = Path.of(properties.storagePath, "argo")
}

object ArgoKinds {
    const val WORKFLOW = "Workflow"
    const val CRON_WORKFLOW = "CronWorkflow"
    const val WORKFLOW_TEMPLATE = "WorkflowTemplate"
}
