package com.bluecopa.temporalservice.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchAttributesTest {

    @Test
    fun `encode then decode labels round-trips`() {
        val labels = mapOf("type" to "source-to-bronze", "team" to "data")
        val encoded = SearchAttributes.encodeLabels(labels)
        assertEquals(listOf("type=source-to-bronze", "team=data"), encoded)
        assertEquals(labels, SearchAttributes.decodeLabels(encoded))
    }

    @Test
    fun `decode preserves values containing equals signs`() {
        val encoded = listOf("expr=a=b")
        assertEquals(mapOf("expr" to "a=b"), SearchAttributes.decodeLabels(encoded))
    }

    @Test
    fun `decode ignores malformed entries without separator`() {
        assertEquals(mapOf("ok" to "1"), SearchAttributes.decodeLabels(listOf("ok=1", "malformed")))
    }

    @Test
    fun `encode and decode handle empty inputs`() {
        assertEquals(emptyList(), SearchAttributes.encodeLabels(emptyMap()))
        assertEquals(emptyMap(), SearchAttributes.decodeLabels(emptyList()))
    }

    @Test
    fun `build query with no filter selects only the DSL workflow type`() {
        assertEquals("WorkflowType = 'DslWorkflow'", SearchAttributes.buildListQuery(null))
        assertEquals("WorkflowType = 'DslWorkflow'", SearchAttributes.buildListQuery(""))
        assertEquals("WorkflowType = 'DslWorkflow'", SearchAttributes.buildListQuery("   "))
    }

    @Test
    fun `build query with label filter adds a DslLabels predicate`() {
        assertEquals(
            "WorkflowType = 'DslWorkflow' AND DslLabels = 'type=source-to-bronze'",
            SearchAttributes.buildListQuery("type=source-to-bronze")
        )
    }

    @Test
    fun `build query escapes single quotes in the label filter`() {
        assertEquals(
            "WorkflowType = 'DslWorkflow' AND DslLabels = 'team=da\\'ta'",
            SearchAttributes.buildListQuery("team=da'ta")
        )
    }
}
