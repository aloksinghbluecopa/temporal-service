package com.bluecopa.temporalservice.workflow

import com.bluecopa.temporalservice.config.DslEngineProperties
import com.bluecopa.temporalservice.config.SearchAttributes
import com.bluecopa.temporalservice.config.TemporalProperties
import com.bluecopa.temporalservice.config.VisibilityProperties
import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.dsl.DslParser
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.common.SearchAttributes as TemporalSearchAttributes
import org.springframework.stereotype.Component

@Component
class WorkflowLauncher(
    private val client: WorkflowClient,
    private val temporalProperties: TemporalProperties,
    private val engineProperties: DslEngineProperties,
    private val visibilityProperties: VisibilityProperties = VisibilityProperties()
) {
    fun start(
        workflowId: String,
        definition: DslDefinition,
        input: Map<String, Any?> = emptyMap(),
        callbackUrl: String? = null,
        labels: Map<String, String> = emptyMap(),
        definitionId: String? = null
    ): WorkflowExecution =
        WorkflowClient.start(newStub(workflowId, labels, definitionId)::run, newRequest(definition, input, callbackUrl))

    fun run(
        workflowId: String,
        definition: DslDefinition,
        input: Map<String, Any?> = emptyMap(),
        callbackUrl: String? = null,
        labels: Map<String, String> = emptyMap(),
        definitionId: String? = null
    ): Map<String, Any?> = newStub(workflowId, labels, definitionId).run(newRequest(definition, input, callbackUrl))

    fun signalWithStart(
        coordinatorId: String,
        init: CoordinatorInit,
        signal: TriggerSignal
    ): WorkflowExecution {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(coordinatorId)
            .setTaskQueue(temporalProperties.triggerCoordinatorQueue)
            .build()
        val stub = client.newWorkflowStub(TriggerCoordinatorWorkflow::class.java, options)
        val batch = client.newSignalWithStartRequest()
        batch.add(stub::coordinate, init)
        batch.add(stub::onTrigger, signal)
        return client.signalWithStart(batch)
    }

    fun signal(coordinatorId: String, signal: TriggerSignal) {
        client.newWorkflowStub(TriggerCoordinatorWorkflow::class.java, coordinatorId).onTrigger(signal)
    }

    private fun newStub(workflowId: String, labels: Map<String, String>, definitionId: String?): DslWorkflow {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(temporalProperties.taskQueue)
        if (visibilityProperties.searchAttributesEnabled) {
            searchAttributes(labels, definitionId)?.let { options.setTypedSearchAttributes(it) }
        }
        return client.newWorkflowStub(DslWorkflow::class.java, options.build())
    }

    private fun searchAttributes(labels: Map<String, String>, definitionId: String?): TemporalSearchAttributes? {
        if (labels.isEmpty() && definitionId == null) return null
        val builder = TemporalSearchAttributes.newBuilder()
        if (labels.isNotEmpty()) {
            builder.set(SearchAttributes.DSL_LABELS, SearchAttributes.encodeLabels(labels))
        }
        if (definitionId != null) {
            builder.set(SearchAttributes.DSL_DEFINITION_ID, definitionId)
        }
        return builder.build()
    }

    private fun newRequest(
        definition: DslDefinition,
        input: Map<String, Any?>,
        callbackUrl: String?
    ): DslWorkflowRequest =
        DslWorkflowRequest(
            definitionYaml = DslParser.toYaml(definition),
            input = input,
            settings = engineProperties.toSettings(),
            callbackUrl = callbackUrl
        )
}
