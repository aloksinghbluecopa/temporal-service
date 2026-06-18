package com.bluecopa.temporalservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dsl.visibility")
data class VisibilityProperties(
    val searchAttributesEnabled: Boolean = false
)
