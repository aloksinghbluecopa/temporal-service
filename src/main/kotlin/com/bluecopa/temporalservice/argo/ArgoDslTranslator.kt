package com.bluecopa.temporalservice.argo

import com.bluecopa.temporalservice.api.DslValidationException
import com.bluecopa.temporalservice.config.ArgoCompatProperties
import com.bluecopa.temporalservice.config.KubernetesProperties
import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.dsl.DslStep
import com.bluecopa.temporalservice.dsl.ForkBranch
import com.bluecopa.temporalservice.dsl.ForkDef
import com.bluecopa.temporalservice.dsl.SwitchCase
import com.bluecopa.temporalservice.dsl.TimeoutDef
import org.springframework.stereotype.Component

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

@Component
class ArgoDslTranslator(
    private val registry: ArgoResourceRegistry,
    private val properties: ArgoCompatProperties,
    private val k8sProperties: KubernetesProperties
) {
    fun validate(resource: ArgoResource): ValidationResult {
        val spec = resource.spec.workflowSpec ?: resource.spec
        val errors = mutableListOf<String>()

        if (spec.templates.isEmpty()) {
            errors.add("Workflow spec must contain at least one template.")
            return ValidationResult.Invalid(errors)
        }

        val templateNames = spec.templates.map { it.name }.toSet()
        val entrypoint = spec.entrypoint ?: properties.defaultEntrypoint
        if (!templateNames.contains(entrypoint)) {
            errors.add("Entrypoint '$entrypoint' does not match any template in the spec.")
        }

        spec.templates.forEach { template ->
            template.steps.flatten().forEach { step ->
                when {
                    step.templateRef != null -> {
                        val result = runCatching { registry.getWorkflowTemplate(step.templateRef.name) }
                        if (result.isFailure) {
                            errors.add("Step '${step.name}' references unknown WorkflowTemplate '${step.templateRef.name}'.")
                        }
                    }
                    step.template != null -> {
                        if (!templateNames.contains(step.template)) {
                            errors.add("Step '${step.name}' references unknown local template '${step.template}'.")
                        }
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    fun translate(resource: ArgoResource, workflowName: String): DslDefinition {
        val spec = resource.spec.workflowSpec ?: resource.spec
        val templates = spec.templates.associateBy { it.name }
        val entrypoint = spec.entrypoint ?: properties.defaultEntrypoint
        val entryTemplate = templates[entrypoint]
            ?: throw DslValidationException("Argo entrypoint template '$entrypoint' was not found.")
        val parameters = resource.spec.arguments.toMap()
        val workflowContext = mapOf(
            "serviceAccountName" to spec.serviceAccountName,
            "volumes" to spec.volumes,
            "imagePullSecrets" to spec.imagePullSecrets,
            "tolerations" to spec.tolerations,
            "targetNamespace" to resource.metadata.namespace
        )

        return DslDefinition(
            id = workflowName,
            steps = translateTemplate(entryTemplate, templates, parameters, workflowContext)
        )
    }

    private fun translateTemplate(
        template: ArgoTemplate,
        localTemplates: Map<String, ArgoTemplate>,
        parameters: Map<String, String>,
        workflowContext: Map<String, Any?> = emptyMap()
    ): List<DslStep> {
        if (template.steps.isEmpty()) {
            return listOf(leafActivityStep(template, parameters, workflowContext))
        }

        return template.steps.map { stepGroup ->
            if (stepGroup.size == 1) {
                translateWorkflowStep(stepGroup.single(), localTemplates, parameters, workflowContext)
            } else {
                DslStep(
                    name = stepGroup.joinToString(separator = "-") { it.name },
                    fork = ForkDef(
                        branches = stepGroup.map { argoStep ->
                            ForkBranch(
                                name = argoStep.name,
                                steps = listOf(translateWorkflowStep(argoStep, localTemplates, parameters, workflowContext))
                            )
                        }
                    )
                )
            }
        }
    }

    private fun translateWorkflowStep(
        step: ArgoWorkflowStep,
        localTemplates: Map<String, ArgoTemplate>,
        inheritedParameters: Map<String, String>,
        workflowContext: Map<String, Any?> = emptyMap()
    ): DslStep {
        val parameters = inheritedParameters + step.arguments.toMap().mapValues { (_, value) ->
            resolveInputParameterReferences(value, inheritedParameters)
        }
        val nestedSteps = when {
            step.templateRef != null -> {
                val referenced = registry.getWorkflowTemplate(step.templateRef.name)
                val referencedTemplates = referenced.spec.templates.associateBy { it.name }
                val referencedTemplate = referencedTemplates[step.templateRef.template]
                    ?: throw DslValidationException(
                        "Template '${step.templateRef.template}' was not found in WorkflowTemplate '${step.templateRef.name}'.",
                    )
                // Merge: WorkflowTemplate spec values take precedence over parent Workflow context
                val refSpec = referenced.spec
                val refContext = mapOf(
                    "serviceAccountName" to (refSpec.serviceAccountName ?: workflowContext["serviceAccountName"]),
                    "volumes" to (refSpec.volumes.takeIf { it.isNotEmpty() } ?: workflowContext["volumes"]),
                    "imagePullSecrets" to (refSpec.imagePullSecrets.takeIf { it.isNotEmpty() } ?: workflowContext["imagePullSecrets"]),
                    "tolerations" to (refSpec.tolerations.takeIf { it.isNotEmpty() } ?: workflowContext["tolerations"]),
                    "targetNamespace" to workflowContext["targetNamespace"]
                )
                translateTemplate(referencedTemplate, referencedTemplates, parameters, refContext)
            }
            step.template != null -> {
                val localTemplate = localTemplates[step.template]
                    ?: throw DslValidationException("Local template '${step.template}' was not found.")
                translateTemplate(localTemplate, localTemplates, parameters, workflowContext)
            }
            else -> throw DslValidationException("Argo step '${step.name}' must define template or templateRef.")
        }

        if (step.`when` != null) {
            return DslStep(
                name = step.name,
                switchCases = listOf(
                    SwitchCase(condition = translateWhenCondition(step.`when`), then = listOf(DslStep(name = step.name, then = nestedSteps))),
                    SwitchCase(otherwise = true, then = emptyList())
                )
            )
        }
        return DslStep(name = step.name, then = nestedSteps)
    }

    private fun translateWhenCondition(condition: String): String {
        val paramRef = Regex("\\{\\{(?:inputs|workflow)\\.parameters\\.([^}]+)}}")
        return paramRef.replace(condition) { match -> "\${ .${match.groupValues[1]} }" }
    }

    private fun leafActivityStep(
        template: ArgoTemplate,
        parameters: Map<String, String>,
        workflowContext: Map<String, Any?> = emptyMap()
    ): DslStep {
        // Merge workflow-level context with template-level overrides
        val effectiveContext = workflowContext.toMutableMap()
        if (template.serviceAccountName != null) effectiveContext["serviceAccountName"] = template.serviceAccountName
        if (template.tolerations.isNotEmpty()) effectiveContext["tolerations"] = template.tolerations
        // Volumes must be MERGED, not replaced: a template's own volumes (e.g. a PVC) are additional to
        // the workflow-level volumes its container still mounts (e.g. dbt-profile, src-workflows-metadata).
        // Replacing here drops the inherited volumes and makes the Job invalid (volumeMount → no such volume).
        if (template.volumes.isNotEmpty()) {
            effectiveContext["volumes"] = mergeVolumesByName(workflowContext["volumes"], template.volumes)
        }

        val inputParameters = template.inputs?.parameters?.map { p ->
            mapOf("name" to p.name, "value" to p.value, "valueFrom" to p.valueFrom)
        } ?: emptyList<Map<String, Any?>>()

        return DslStep(
            name = template.name,
            call = properties.leafActivityName,
            // The leaf activity watches the K8s Job to completion (up to k8s.job-timeout-minutes), so its
            // start-to-close must cover the whole Job run rather than the short generic default. Without
            // this, Temporal kills the activity at the default timeout and retries it every cycle (adopting
            // the same Job), never letting a longer Job finish. A small buffer is added so Temporal does not
            // cancel right as the in-activity watch is timing out. No heartbeat timeout is set: the handler
            // heartbeats only on Job watch events, which go silent during image pulls, so a heartbeat timeout
            // would reintroduce the same spurious-retry loop.
            timeout = TimeoutDef(startToClose = "${k8sProperties.jobTimeoutMinutes + 5}m"),
            arguments = mapOf(
                "templateName" to template.name,
                "parameters" to parameters,
                "inputParameters" to inputParameters,
                "container" to template.container,
                "script" to template.script,
                "workflowContext" to effectiveContext,
                "metadata" to mapOf(
                    "labels" to (template.metadata?.labels ?: emptyMap<String, String>()),
                    "annotations" to (template.metadata?.annotations ?: emptyMap<String, String>())
                )
            ),
            result = template.name
        )
    }

    /** Merges inherited (workflow-level) volumes with a template's own volumes, deduped by name (template wins). */
    private fun mergeVolumesByName(inherited: Any?, templateVolumes: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val inheritedList = (inherited as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
        val byName = LinkedHashMap<String, Map<String, Any?>>()
        (inheritedList + templateVolumes).forEach { vol ->
            val name = vol["name"] as? String ?: return@forEach
            byName[name] = vol
        }
        return byName.values.toList()
    }

    private fun ArgoArguments?.toMap(): Map<String, String> =
        this?.parameters
            ?.filter { it.value != null }
            ?.associate { it.name to it.value.orEmpty() }
            ?: emptyMap()

    private fun resolveInputParameterReferences(value: String, parameters: Map<String, String>): String {
        var resolved = value
        parameters.forEach { (name, parameterValue) ->
            resolved = resolved.replace("{{inputs.parameters.$name}}", parameterValue)
        }
        return resolved
    }
}
