package com.bluecopa.temporalservice.activity

import com.bluecopa.temporalservice.dsl.DslParser
import com.bluecopa.temporalservice.registry.WorkflowDefinitionRegistry
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.anyMap(key: String): Map<String, Any?> =
    (this[key] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> if (k is String) k to v else null }?.toMap() ?: emptyMap()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.stringMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap() ?: emptyMap()

@Component
class TriggerLaunchActivityHandler(
    private val registry: WorkflowDefinitionRegistry,
    private val launcher: WorkflowLauncher
) : DslActivityHandler {
    override val name: String = "trigger.launch"
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(input: Map<String, Any?>): Any? {
        val definitionId = (input["definitionId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ApplicationFailure.newNonRetryableFailure("definitionId is required", "MissingDefinitionId")
        val definitionYaml = (input["definitionYaml"] as? String)?.takeIf { it.isNotBlank() }
        if (definitionYaml != null) {
            registry.put(definitionId, definitionYaml)
        }
        val yaml = registry.get(definitionId)
        val definition = DslParser.parse(yaml)
        val targetWorkflowId = (input["targetWorkflowId"] as? String) ?: definitionId
        val inputMap = input.anyMap("input")
        val callbackUrl = input["callbackUrl"] as? String
        val labels = input.stringMap("labels")
        return try {
            launcher.start(targetWorkflowId, definition, inputMap, callbackUrl, labels, definitionId).workflowId
        } catch (e: WorkflowExecutionAlreadyStarted) {
            log.info("Target workflow '{}' already started; treating as idempotent", targetWorkflowId)
            targetWorkflowId
        }
    }
}