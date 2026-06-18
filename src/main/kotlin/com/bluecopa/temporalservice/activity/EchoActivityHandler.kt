package com.bluecopa.temporalservice.activity

import org.springframework.stereotype.Component

@Component
class EchoActivityHandler : DslActivityHandler {
    override val name: String = "echo"

    override fun handle(input: Map<String, Any?>): Any? = input
}
