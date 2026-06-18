package com.bluecopa.temporalservice.dsl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object DslParser {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(yaml: String): DslDefinition {
        val definition = mapper.readValue<DslDefinition>(yaml)
        require(definition.steps.isNotEmpty()) { "Workflow definition must contain tasks or do steps." }
        return definition
    }

    fun toYaml(definition: DslDefinition): String = mapper.writeValueAsString(definition)
}
