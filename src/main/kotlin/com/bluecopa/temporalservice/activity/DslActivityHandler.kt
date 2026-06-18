package com.bluecopa.temporalservice.activity

interface DslActivityHandler {
    val name: String
    fun handle(input: Map<String, Any?>): Any?
}
