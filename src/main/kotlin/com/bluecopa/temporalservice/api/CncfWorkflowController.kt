package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.cncf.CncfDslTranslator
import com.bluecopa.temporalservice.cncf.CncfManifestParser
import com.bluecopa.temporalservice.config.DslEngineProperties
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Experimental import of CNCF Serverless Workflow v1.0 YAML. The manifest is parsed and translated
 * into this service's DSL by [CncfDslTranslator], then started on Temporal exactly like a native
 * definition. Unsupported constructs surface as the standard 400 `VALIDATION_FAILED` error.
 */
@RestController
@RequestMapping("/cncf/workflows")
class CncfWorkflowController(
    private val translator: CncfDslTranslator,
    private val launcher: WorkflowLauncher,
    private val engineProperties: DslEngineProperties
) {
    @PostMapping(consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@RequestBody yaml: String): StartWorkflowResponse {
        val name = generatedName()
        val definition = translate(yaml, name)
        val execution = launcher.start(name, definition)
        return StartWorkflowResponse(execution.workflowId, execution.runId)
    }

    @PostMapping("/run", consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    fun run(@RequestBody yaml: String): Map<String, Any?> {
        val name = generatedName()
        val definition = translate(yaml, name)
        return launcher.run(name, definition)
    }

    private fun translate(yaml: String, name: String) =
        translator.translate(
            try {
                CncfManifestParser.parse(yaml)
            } catch (ex: Exception) {
                throw DslValidationException("Invalid CNCF workflow manifest: ${ex.message}", ex)
            },
            name
        )

    private fun generatedName(): String =
        "${engineProperties.workflowIdPrefix}-cncf-${UUID.randomUUID()}"
}
