package com.bluecopa.temporalservice.argo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoResource(
    val apiVersion: String? = null,
    val kind: String,
    val metadata: ArgoMetadata = ArgoMetadata(),
    val spec: ArgoSpec = ArgoSpec()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoMetadata(
    val name: String? = null,
    val generateName: String? = null,
    val namespace: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoSpec(
    val entrypoint: String? = null,
    val workflowSpec: ArgoSpec? = null,
    val templates: List<ArgoTemplate> = emptyList(),
    val arguments: ArgoArguments? = null,
    val schedule: String? = null,
    val timezone: String? = null,
    val suspend: Boolean? = null,
    val concurrencyPolicy: String? = null,
    val serviceAccountName: String? = null,
    val volumes: List<Map<String, Any?>> = emptyList(),
    val imagePullSecrets: List<Map<String, Any?>> = emptyList(),
    val tolerations: List<Map<String, Any?>> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoTemplate(
    val name: String,
    val steps: List<List<ArgoWorkflowStep>> = emptyList(),
    val inputs: ArgoArguments? = null,
    val container: Map<String, Any?>? = null,
    val script: Map<String, Any?>? = null,
    val metadata: ArgoTemplateMetadata? = null,
    val serviceAccountName: String? = null,
    val tolerations: List<Map<String, Any?>> = emptyList(),
    val volumes: List<Map<String, Any?>> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoTemplateMetadata(
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoWorkflowStep(
    val name: String,
    val template: String? = null,
    val templateRef: ArgoTemplateRef? = null,
    val arguments: ArgoArguments? = null,
    val `when`: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoTemplateRef(
    val name: String,
    val template: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoArguments(
    val parameters: List<ArgoParameter> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgoParameter(
    val name: String,
    val value: String? = null,
    val valueFrom: Map<String, Any?>? = null
)

data class ArgoStoredResource(
    val kind: String,
    val name: String,
    val manifestYaml: String,
    val resource: ArgoResource
)

data class ArgoResourceResponse(
    val kind: String,
    val metadata: ArgoResponseMetadata,
    val temporal: ArgoTemporalExecution? = null
)

data class ArgoResponseMetadata(
    val name: String,
    val labels: Map<String, String> = emptyMap()
)

data class ArgoTemporalExecution(
    val workflowId: String,
    val runId: String
)
