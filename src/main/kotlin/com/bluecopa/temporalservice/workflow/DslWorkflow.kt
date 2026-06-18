package com.bluecopa.temporalservice.workflow

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

data class DslWorkflowRequest(
    val definitionYaml: String,
    val input: Map<String, Any?> = emptyMap(),
    val settings: DslEngineSettings,
    val callbackUrl: String? = null
)

data class DslEngineSettings(
    val defaultActivityStartToClose: String,
    val defaultRetry: DslRetrySettings,
    val branchNamePrefix: String,
    val competeCancelReason: String
)

data class DslRetrySettings(
    val maxAttempts: Int,
    val initialInterval: String,
    val maxInterval: String?,
    val backoffCoefficient: Double
)

@WorkflowInterface
interface DslWorkflow {
    @WorkflowMethod(name = "DslWorkflow")
    fun run(request: DslWorkflowRequest): Map<String, Any?>
}
