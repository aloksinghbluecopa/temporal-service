package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.config.DslEngineProperties
import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.dsl.DslParser
import com.bluecopa.temporalservice.dsl.InputSchemaValidator
import com.bluecopa.temporalservice.registry.WorkflowDefinitionRegistry
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${dsl.api.base-path}")
class DslWorkflowController(
    private val engineProperties: DslEngineProperties,
    private val registry: WorkflowDefinitionRegistry,
    private val launcher: WorkflowLauncher,
    private val inputSchemaValidator: InputSchemaValidator
) {
    @PostMapping("\${dsl.api.definitions-path}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun putDefinition(@PathVariable id: String, @RequestBody yaml: String) {
        registry.put(id, yaml)
    }

    @GetMapping("\${dsl.api.definitions-path}")
    fun getDefinition(@PathVariable id: String): String = registry.get(id)

    @PostMapping("\${dsl.api.start-path}")
    fun start(@Valid @RequestBody request: StartWorkflowRequest): StartWorkflowResponse {
        val definition = resolveDefinition(request)
        validateInput(definition, request.input)
        val execution = launcher.start(workflowId(request), definition, request.input, definitionId = request.definitionId)
        return StartWorkflowResponse(execution.workflowId, execution.runId)
    }

    @PostMapping("\${dsl.api.run-path}")
    fun run(@Valid @RequestBody request: StartWorkflowRequest): Map<String, Any?> {
        val definition = resolveDefinition(request)
        validateInput(definition, request.input)
        return launcher.run(workflowId(request), definition, request.input, definitionId = request.definitionId)
    }

    private fun validateInput(definition: DslDefinition, input: Map<String, Any?>) {
        val schema = definition.inputSchema ?: return
        val messages = inputSchemaValidator.validate(schema, input)
        if (messages.isNotEmpty()) {
            throw DslValidationException("Input validation failed: ${messages.joinToString("; ")}")
        }
    }

    private fun resolveDefinition(request: StartWorkflowRequest): DslDefinition {
        // registry.get may throw ResourceNotFoundException (→404); that is intentional and not caught.
        val yaml = request.definitionYaml ?: registry.get(requireNotNull(request.definitionId) { "definitionId is required." })
        return try {
            DslParser.parse(yaml)
        } catch (ex: Exception) {
            throw DslValidationException("Invalid workflow definition: ${ex.message}", ex)
        }
    }

    private fun workflowId(request: StartWorkflowRequest): String =
        request.workflowId
            ?: "${engineProperties.workflowIdPrefix}-${request.definitionId ?: engineProperties.inlineDefinitionId}-${UUID.randomUUID()}"
}

data class StartWorkflowRequest(
    val workflowId: String? = null,
    val definitionId: String? = null,
    val definitionYaml: String? = null,
    val input: Map<String, Any?> = emptyMap()
) {
    init {
        require(!definitionYaml.isNullOrBlank() || !definitionId.isNullOrBlank()) {
            "Either definitionYaml or definitionId is required."
        }
    }
}

data class StartWorkflowResponse(
    val workflowId: String,
    val runId: String
)
