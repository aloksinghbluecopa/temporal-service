package com.bluecopa.temporalservice.cncf

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object CncfManifestParser {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(yaml: String): CncfWorkflow {
        require(yaml.length <= 512_000) { "Manifest too large (max 512 KB)" }
        return mapper.readValue(yaml)
    }

    /** Coerces a raw (loosely-typed) value into a [CncfTask] — used for inline `switch` `then` bodies. */
    fun convert(value: Any?): CncfTask = mapper.convertValue(value, CncfTask::class.java)
}
