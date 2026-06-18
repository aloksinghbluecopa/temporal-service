package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.api.support.LabelSelectors
import com.bluecopa.temporalservice.argo.ArgoKinds
import com.bluecopa.temporalservice.argo.ArgoManifestParser
import com.bluecopa.temporalservice.argo.ArgoResource
import com.bluecopa.temporalservice.argo.ArgoResourceRegistry
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

@RestController
@RequestMapping("/cron-workflows")
class CronWorkflowController(
    private val registry: ArgoResourceRegistry
) {

    /** Register a CronWorkflow from an Argo YAML manifest. */
    @PostMapping(consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody yaml: String): CronWorkflowResponse {
        val resource = ArgoManifestParser.parse(yaml)
        require(resource.kind == ArgoKinds.CRON_WORKFLOW) { "Expected kind: CronWorkflow" }
        val name = requireNotNull(resource.metadata.name) { "metadata.name is required for CronWorkflow" }
        registry.put(resource, yaml, name)
        return toResponse(name, resource)
    }

    /** Update a CronWorkflow. */
    @PutMapping("/{name}", consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    fun update(@PathVariable name: String, @RequestBody yaml: String): CronWorkflowResponse {
        val resource = ArgoManifestParser.parse(yaml)
        registry.put(resource, yaml, name)
        return toResponse(name, resource)
    }

    /** Get a CronWorkflow by name. */
    @GetMapping("/{name}")
    fun get(@PathVariable name: String): CronWorkflowResponse {
        val stored = registry.getCronWorkflow(name) ?: throw ResourceNotFoundException("CronWorkflow '$name' not found")
        return toResponse(name, stored.resource)
    }

    /** List all CronWorkflows, optionally filtered by label. */
    @GetMapping
    fun list(@RequestParam(required = false) label: String?): CronWorkflowListResponse {
        val labelFilter = LabelSelectors.parse(label)
        val items = registry.allCronWorkflows()
            .map { stored -> toResponse(stored.name, stored.resource) }
            .filter { cron ->
                labelFilter.isEmpty() || LabelSelectors.matches(cron.labels, labelFilter)
            }
        return CronWorkflowListResponse(items = items, total = items.size)
    }

    /** Suspend a CronWorkflow (stops future triggers). */
    @PostMapping("/{name}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun suspend(@PathVariable name: String) {
        registry.getCronWorkflow(name) ?: throw ResourceNotFoundException("CronWorkflow '$name' not found")
        registry.suspendedCrons.add(name)
    }

    /** Resume a suspended CronWorkflow. */
    @PostMapping("/{name}/resume")
    fun resume(@PathVariable name: String): CronWorkflowResponse {
        registry.suspendedCrons.remove(name)
        return get(name)
    }

    /** Delete a CronWorkflow. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable name: String) {
        registry.removeCronWorkflow(name)
    }

    private fun toResponse(name: String, resource: ArgoResource): CronWorkflowResponse {
        val schedule = resource.spec.schedule ?: resource.spec.workflowSpec?.schedule ?: ""
    return CronWorkflowResponse(
            name = name,
            schedule = schedule,
            suspended = registry.suspendedCrons.contains(name),
            labels = resource.metadata.labels
        )
    }
}

data class CronWorkflowResponse(
    val name: String,
    val schedule: String,
    val suspended: Boolean = false,
    val labels: Map<String, String> = emptyMap()
)

data class CronWorkflowListResponse(
    val items: List<CronWorkflowResponse>,
    val total: Int
)
