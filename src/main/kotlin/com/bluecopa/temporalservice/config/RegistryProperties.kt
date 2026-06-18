package com.bluecopa.temporalservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "registry")
data class RegistryProperties(
    val storagePath: String
)
