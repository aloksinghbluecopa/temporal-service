package com.bluecopa.temporalservice.api

data class ArgoObjectMeta(
    val name: String? = null,
    val namespace: String? = null,
    val uid: String? = null,
    val labels: Map<String, String>? = null,
    val annotations: Map<String, String>? = null
)

data class ArgoWorkflowStatus(
    val phase: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val message: String? = null
)

data class ArgoWorkflowObject(
    val apiVersion: String = "argoproj.io/v1alpha1",
    val kind: String = "Workflow",
    val metadata: ArgoObjectMeta? = null,
    val spec: Map<String, Any?>? = null,
    val status: ArgoWorkflowStatus? = null
)

data class ArgoWorkflowList(
    val apiVersion: String = "argoproj.io/v1alpha1",
    val kind: String = "WorkflowList",
    val items: List<ArgoWorkflowObject> = emptyList()
)

data class ArgoWorkflowCreateRequest(
    val workflow: Map<String, Any?>? = null
)

data class ArgoWorkflowTemplateBody(
    val metadata: Map<String, Any?>? = null,
    val spec: Map<String, Any?>? = null
)

data class ArgoWorkflowTemplateObject(
    val apiVersion: String = "argoproj.io/v1alpha1",
    val kind: String = "WorkflowTemplate",
    val metadata: ArgoObjectMeta? = null,
    val spec: Map<String, Any?>? = null
)

data class ArgoWorkflowTemplateList(
    val apiVersion: String = "argoproj.io/v1alpha1",
    val kind: String = "WorkflowTemplateList",
    val items: List<ArgoWorkflowTemplateObject> = emptyList()
)

data class ArgoWorkflowTemplateCreateRequest(
    val template: ArgoWorkflowTemplateBody? = null
)

data class ArgoWorkflowTemplateUpdateRequest(
    val template: ArgoWorkflowTemplateBody? = null
)

data class ArgoCronWorkflowBody(
    val metadata: Map<String, Any?>? = null,
    val spec: Map<String, Any?>? = null
)

data class ArgoCronWorkflowObject(
    val apiVersion: String = "argoproj.io/v1alpha1",
    val kind: String = "CronWorkflow",
    val metadata: ArgoObjectMeta? = null,
    val spec: Map<String, Any?>? = null
)

data class ArgoCronWorkflowList(
    val apiVersion: String = "argoproj.io/v1alpha1",
    val kind: String = "CronWorkflowList",
    val items: List<ArgoCronWorkflowObject> = emptyList()
)

data class ArgoCronWorkflowCreateRequest(
    val cronWorkflow: ArgoCronWorkflowBody? = null
)

data class ArgoCronWorkflowUpdateRequest(
    val cronWorkflow: ArgoCronWorkflowBody? = null
)

data class SignalRequest(
    val workflowType: String,
    val definitionId: String,
    val idempotencyKey: String,
    val input: Map<String, Any?> = emptyMap(),
    val callbackUrl: String? = null,
    val definitionYaml: String? = null,
    val triggeredBy: List<Map<String, Any?>> = emptyList()
)

data class SignalResponse(
    val coordinatorId: String,
    val accepted: Boolean
)

@Suppress("unused")
object ArgoPhase {
    const val RUNNING = "Running"
    const val SUCCEEDED = "Succeeded"
    const val FAILED = "Failed"
    const val ERROR = "Error"
    const val UNKNOWN = "Unknown"
    const val PENDING = "Pending"
}
