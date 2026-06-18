package com.bluecopa.temporalservice.registry

import com.bluecopa.temporalservice.config.RegistryProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Component
class WorkflowDefinitionRegistry(
    private val properties: RegistryProperties
) {
    private val definitions = ConcurrentHashMap<String, String>()

    @PostConstruct
    fun reload() {
        val dslDir = storageDir()
        if (!Files.exists(dslDir)) return
        Files.walk(dslDir, 1)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".yaml") }
            .forEach { path ->
                runCatching {
                    val id = path.toFile().nameWithoutExtension
                    definitions[id] = path.toFile().readText()
                }
            }
    }

    fun put(id: String, yaml: String) {
        require(id.isNotBlank()) { "Definition id is required." }
        require(yaml.isNotBlank()) { "Definition YAML is required." }
        definitions[id] = yaml
        val dir = storageDir().toFile()
        dir.mkdirs()
        dir.resolve("$id.yaml").writeText(yaml)
    }

    fun get(id: String): String =
        definitions[id]
            ?: throw com.bluecopa.temporalservice.api.ResourceNotFoundException("No workflow definition registered for '$id'")

    private fun storageDir(): Path = Path.of(properties.storagePath, "dsl")
}
