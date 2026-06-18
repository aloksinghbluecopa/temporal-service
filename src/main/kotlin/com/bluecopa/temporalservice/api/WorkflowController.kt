package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.api.support.LabelSelectors
import com.bluecopa.temporalservice.api.support.TemporalPhase
import com.bluecopa.temporalservice.argo.ArgoDslTranslator
import com.bluecopa.temporalservice.argo.ArgoKinds
import com.bluecopa.temporalservice.argo.ValidationResult
import com.bluecopa.temporalservice.argo.ArgoManifestParser
import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
import com.bluecopa.temporalservice.config.SearchAttributes
import com.bluecopa.temporalservice.config.TemporalProperties
import com.bluecopa.temporalservice.config.VisibilityProperties
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionMetadata
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Optional
import java.util.UUID

@RestController
@RequestMapping("/workflows")
class WorkflowController(
    private val registry: ArgoResourceRegistry,
    private val translator: ArgoDslTranslator,
    private val client: WorkflowClient,
    private val serviceStubs: WorkflowServiceStubs,
    private val temporalProperties: TemporalProperties,
    private val launcher: WorkflowLauncher,
    private val visibilityProperties: VisibilityProperties
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /** Submit a workflow from an Argo Workflow YAML manifest. */
    @PostMapping(consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@RequestBody yaml: String): WorkflowResponse {
        val resource = ArgoManifestParser.parse(yaml)
        require(resource.kind == ArgoKinds.WORKFLOW) { "Expected kind: Workflow" }

        val id = resource.metadata.name
            ?: "${resource.metadata.generateName ?: "workflow"}-${UUID.randomUUID()}"

        val validation = translator.validate(resource)
        if (validation is ValidationResult.Invalid) {
            throw IllegalArgumentException("Workflow validation failed:\n${validation.errors.joinToString("\n")}")
        }
        val definition = translator.translate(resource, id)
        val execution = launcher.start(
            id,
            definition,
            input = mapOf("metadata" to mapOf("name" to id, "labels" to resource.metadata.labels)),
            labels = resource.metadata.labels
        )

        if (resource.metadata.labels.isNotEmpty()) {
            registry.workflowLabels[id] = resource.metadata.labels
        }

        return WorkflowResponse(
            id = execution.workflowId,
            runId = execution.runId,
            status = "Running",
            labels = resource.metadata.labels
        )
    }

    /** Get the current status of a workflow by its ID. */
    @GetMapping("/{id}")
    fun get(@PathVariable id: String): WorkflowResponse {
        val info = serviceStubs.blockingStub().describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(temporalProperties.namespace)
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(id).build())
                .build()
        ).workflowExecutionInfo

        val labels = if (visibilityProperties.searchAttributesEnabled) {
            SearchAttributes.decodeLabelsFrom(info.searchAttributes)
        } else {
            registry.workflowLabels[id] ?: emptyMap()
        }

        return WorkflowResponse(
            id = id,
            runId = info.execution.runId,
            status = TemporalPhase.native(info.status),
            startedAt = if (info.hasStartTime()) Instant.ofEpochSecond(info.startTime.seconds).toString() else null,
            finishedAt = if (info.hasCloseTime()) Instant.ofEpochSecond(info.closeTime.seconds).toString() else null,
            labels = labels
        )
    }

    /** List all workflows, optionally filtered by label (e.g. ?label=type=source-to-bronze). */
    @GetMapping
    fun list(@RequestParam(required = false) label: String?): WorkflowListResponse {
        if (visibilityProperties.searchAttributesEnabled) {
            return listViaVisibility(label)
        }

        val labelFilter = LabelSelectors.parse(label)

        val open = serviceStubs.blockingStub()
            .listOpenWorkflowExecutions(
                ListOpenWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(temporalProperties.namespace).build()
            ).executionsList.map { info ->
                WorkflowResponse(
                    id = info.execution.workflowId,
                    runId = info.execution.runId,
                    status = "Running",
                    startedAt = if (info.hasStartTime()) Instant.ofEpochSecond(info.startTime.seconds).toString() else null,
                    labels = registry.workflowLabels[info.execution.workflowId] ?: emptyMap()
                )
            }

        val closed = runCatching {
            serviceStubs.blockingStub()
                .listClosedWorkflowExecutions(
                    ListClosedWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(temporalProperties.namespace).build()
                ).executionsList.map { info ->
                    WorkflowResponse(
                        id = info.execution.workflowId,
                        runId = info.execution.runId,
                        status = TemporalPhase.native(info.status),
                        startedAt = if (info.hasStartTime()) Instant.ofEpochSecond(info.startTime.seconds).toString() else null,
                        finishedAt = if (info.hasCloseTime()) Instant.ofEpochSecond(info.closeTime.seconds).toString() else null,
                        labels = registry.workflowLabels[info.execution.workflowId] ?: emptyMap()
                    )
                }
        }.getOrElse {
            log.warn("Failed to list closed workflow executions; returning open only: {}", it.message)
            emptyList()
        }

        val items = (open + closed).filter { wf ->
            labelFilter.isEmpty() || LabelSelectors.matches(wf.labels, labelFilter)
        }

        return WorkflowListResponse(items = items, total = items.size)
    }

    private fun listViaVisibility(label: String?): WorkflowListResponse {
        val query = SearchAttributes.buildListQuery(label)
        val items = mutableListOf<WorkflowResponse>()
        val iterator = client.listExecutions(query).iterator()
        while (iterator.hasNext()) {
            val meta: WorkflowExecutionMetadata = iterator.next()
            items += WorkflowResponse(
                id = meta.execution.workflowId,
                runId = meta.execution.runId,
                status = TemporalPhase.native(meta.status),
                startedAt = meta.startTime.toString(),
                finishedAt = meta.closeTime?.toString(),
                labels = SearchAttributes.decodeLabelsFrom(meta.typedSearchAttributes)
            )
        }
        return WorkflowListResponse(items = items, total = items.size)
    }

    /** Terminate a running workflow. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun terminate(@PathVariable id: String) {
        client.newUntypedWorkflowStub(id, Optional.empty(), Optional.empty())
            .terminate("Terminated via API")
    }

    /** Suspend a running workflow. */
    @PostMapping("/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun suspend(@PathVariable id: String) {
        runCatching {
            client.newUntypedWorkflowStub(id, Optional.empty(), Optional.empty())
                .terminate("Suspended via API")
        }
    }

    /** Get the current status (used after resume). */
    @PostMapping("/{id}/resume")
    fun resume(@PathVariable id: String): WorkflowResponse = get(id)
}

data class WorkflowResponse(
    val id: String,
    val runId: String? = null,
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val labels: Map<String, String> = emptyMap()
)

data class WorkflowListResponse(
    val items: List<WorkflowResponse>,
    val total: Int
)
