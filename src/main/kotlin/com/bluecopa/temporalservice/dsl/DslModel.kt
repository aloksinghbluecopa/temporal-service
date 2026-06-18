package com.bluecopa.temporalservice.dsl

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DslDefinition(
    val id: String? = null,
    val version: String? = null,
    @JsonAlias("tasks", "do")
    val steps: List<DslStep> = emptyList(),
    val workflows: Map<String, DslDefinition> = emptyMap(),
    val inputSchema: Map<String, Any?>? = null
)

/**
 * A step's result is an untyped `Map<String, Any?>`. By convention, a step that produces a large
 * artifact (too big for an inline ~2 MB Temporal payload) carries it by reference under an
 * `artifacts` key: `artifacts: List<Map<String, Any?>>`, where each map is an [ArtifactRef] envelope
 * encoded via [ArtifactRefCodec]. Downstream steps read those refs (e.g. `ARTIFACT_*` env injection)
 * and terminal GC inspects them; the bytes themselves never travel through the workflow payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DslStep(
    val name: String? = null,
    val call: String? = null,
    val taskQueue: String? = null,
    @JsonProperty("with")
    val arguments: Map<String, Any?> = emptyMap(),
    val result: String? = null,
    val timeout: TimeoutDef? = null,
    val retry: RetryDef? = null,
    val wait: String? = null,
    @JsonProperty("switch")
    val switchCases: List<SwitchCase> = emptyList(),
    val fork: ForkDef? = null,
    @JsonProperty("try")
    val trySteps: List<DslStep> = emptyList(),
    @JsonProperty("catch")
    val catchSteps: List<DslStep> = emptyList(),
    val compensate: List<DslStep> = emptyList(),
    val run: String? = null,
    val workflow: String? = null,
    val input: Map<String, Any?> = emptyMap(),
    val set: Map<String, Any?>? = null,
    val raise: String? = null,
    val then: List<DslStep> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SwitchCase(
    @JsonProperty("when")
    val condition: String? = null,
    val otherwise: Boolean = false,
    val then: List<DslStep> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ForkDef(
    val branches: List<ForkBranch> = emptyList(),
    val compete: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ForkBranch(
    val name: String? = null,
    @JsonAlias("tasks", "do")
    val steps: List<DslStep> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TimeoutDef(
    val startToClose: String? = null,
    val scheduleToClose: String? = null,
    val heartbeat: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RetryDef(
    val maxAttempts: Int? = null,
    val initialInterval: String? = null,
    val maxInterval: String? = null,
    val backoffCoefficient: Double? = null
)
