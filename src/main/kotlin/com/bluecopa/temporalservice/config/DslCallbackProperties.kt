package com.bluecopa.temporalservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dsl.callback")
data class DslCallbackProperties(
    val enabled: Boolean = false,
    val url: String = "",
    val timeout: String = "5s"
)
