package com.bluecopa.temporalservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "argo.compat")
data class ArgoCompatProperties(
    val leafActivityName: String,
    val defaultEntrypoint: String
)
