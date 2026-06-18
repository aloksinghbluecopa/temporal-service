package com.bluecopa.temporalservice.config

import com.bluecopa.temporalservice.workflow.DslEngineSettings
import com.bluecopa.temporalservice.workflow.DslRetrySettings
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dsl.engine")
data class DslEngineProperties(
    val workflowIdPrefix: String,
    val inlineDefinitionId: String,
    val defaultActivityStartToClose: String,
    val defaultRetry: DslRetryProperties,
    val branchNamePrefix: String,
    val competeCancelReason: String
) {
    fun toSettings(): DslEngineSettings =
        DslEngineSettings(
            defaultActivityStartToClose = defaultActivityStartToClose,
            defaultRetry = DslRetrySettings(
                maxAttempts = defaultRetry.maxAttempts,
                initialInterval = defaultRetry.initialInterval,
                maxInterval = defaultRetry.maxInterval,
                backoffCoefficient = defaultRetry.backoffCoefficient
            ),
            branchNamePrefix = branchNamePrefix,
            competeCancelReason = competeCancelReason
        )
}

data class DslRetryProperties(
    val maxAttempts: Int,
    val initialInterval: String,
    val maxInterval: String?,
    val backoffCoefficient: Double
)

