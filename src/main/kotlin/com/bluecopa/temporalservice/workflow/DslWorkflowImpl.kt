package com.bluecopa.temporalservice.workflow

import com.bluecopa.temporalservice.dsl.DslParser
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration

class DslWorkflowImpl : DslWorkflow {
    override fun run(request: DslWorkflowRequest): Map<String, Any?> {
        val definition = DslParser.parse(request.definitionYaml)
        val workflowId = Workflow.getInfo().workflowId
        val result: Map<String, Any?>

        try {
            result = WorkflowInterpreter(definition, request.input, request.settings).execute()
        } catch (ex: Exception) {
            fireCallback(request.callbackUrl, workflowId, "FAILED", emptyMap(), ex.message)
            throw ex
        }

        fireCallback(request.callbackUrl, workflowId, "SUCCEEDED", result, null)
        gcArtifacts(result)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun gcArtifacts(result: Map<String, Any?>) {
        val artifacts = result["artifacts"] as? List<*> ?: return
        if (artifacts.isEmpty()) return
        val options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build()
        val activity = Workflow.newUntypedActivityStub(options)
        activity.execute("artifact.gc", Void::class.java, mapOf("artifacts" to artifacts))
    }

    private fun fireCallback(
        callbackUrl: String?,
        workflowId: String,
        status: String,
        result: Map<String, Any?>,
        error: String?
    ) {
        if (callbackUrl.isNullOrBlank()) return
        val options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build()
        val activity = Workflow.newUntypedActivityStub(options)
        activity.execute(
            "__status.callback",
            Void::class.java,
            mapOf(
                "callbackUrl" to callbackUrl,
                "workflowId" to workflowId,
                "status" to status,
                "result" to result,
                "error" to error,
                "completedAt" to Workflow.currentTimeMillis()
            )
        )
    }
}
