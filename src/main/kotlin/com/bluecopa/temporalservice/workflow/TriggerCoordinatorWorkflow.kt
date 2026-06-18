package com.bluecopa.temporalservice.workflow

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

data class CoordinatorInit(
    val coordinatorId: String,
    val workflowType: String,
    val callbackUrl: String? = null
)

data class TriggerSignal(
    val definitionId: String,
    val idempotencyKey: String,
    val triggeredBy: List<Map<String, Any?>> = emptyList(),
    val input: Map<String, Any?> = emptyMap(),
    val definitionYaml: String? = null
)

@WorkflowInterface
interface TriggerCoordinatorWorkflow {
    @WorkflowMethod(name = "TriggerCoordinatorWorkflow")
    fun coordinate(init: CoordinatorInit)

    @SignalMethod
    fun onTrigger(signal: TriggerSignal)

    @QueryMethod
    fun pending(): List<TriggerSignal>
}