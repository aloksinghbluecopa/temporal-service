package com.bluecopa.temporalservice.dsl

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.springframework.stereotype.Component

/**
 * Validates a workflow's `input` against an inline JSON Schema (Draft 2020-12) declared on the
 * definition via [DslDefinition.inputSchema]. Only invoked when a schema is present, so existing
 * schemaless definitions are unaffected.
 */
@Component
class InputSchemaValidator(private val objectMapper: ObjectMapper) {

    private val factory: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    /** Returns the validation messages; an empty list means the input is valid. */
    fun validate(inputSchema: Map<String, Any?>, input: Map<String, Any?>): List<String> {
        val schemaNode = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(inputSchema)
        val inputNode = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(input)
        return factory.getSchema(schemaNode).validate(inputNode).map { it.message }
    }
}
