package com.bluecopa.temporalservice.activity

import com.bluecopa.temporalservice.config.DslCallbackProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
@ConditionalOnProperty(prefix = "dsl.callback", name = ["enabled"], havingValue = "true")
class StatusCallbackActivityHandler(
    private val properties: DslCallbackProperties,
    private val objectMapper: ObjectMapper
) : DslActivityHandler {
    override val name: String = "__status.callback"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun handle(input: Map<String, Any?>): Any? {
        val url = (input["callbackUrl"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val body = objectMapper.writeValueAsString(
            mapOf(
                "workflowId" to input["workflowId"],
                "status" to input["status"],
                "result" to input["result"],
                "error" to input["error"],
                "completedAt" to input["completedAt"],
                "workflowType" to input["workflowType"],
                "workspaceId" to input["workspaceId"]
            )
        )
        val timeoutSeconds = properties.timeout.trimEnd('s').toLongOrNull() ?: 5L
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        return null
    }
}
