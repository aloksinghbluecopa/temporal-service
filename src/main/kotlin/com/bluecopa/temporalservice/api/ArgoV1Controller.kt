package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.api.support.LabelSelectors
import com.bluecopa.temporalservice.api.support.TemporalPhase
import com.bluecopa.temporalservice.argo.ArgoDslTranslator
import com.bluecopa.temporalservice.argo.ArgoKinds
import com.bluecopa.temporalservice.argo.ValidationResult
import com.bluecopa.temporalservice.argo.ArgoManifestParser
import com.bluecopa.temporalservice.argo.ArgoResource
import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
import com.bluecopa.temporalservice.config.DslEngineProperties
import com.bluecopa.temporalservice.config.SearchAttributes
import com.bluecopa.temporalservice.config.TemporalProperties
import com.bluecopa.temporalservice.config.VisibilityProperties
import com.bluecopa.temporalservice.dsl.DslParser
import com.bluecopa.temporalservice.workflow.DslWorkflow
import com.bluecopa.temporalservice.workflow.DslWorkflowRequest
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionMetadata
import io.temporal.client.WorkflowOptions
import io.temporal.client.schedules.Schedule
import io.temporal.client.schedules.ScheduleActionStartWorkflow
import io.temporal.client.schedules.ScheduleAlreadyRunningException
import io.temporal.client.schedules.ScheduleClient
import io.temporal.client.schedules.ScheduleOptions
import io.temporal.client.schedules.SchedulePolicy
import io.temporal.client.schedules.ScheduleSpec
import io.temporal.client.schedules.ScheduleUpdate
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Optional
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ArgoV1Controller(
    private val registry: ArgoResourceRegistry,
    private val translator: ArgoDslTranslator,
    private val client: WorkflowClient,
    private val scheduleClient: ScheduleClient,
    private val serviceStubs: WorkflowServiceStubs,
    private val temporalProperties: TemporalProperties,
    private val engineProperties: DslEngineProperties,
    private val launcher: WorkflowLauncher,
    private val visibilityProperties: VisibilityProperties,
    @org.springframework.beans.factory.annotation.Qualifier("yamlObjectMapper")
    private val yamlMapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Qualifier("jacksonObjectMapper")
    private val jsonMapper: ObjectMapper
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    // ================================================================
    // Workflows
    // ================================================================

    @PostMapping("/workflows/{namespace}")
    @ResponseStatus(HttpStatus.OK)
    fun createWorkflow(
        @PathVariable namespace: String,
        @RequestBody request: ArgoWorkflowCreateRequest
    ): ArgoWorkflowObject {
        val wf = requireNotNull(request.workflow) { "workflow is required" }
        val yaml = toYamlString(wf, ArgoKinds.WORKFLOW)
        val resource = ArgoManifestParser.parse(yaml)
        val name = resolveName(resource, "workflow")

        val validation = translator.validate(resource)
        if (validation is ValidationResult.Invalid) {
            throw IllegalArgumentException("Workflow validation failed:\n${validation.errors.joinToString("\n")}")
        }
        val definition = translator.translate(resource, name)
        val execution = launcher.start(name, definition, input = buildInput(resource, name), labels = resource.metadata.labels)
        if (resource.metadata.labels.isNotEmpty()) {
            registry.workflowLabels[name] = resource.metadata.labels
        }
        return ArgoWorkflowObject(
            metadata = ArgoObjectMeta(
                name = execution.workflowId,
                uid = execution.runId,
                namespace = namespace,
                labels = resource.metadata.labels.takeIf { it.isNotEmpty() }
            ),
            status = ArgoWorkflowStatus(phase = ArgoPhase.RUNNING, startedAt = Instant.now().toString())
        )
    }

    @GetMapping("/workflows/{namespace}")
    fun listWorkflows(
        @PathVariable namespace: String,
        @RequestParam(name = "listOptions.labelSelector", required = false) labelSelector: String?,
        @RequestParam(name = "listOptions.fieldSelector", required = false) fieldSelector: String?
    ): ArgoWorkflowList {
        if (visibilityProperties.searchAttributesEnabled) {
            return listWorkflowsViaVisibility(namespace, labelSelector)
        }

        val labelFilter = LabelSelectors.parse(labelSelector)

        val open = serviceStubs.blockingStub().listOpenWorkflowExecutions(
            ListOpenWorkflowExecutionsRequest.newBuilder()
                .setNamespace(temporalProperties.namespace)
                .build()
        ).executionsList.map { info ->
            val wfId = info.execution.workflowId
            buildWorkflowObject(
                name = wfId,
                namespace = namespace,
                uid = info.execution.runId,
                phase = ArgoPhase.RUNNING,
                startedAt = if (info.hasStartTime()) Instant.ofEpochSecond(
                    info.startTime.seconds, info.startTime.nanos.toLong()
                ).toString() else null,
                labels = registry.workflowLabels[wfId] ?: emptyMap()
            )
        }

        val closed = runCatching {
            serviceStubs.blockingStub().listClosedWorkflowExecutions(
                ListClosedWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(temporalProperties.namespace)
                    .build()
            ).executionsList.map { info ->
                val wfId = info.execution.workflowId
                buildWorkflowObject(
                    name = wfId,
                    namespace = namespace,
                    uid = info.execution.runId,
                    phase = TemporalPhase.argo(info.status),
                    startedAt = if (info.hasStartTime()) Instant.ofEpochSecond(
                        info.startTime.seconds, info.startTime.nanos.toLong()
                    ).toString() else null,
                    finishedAt = if (info.hasCloseTime()) Instant.ofEpochSecond(
                        info.closeTime.seconds, info.closeTime.nanos.toLong()
                    ).toString() else null,
                    labels = registry.workflowLabels[wfId] ?: emptyMap()
                )
            }
        }.getOrElse {
            log.warn("Failed to list closed workflow executions; returning open only: {}", it.message)
            emptyList()
        }

        val items = (open + closed).filter { wf ->
            labelFilter.isEmpty() || LabelSelectors.matches(wf.metadata?.labels ?: emptyMap(), labelFilter)
        }
        return ArgoWorkflowList(items = items)
    }

    private fun listWorkflowsViaVisibility(namespace: String, labelSelector: String?): ArgoWorkflowList {
        val query = SearchAttributes.buildListQuery(labelSelector)
        val items = mutableListOf<ArgoWorkflowObject>()
        val iterator = client.listExecutions(query).iterator()
        while (iterator.hasNext()) {
            val meta: WorkflowExecutionMetadata = iterator.next()
            items += buildWorkflowObject(
                name = meta.execution.workflowId,
                namespace = namespace,
                uid = meta.execution.runId,
                phase = TemporalPhase.argo(meta.status),
                startedAt = meta.startTime.toString(),
                finishedAt = meta.closeTime?.toString(),
                labels = SearchAttributes.decodeLabelsFrom(meta.typedSearchAttributes)
            )
        }
        return ArgoWorkflowList(items = items)
    }

    @GetMapping("/workflows/{namespace}/{name}")
    fun getWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowObject {
        val response = serviceStubs.blockingStub().describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(temporalProperties.namespace)
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(name).build())
                .build()
        )
        val info = response.workflowExecutionInfo
        val labels = if (visibilityProperties.searchAttributesEnabled) {
            SearchAttributes.decodeLabelsFrom(info.searchAttributes)
        } else {
            registry.workflowLabels[name] ?: emptyMap()
        }
        return buildWorkflowObject(
            name = name,
            namespace = namespace,
            uid = info.execution.runId,
            phase = TemporalPhase.argo(info.status),
            startedAt = if (info.hasStartTime()) Instant.ofEpochSecond(
                info.startTime.seconds, info.startTime.nanos.toLong()
            ).toString() else null,
            finishedAt = if (info.hasCloseTime()) Instant.ofEpochSecond(
                info.closeTime.seconds, info.closeTime.nanos.toLong()
            ).toString() else null,
            labels = labels
        )
    }

    @DeleteMapping("/workflows/{namespace}/{name}")
    @ResponseStatus(HttpStatus.OK)
    fun deleteWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): Map<String, Any?> {
        runCatching {
            client.newUntypedWorkflowStub(name, Optional.empty(), Optional.empty())
                .terminate("Deleted via API")
        }
        return emptyMap()
    }

    @PutMapping("/workflows/{namespace}/{name}/suspend")
    @ResponseStatus(HttpStatus.OK)
    fun suspendWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowObject {
        runCatching {
            client.newUntypedWorkflowStub(name, Optional.empty(), Optional.empty())
                .terminate("Suspended via API")
        }
        return buildWorkflowObject(name = name, namespace = namespace, phase = ArgoPhase.ERROR)
    }

    @PutMapping("/workflows/{namespace}/{name}/resume")
    @ResponseStatus(HttpStatus.OK)
    fun resumeWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowObject {
        return getWorkflow(namespace, name)
    }

    @PutMapping("/workflows/{namespace}/{name}/terminate")
    @ResponseStatus(HttpStatus.OK)
    fun terminateWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowObject {
        client.newUntypedWorkflowStub(name, Optional.empty(), Optional.empty())
            .terminate("Terminated via API")
        return buildWorkflowObject(name = name, namespace = namespace, phase = ArgoPhase.ERROR)
    }

    @PutMapping("/workflows/{namespace}/{name}/stop")
    @ResponseStatus(HttpStatus.OK)
    fun stopWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowObject {
        runCatching {
            client.newUntypedWorkflowStub(name, Optional.empty(), Optional.empty())
                .terminate("Stopped via API")
        }
        return buildWorkflowObject(name = name, namespace = namespace, phase = ArgoPhase.ERROR)
    }

    @PostMapping("/workflows/{namespace}/{name}/resubmit")
    @ResponseStatus(HttpStatus.OK)
    fun resubmitWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowObject {
        val stored = registry.allWorkflowTemplates().find { it.name == name }
        if (stored != null) {
            val resource = stored.resource
            val newName = "$name-${UUID.randomUUID().toString().take(8)}"
            val validation = translator.validate(resource)
            if (validation is ValidationResult.Invalid) {
                throw IllegalArgumentException("Workflow validation failed:\n${validation.errors.joinToString("\n")}")
            }
            val definition = translator.translate(resource, newName)
            val execution = launcher.start(newName, definition, labels = resource.metadata.labels)
            return buildWorkflowObject(name = execution.workflowId, namespace = namespace, uid = execution.runId, phase = ArgoPhase.RUNNING)
        }
        return buildWorkflowObject(name = name, namespace = namespace, phase = ArgoPhase.UNKNOWN)
    }

    // ================================================================
    // WorkflowTemplates
    // ================================================================

    @PostMapping("/workflow-templates/{namespace}")
    @ResponseStatus(HttpStatus.OK)
    fun createWorkflowTemplate(
        @PathVariable namespace: String,
        @RequestBody request: ArgoWorkflowTemplateCreateRequest
    ): ArgoWorkflowTemplateObject {
        val tpl = requireNotNull(request.template) { "template is required" }
        val yaml = toYamlString(tpl, ArgoKinds.WORKFLOW_TEMPLATE)
        val resource = ArgoManifestParser.parse(yaml)
        val name = resolveName(resource, "workflow-template")
        registry.put(resource, yaml, name)
        return ArgoWorkflowTemplateObject(
            metadata = ArgoObjectMeta(name = name, namespace = namespace, labels = resource.metadata.labels.takeIf { it.isNotEmpty() }),
            spec = tpl.spec
        )
    }

    @GetMapping("/workflow-templates/{namespace}")
    fun listWorkflowTemplates(
        @PathVariable namespace: String,
        @RequestParam(name = "listOptions.labelSelector", required = false) labelSelector: String?
    ): ArgoWorkflowTemplateList {
        val labelFilter = LabelSelectors.parse(labelSelector)
        val items = registry.allWorkflowTemplates()
            .map { stored ->
                ArgoWorkflowTemplateObject(
                    metadata = ArgoObjectMeta(name = stored.name, namespace = namespace, labels = stored.resource.metadata.labels.takeIf { it.isNotEmpty() })
                )
            }
            .filter { tpl ->
                labelFilter.isEmpty() || LabelSelectors.matches(tpl.metadata?.labels ?: emptyMap(), labelFilter)
            }
        return ArgoWorkflowTemplateList(items = items)
    }

    @GetMapping("/workflow-templates/{namespace}/{name}")
    fun getWorkflowTemplate(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoWorkflowTemplateObject {
        val resource = registry.getWorkflowTemplate(name)
        return ArgoWorkflowTemplateObject(
            metadata = ArgoObjectMeta(name = name, namespace = namespace, labels = resource.metadata.labels.takeIf { it.isNotEmpty() })
        )
    }

    @PutMapping("/workflow-templates/{namespace}/{name}")
    @ResponseStatus(HttpStatus.OK)
    fun updateWorkflowTemplate(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @RequestBody request: ArgoWorkflowTemplateUpdateRequest
    ): ArgoWorkflowTemplateObject {
        val tpl = requireNotNull(request.template) { "template is required" }
        val yaml = toYamlString(tpl, ArgoKinds.WORKFLOW_TEMPLATE)
        val resource = ArgoManifestParser.parse(yaml)
        registry.put(resource, yaml, name)
        return ArgoWorkflowTemplateObject(
            metadata = ArgoObjectMeta(name = name, namespace = namespace, labels = resource.metadata.labels.takeIf { it.isNotEmpty() }),
            spec = tpl.spec
        )
    }

    @DeleteMapping("/workflow-templates/{namespace}/{name}")
    @ResponseStatus(HttpStatus.OK)
    fun deleteWorkflowTemplate(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): Map<String, Any?> {
        registry.removeWorkflowTemplate(name)
        return emptyMap()
    }

    // ================================================================
    // CronWorkflows
    // ================================================================

    @PostMapping("/cron-workflows/{namespace}")
    @ResponseStatus(HttpStatus.OK)
    fun createCronWorkflow(
        @PathVariable namespace: String,
        @RequestBody request: ArgoCronWorkflowCreateRequest
    ): ArgoCronWorkflowObject {
        val cron = requireNotNull(request.cronWorkflow) { "cronWorkflow is required" }
        val yaml = toYamlString(cron, ArgoKinds.CRON_WORKFLOW)
        val resource = ArgoManifestParser.parse(yaml)
        val name = resolveName(resource, "cron-workflow")
        registry.put(resource, yaml, name)
        startCronWorkflow(resource, name)
        return ArgoCronWorkflowObject(
            metadata = ArgoObjectMeta(name = name, namespace = namespace, labels = resource.metadata.labels.takeIf { it.isNotEmpty() }),
            spec = cron.spec
        )
    }

    @GetMapping("/cron-workflows/{namespace}")
    fun listCronWorkflows(
        @PathVariable namespace: String,
        @RequestParam(name = "listOptions.labelSelector", required = false) labelSelector: String?
    ): ArgoCronWorkflowList {
        val labelFilter = LabelSelectors.parse(labelSelector)
        val items = registry.allCronWorkflows()
            .map { stored ->
                ArgoCronWorkflowObject(
                    metadata = ArgoObjectMeta(name = stored.name, namespace = namespace, labels = stored.resource.metadata.labels.takeIf { it.isNotEmpty() })
                )
            }
            .filter { cron ->
                labelFilter.isEmpty() || LabelSelectors.matches(cron.metadata?.labels ?: emptyMap(), labelFilter)
            }
        return ArgoCronWorkflowList(items = items)
    }

    @GetMapping("/cron-workflows/{namespace}/{name}")
    fun getCronWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoCronWorkflowObject {
        val stored = registry.getCronWorkflow(name) ?: throw ResourceNotFoundException("CronWorkflow '$name' not found")
        return ArgoCronWorkflowObject(
            metadata = ArgoObjectMeta(
                name = name,
                namespace = namespace,
                labels = stored.resource.metadata.labels.takeIf { it.isNotEmpty() }
            )
        )
    }

    @PutMapping("/cron-workflows/{namespace}/{name}")
    @ResponseStatus(HttpStatus.OK)
    fun updateCronWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @RequestBody request: ArgoCronWorkflowUpdateRequest
    ): ArgoCronWorkflowObject {
        val cron = requireNotNull(request.cronWorkflow) { "cronWorkflow is required" }
        val yaml = toYamlString(cron, ArgoKinds.CRON_WORKFLOW)
        val resource = ArgoManifestParser.parse(yaml)
        registry.put(resource, yaml, name)
        runCatching {
            client.newUntypedWorkflowStub("cron-$name", Optional.empty(), Optional.empty())
                .terminate("Updated via API")
        }
        startCronWorkflow(resource, name)
        return ArgoCronWorkflowObject(
            metadata = ArgoObjectMeta(name = name, namespace = namespace, labels = resource.metadata.labels.takeIf { it.isNotEmpty() }),
            spec = cron.spec
        )
    }

    @DeleteMapping("/cron-workflows/{namespace}/{name}")
    @ResponseStatus(HttpStatus.OK)
    fun deleteCronWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): Map<String, Any?> {
        runCatching { scheduleClient.getHandle("cron-$name").delete() }
        registry.removeCronWorkflow(name)
        return emptyMap()
    }

    @PutMapping("/cron-workflows/{namespace}/{name}/suspend")
    @ResponseStatus(HttpStatus.OK)
    fun suspendCronWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoCronWorkflowObject {
        registry.suspendedCrons.add(name)
        runCatching { scheduleClient.getHandle("cron-$name").pause("Suspended via API") }
        val stored = registry.getCronWorkflow(name)
        return ArgoCronWorkflowObject(
            metadata = ArgoObjectMeta(
                name = name,
                namespace = namespace,
                labels = stored?.resource?.metadata?.labels?.takeIf { it.isNotEmpty() }
            )
        )
    }

    @PutMapping("/cron-workflows/{namespace}/{name}/resume")
    @ResponseStatus(HttpStatus.OK)
    fun resumeCronWorkflow(
        @PathVariable namespace: String,
        @PathVariable name: String
    ): ArgoCronWorkflowObject {
        val stored = registry.getCronWorkflow(name) ?: throw ResourceNotFoundException("CronWorkflow '$name' not found")
        registry.suspendedCrons.remove(name)
        runCatching { scheduleClient.getHandle("cron-$name").unpause("Resumed via API") }
        return ArgoCronWorkflowObject(
            metadata = ArgoObjectMeta(name = name, namespace = namespace, labels = stored.resource.metadata.labels.takeIf { it.isNotEmpty() })
        )
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun startCronWorkflow(resource: ArgoResource, name: String) {
        val spec = resource.spec.workflowSpec ?: resource.spec
        val schedule = spec.schedule ?: return
        val workflowResource = resource.copy(kind = ArgoKinds.WORKFLOW, spec = spec)
        val validation = translator.validate(workflowResource)
        if (validation is ValidationResult.Invalid) {
            throw IllegalArgumentException("Workflow validation failed:\n${validation.errors.joinToString("\n")}")
        }
        val definition = translator.translate(workflowResource, name)

        val specBuilder = ScheduleSpec.newBuilder()
            .setCronExpressions(listOf(schedule))
        if (!spec.timezone.isNullOrBlank()) {
            specBuilder.setTimeZoneName(spec.timezone)
        }

        val overlapPolicy = when (spec.concurrencyPolicy) {
            "Allow" -> ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_ALLOW_ALL
            "Replace" -> ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_CANCEL_OTHER
            else -> ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP
        }

        val action = ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType(DslWorkflow::class.java)
            .setOptions(
                WorkflowOptions.newBuilder()
                    .setWorkflowId("cron-$name")
                    .setTaskQueue(temporalProperties.taskQueue)
                    .build()
            )
            .setArguments(
                DslWorkflowRequest(
                    DslParser.toYaml(definition),
                    input = buildInput(resource, name),
                    settings = engineProperties.toSettings()
                )
            )
            .build()

        val temporalSchedule = Schedule.newBuilder()
            .setSpec(specBuilder.build())
            .setAction(action)
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(overlapPolicy)
                    .build()
            )
            .build()

        val scheduleId = "cron-$name"
        val createResult = runCatching {
            scheduleClient.createSchedule(scheduleId, temporalSchedule, ScheduleOptions.newBuilder().build())
        }
        if (createResult.exceptionOrNull() is ScheduleAlreadyRunningException) {
            scheduleClient.getHandle(scheduleId).update { ScheduleUpdate(temporalSchedule) }
        } else {
            createResult.getOrThrow()
        }
    }

    private fun toYamlString(obj: Any, kind: String): String {
        val map = jsonMapper.convertValue(obj, Map::class.java).toMutableMap()
        if (!map.containsKey("apiVersion")) map["apiVersion"] = "argoproj.io/v1alpha1"
        if (!map.containsKey("kind")) map["kind"] = kind
        return yamlMapper.writeValueAsString(map)
    }

    private fun buildInput(resource: ArgoResource, name: String): Map<String, Any?> = mapOf(
        "metadata" to mapOf(
            "name" to name,
            "labels" to resource.metadata.labels,
            "annotations" to resource.metadata.annotations
        )
    )

    private fun resolveName(resource: ArgoResource, prefix: String): String =
        resource.metadata.name
            ?: "${resource.metadata.generateName ?: prefix}-${UUID.randomUUID()}"

    private fun buildWorkflowObject(
        name: String,
        namespace: String,
        uid: String? = null,
        phase: String,
        startedAt: String? = null,
        finishedAt: String? = null,
        labels: Map<String, String> = emptyMap()
    ) = ArgoWorkflowObject(
        metadata = ArgoObjectMeta(name = name, namespace = namespace, uid = uid, labels = labels.takeIf { it.isNotEmpty() }),
        status = ArgoWorkflowStatus(phase = phase, startedAt = startedAt, finishedAt = finishedAt)
    )

}  // end ArgoV1Controller
