package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.api.support.LabelSelectors
import com.bluecopa.temporalservice.argo.ArgoKinds
import com.bluecopa.temporalservice.argo.ArgoManifestParser
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
@RequestMapping("/workflow-templates")
class WorkflowTemplateController(
    private val registry: ArgoResourceRegistry
) {

    /** Register or replace a WorkflowTemplate from an Argo YAML manifest. */
    @PostMapping(consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody yaml: String): TemplateResponse {
        val resource = ArgoManifestParser.parse(yaml)
        require(resource.kind == ArgoKinds.WORKFLOW_TEMPLATE) { "Expected kind: WorkflowTemplate" }
        val name = requireNotNull(resource.metadata.name) { "metadata.name is required for WorkflowTemplate" }
        registry.put(resource, yaml, name)
        return TemplateResponse(name = name, labels = resource.metadata.labels)
    }

    /** Update an existing WorkflowTemplate. */
    @PutMapping("/{name}", consumes = ["text/plain", "application/yaml", "application/x-yaml"])
    fun update(@PathVariable name: String, @RequestBody yaml: String): TemplateResponse {
        val resource = ArgoManifestParser.parse(yaml)
        registry.put(resource, yaml, name)
        return TemplateResponse(name = name, labels = resource.metadata.labels)
    }

    /** Get a WorkflowTemplate by name. */
    @GetMapping("/{name}")
    fun get(@PathVariable name: String): TemplateResponse {
        val resource = registry.getWorkflowTemplate(name)
        return TemplateResponse(name = name, labels = resource.metadata.labels)
    }

    /** List all registered WorkflowTemplates, optionally filtered by label. */
    @GetMapping
    fun list(@RequestParam(required = false) label: String?): TemplateListResponse {
        val labelFilter = LabelSelectors.parse(label)
        val items = registry.allWorkflowTemplates()
            .map { stored -> TemplateResponse(name = stored.name, labels = stored.resource.metadata.labels) }
            .filter { tpl ->
                labelFilter.isEmpty() || LabelSelectors.matches(tpl.labels, labelFilter)
            }
        return TemplateListResponse(items = items, total = items.size)
    }

    /** Delete a WorkflowTemplate by name. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable name: String) {
        registry.removeWorkflowTemplate(name)
    }
}

data class TemplateResponse(
    val name: String,
    val labels: Map<String, String> = emptyMap()
)

data class TemplateListResponse(
    val items: List<TemplateResponse>,
    val total: Int
)
