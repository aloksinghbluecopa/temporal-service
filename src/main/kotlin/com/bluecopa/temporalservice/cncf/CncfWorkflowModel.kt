package com.bluecopa.temporalservice.cncf

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Minimal Jackson model for the subset of the CNCF Serverless Workflow v1.0 DSL this service can
 * translate. Reference: https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md
 *
 * Only the fields the translator reads are mapped; everything else is ignored so unknown spec
 * features parse without error and are rejected explicitly by [CncfDslTranslator] where relevant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfWorkflow(
    val document: CncfDocument? = null,
    /** Ordered list of single-key maps `{ <taskName>: <taskBody> }`. */
    val `do`: List<Map<String, CncfTask>> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfDocument(
    val dsl: String? = null,
    val namespace: String? = null,
    val name: String? = null,
    val version: String? = null
)

/**
 * A CNCF task body. The presence of a discriminating field (`call`, `do`, `fork`, ...) determines
 * the task type. Unsupported task types still parse and are rejected by the translator with a clear
 * RFC-7807 error rather than failing deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfTask(
    val call: String? = null,
    val with: Map<String, Any?> = emptyMap(),
    val taskQueue: String? = null,
    val `do`: List<Map<String, CncfTask>> = emptyList(),
    val fork: CncfFork? = null,
    val switch: List<Map<String, CncfSwitchCase>> = emptyList(),
    val `try`: List<Map<String, CncfTask>> = emptyList(),
    val catch: CncfCatch? = null,
    val wait: CncfWait? = null,
    val set: Map<String, Any?>? = null,
    val raise: CncfRaise? = null,
    val run: CncfRun? = null,
    // Unsupported constructs — mapped so the translator can name them precisely when rejecting.
    val `for`: Map<String, Any?>? = null,
    val listen: Map<String, Any?>? = null,
    val emit: Map<String, Any?>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfFork(
    val branches: List<Map<String, CncfTask>> = emptyList(),
    val compete: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfSwitchCase(
    val `when`: String? = null,
    /**
     * CNCF `then` is a control-flow directive (`continue`/`exit`/`end`/`<taskName>`) or an inline
     * task body. Captured untyped so the translator can distinguish the inline form it supports
     * from goto-style named-task jumps it rejects.
     */
    val then: Any? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfCatch(
    val `do`: List<Map<String, CncfTask>> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfWait(
    val seconds: Long? = null,
    val minutes: Long? = null,
    val hours: Long? = null,
    val days: Long? = null,
    val milliseconds: Long? = null,
    val until: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfRaise(
    val error: CncfRaiseError? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfRaiseError(
    val detail: String? = null,
    val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfRun(
    val workflow: CncfRunWorkflow? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CncfRunWorkflow(
    val name: String? = null,
    val namespace: String? = null,
    val version: String? = null,
    val input: Map<String, Any?> = emptyMap()
)
