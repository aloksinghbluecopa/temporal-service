package com.bluecopa.temporalservice.config

import io.temporal.common.SearchAttributeKey
import io.temporal.common.SearchAttributes as TemporalSearchAttributes
import io.temporal.internal.common.SearchAttributesUtil

/**
 * Custom Temporal Search Attribute definitions and the pure (SDK-call-free) helpers used to encode
 * workflow labels and build visibility List Filter queries. Kept free of cluster/SDK invocations so
 * the encoding and query-building logic is unit-testable without an Advanced Visibility backend.
 */
object SearchAttributes {
    const val DSL_WORKFLOW_TYPE = "DslWorkflow"

    val DSL_LABELS: SearchAttributeKey<List<String>> = SearchAttributeKey.forKeywordList("DslLabels")
    val DSL_DEFINITION_ID: SearchAttributeKey<String> = SearchAttributeKey.forKeyword("DslDefinitionId")

    /** Encode `{a=1, b=2}` to `["a=1", "b=2"]` for storage in the `DslLabels` KeywordList attribute. */
    fun encodeLabels(labels: Map<String, String>): List<String> =
        labels.map { (key, value) -> "$key=$value" }

    /** Decode `["a=1", "b=2"]` back to `{a=1, b=2}`; entries without `=` are ignored. */
    fun decodeLabels(encoded: List<String>): Map<String, String> =
        encoded.mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx < 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
        }.toMap()

    /**
     * Build a Temporal visibility List Filter query selecting DSL workflows, optionally constrained to
     * a single `key=value` label. A `null`/blank filter yields just the workflow-type predicate.
     */
    fun buildListQuery(labelFilter: String?): String {
        val base = "WorkflowType = '${escape(DSL_WORKFLOW_TYPE)}'"
        if (labelFilter.isNullOrBlank()) return base
        return "$base AND ${DSL_LABELS.name} = '${escape(labelFilter)}'"
    }

    private fun escape(value: String): String = value.replace("'", "\\'")

    /** Read and decode the `DslLabels` KeywordList out of a proto search-attributes message. */
    fun decodeLabelsFrom(proto: io.temporal.api.common.v1.SearchAttributes): Map<String, String> =
        decodeLabelsFrom(SearchAttributesUtil.decodeTyped(proto))

    /** Read and decode the `DslLabels` KeywordList out of an SDK typed search-attributes value. */
    fun decodeLabelsFrom(attributes: TemporalSearchAttributes?): Map<String, String> {
        val encoded = attributes?.get(DSL_LABELS) ?: return emptyMap()
        return decodeLabels(encoded)
    }
}
