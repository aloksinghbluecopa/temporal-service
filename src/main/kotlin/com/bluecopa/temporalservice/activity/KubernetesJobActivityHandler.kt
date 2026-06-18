package com.bluecopa.temporalservice.activity

import com.bluecopa.temporalservice.config.ArgoCompatProperties
import com.bluecopa.temporalservice.config.KubernetesProperties
import com.bluecopa.temporalservice.dsl.ArtifactRef
import com.bluecopa.temporalservice.dsl.ArtifactRefCodec
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1Pod
import java.util.Base64
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import io.temporal.activity.Activity
import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.stringMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)?.mapNotNull { (k, v) ->
        if (k is String && v is String) k to v else null
    }?.toMap() ?: emptyMap()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.anyMap(key: String): Map<String, Any?> =
    (this[key] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        if (k is String) k to v else null
    }?.toMap() ?: emptyMap()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.anyList(key: String): List<Any?> =
    this[key] as? List<*> ?: emptyList<Any?>()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.anyMapOrNull(key: String): Map<String, Any?>? =
    (this[key] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        if (k is String) k to v else null
    }?.toMap()

@Suppress("UNCHECKED_CAST")
private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> =
    entries.associate { (k, v) -> k.toString() to v }

@Suppress("UNCHECKED_CAST")
private fun mergeList(vararg sources: Any?): List<Any> =
    sources.filterNotNull().flatMap { src ->
        (src as? List<*>)?.filterNotNull() ?: emptyList()
    }

@Component
class KubernetesJobActivityHandler(
    argoProperties: ArgoCompatProperties,
    private val k8sProperties: KubernetesProperties
) : DslActivityHandler {

    override val name: String = argoProperties.leafActivityName

    private val log = LoggerFactory.getLogger(javaClass)
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private val apiClient: ApiClient by lazy {
        if (k8sProperties.inCluster) ClientBuilder.cluster().build()
        else ClientBuilder.defaultClient()
    }
    private val batchApi by lazy { BatchV1Api(apiClient) }
    private val coreApi by lazy { CoreV1Api(apiClient) }

    override fun handle(input: Map<String, Any?>): Any? {
        val templateName = input["templateName"] as? String ?: return null
        val container = input.anyMapOrNull("container")
        val script = input.anyMapOrNull("script")
        val parameters = input.anyMap("parameters")
        val workflowContext = input.anyMap("workflowContext")

        // No container spec — nothing to run (e.g. pure steps template)
        val containerSpec = container ?: script
            ?: return mapOf("status" to "SKIPPED", "templateName" to templateName)

        val activityInfo = Activity.getExecutionContext().info
        val workflowId = activityInfo.workflowId

        val jobName = buildJobName(workflowId, templateName)
        // When forceNamespace is set, always run in the configured namespace; otherwise honor the
        // manifest's namespace and fall back to the configured one.
        val namespace = if (k8sProperties.forceNamespace) {
            k8sProperties.namespace
        } else {
            (workflowContext["targetNamespace"] as? String)?.takeIf { it.isNotBlank() }
                ?: k8sProperties.namespace
        }

        val inputParametersList = input.anyList("inputParameters")
        val resolvedParameters = resolveInputParameters(inputParametersList, namespace, parameters)
        val upstreamArtifacts = input.anyMap("upstreamArtifacts")

        log.info("Launching K8s Job '{}' for template '{}' (workflow: {})", jobName, templateName, workflowId)

        val job = buildJob(
            jobName, namespace, templateName, containerSpec, resolvedParameters,
            workflowContext, workflowId, upstreamArtifacts
        )

        val result = try {
            createJob(namespace, job, jobName)
            pollUntilComplete(jobName, namespace, templateName)
        } catch (e: Throwable) {
            // Keep failed Jobs around for `kubectl logs`/`describe` when configured; otherwise clean up.
            if (!k8sProperties.keepFailedJobs) deleteJob(jobName, namespace)
            else log.info("K8s Job '{}' failed; keeping it for inspection (k8s.keep-failed-jobs=true)", jobName)
            throw e
        }
        // Success: always clean up.
        deleteJob(jobName, namespace)

        val produces = input.anyMapOrNull("producesArtifact")
        return if (produces != null && result["status"] == "SUCCEEDED") {
            val ref = ArtifactRef(
                store = (produces["store"] as? String).orEmpty(),
                bucket = resolveString((produces["bucket"] as? String).orEmpty(), resolvedParameters, workflowId),
                key = resolveString((produces["key"] as? String).orEmpty(), resolvedParameters, workflowId),
                contentType = produces["contentType"] as? String,
                ttlSeconds = (produces["ttlSeconds"] as? Number)?.toLong(),
                transient = produces["transient"] as? Boolean ?: false,
            )
            result + mapOf("artifacts" to listOf(ArtifactRefCodec.toMap(ref)))
        } else {
            result
        }
    }

    private fun createJob(namespace: String, job: V1Job, jobName: String) {
        try {
            batchApi.createNamespacedJob(namespace, job).execute()
        } catch (ex: ApiException) {
            // 409 == AlreadyExists: a prior activity attempt already created this Job (the name is
            // deterministic across retries). Adopt it and resume watching instead of failing.
            if (ex.code == 409) {
                log.info("K8s Job '{}' already exists; adopting and resuming watch", jobName)
            } else {
                throw ex
            }
        }
    }

    private fun buildJob(
        jobName: String,
        namespace: String,
        templateName: String,
        containerSpec: Map<String, Any?>,
        parameters: Map<String, Any?>,
        workflowContext: Map<String, Any?>,
        workflowId: String,
        upstreamArtifacts: Map<String, Any?>
    ): V1Job {
        val serviceAccountName = containerSpec["serviceAccountName"] as? String
            ?: workflowContext["serviceAccountName"] as? String

        val volumes = mergeList(workflowContext["volumes"], containerSpec["volumes"])
        val tolerations = mergeList(containerSpec["tolerations"] ?: workflowContext["tolerations"])
        val imagePullSecrets = mergeList(workflowContext["imagePullSecrets"])

        // Resolve Argo template expressions in env vars
        val resolvedContainer = resolveExpressions(containerSpec, parameters, workflowId)
        // Inject upstream artifact references as ARTIFACT_<NAME>_STORE/_BUCKET/_KEY env vars
        val containerWithArtifacts = injectArtifactEnv(resolvedContainer, upstreamArtifacts)

        val podTemplate = mapOf(
            "metadata" to mapOf(
                "labels" to mapOf(
                    "app" to "temporal-job",
                    "template-name" to templateName.take(63)
                )
            ),
            "spec" to buildPodSpec(
                serviceAccountName, containerWithArtifacts, volumes, tolerations, imagePullSecrets
            )
        )

        val spec = mutableMapOf<String, Any?>(
            "backoffLimit" to 0,
            "template" to podTemplate
        )
        // Skip the auto-cleanup TTL when keeping failed Jobs, so a failed pod survives for inspection.
        // (Succeeded Jobs are deleted explicitly, so they don't rely on the TTL.)
        if (!k8sProperties.keepFailedJobs) {
            spec["ttlSecondsAfterFinished"] = k8sProperties.jobTtlSeconds
        }

        val jobSpec = mutableMapOf<String, Any?>(
            "apiVersion" to "batch/v1",
            "kind" to "Job",
            "metadata" to mapOf(
                "name" to jobName,
                "namespace" to namespace,
                "labels" to mapOf(
                    "app" to "temporal-service",
                    "template-name" to templateName.take(63),
                    "managed-by" to "temporal"
                )
            ),
            "spec" to spec
        )

        val yaml = yamlMapper.writeValueAsString(jobSpec)
        return Yaml.loadAs(yaml, V1Job::class.java)
    }

    private fun buildPodSpec(
        serviceAccountName: String?,
        containerSpec: Map<String, Any?>,
        volumes: List<Any>,
        tolerations: List<Any>,
        imagePullSecrets: List<Any>
    ): Map<String, Any?> {
        val podSpec = mutableMapOf<String, Any?>(
            "restartPolicy" to "Never",
            "containers" to listOf(
                buildContainerSpec(containerSpec)
            )
        )
        if (serviceAccountName != null) podSpec["serviceAccountName"] = serviceAccountName
        if (volumes.isNotEmpty()) podSpec["volumes"] = volumes
        if (tolerations.isNotEmpty()) podSpec["tolerations"] = tolerations
        if (imagePullSecrets.isNotEmpty()) podSpec["imagePullSecrets"] = imagePullSecrets
        return podSpec
    }

    private fun buildContainerSpec(containerSpec: Map<String, Any?>): Map<String, Any?> {
        val spec = containerSpec.toMutableMap()
        spec["name"] = "main"

        // For script templates: wrap source as args to the command
        val source = spec.remove("source") as? String
        if (source != null) {
            val command = spec["command"]
            if (command != null) {
                spec["args"] = listOf("-c", source)
            }
        }

        // Remove fields that don't belong in a container spec
        spec.remove("serviceAccountName")
        spec.remove("tolerations")

        // Local testing: shrink scheduling requests so heavy steps fit a small single node.
        // Only requests are overridden; existing limits are preserved.
        if (k8sProperties.minimalResources) {
            val resources = (spec["resources"] as? Map<*, *>)?.toStringKeyMap()?.toMutableMap() ?: mutableMapOf()
            resources["requests"] = mapOf(
                "memory" to k8sProperties.minimalMemoryRequest,
                "cpu" to k8sProperties.minimalCpuRequest
            )
            spec["resources"] = resources
        }

        return spec
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectArtifactEnv(
        containerSpec: Map<String, Any?>,
        upstreamArtifacts: Map<String, Any?>
    ): Map<String, Any?> {
        if (upstreamArtifacts.isEmpty()) return containerSpec
        val existing = (containerSpec["env"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val existingNames = existing.mapNotNull { it["name"] as? String }.toMutableSet()
        val additions = mutableListOf<Map<String, Any?>>()
        upstreamArtifacts.forEach { (name, refAny) ->
            val ref = (refAny as? Map<*, *>) ?: return@forEach
            val prefix = "ARTIFACT_" + name.uppercase().replace(Regex("[^A-Z0-9]"), "_")
            listOf("STORE" to ref["store"], "BUCKET" to ref["bucket"], "KEY" to ref["key"]).forEach { (suffix, value) ->
                val envName = "${prefix}_$suffix"
                if (value != null && envName !in existingNames) {
                    additions.add(mapOf("name" to envName, "value" to value.toString()))
                    existingNames.add(envName)
                }
            }
        }
        if (additions.isEmpty()) return containerSpec
        return containerSpec.toMutableMap().apply { this["env"] = existing + additions }
    }

    private fun resolveInputParameters(
        inputParameters: List<Any?>,
        namespace: String,
        existing: Map<String, Any?>
    ): Map<String, Any?> {
        val resolved = existing.toMutableMap()
        for (param in inputParameters) {
            if (param !is Map<*, *>) continue
            val p = param.toStringKeyMap()
            val name = p["name"] as? String ?: continue
            if (resolved[name] != null) continue
            val valueFrom = p.anyMapOrNull("valueFrom") ?: continue
            val cmRef = valueFrom.anyMapOrNull("configMapKeyRef")
            val secRef = valueFrom.anyMapOrNull("secretKeyRef")
            val value = when {
                cmRef != null -> fetchConfigMapValue(cmRef["name"] as? String ?: continue, namespace, cmRef["key"] as? String ?: continue)
                secRef != null -> fetchSecretValue(secRef["name"] as? String ?: continue, namespace, secRef["key"] as? String ?: continue)
                else -> null
            }
            if (value != null) {
                resolved[name] = value
            } else {
                // A parameter sourced from a ConfigMap/Secret that resolves to null would otherwise launch
                // a Job with an unresolved placeholder (e.g. image "{{inputs.parameters.sourceDockerImage}}"),
                // which never starts and leaves the watch hanging until the Job timeout. Fail fast with a
                // clear, non-retryable error instead — unless the source is explicitly marked optional.
                val ref = cmRef ?: secRef
                val optional = (ref?.get("optional") as? Boolean) ?: false
                if (ref != null && !optional) {
                    val kind = if (cmRef != null) "ConfigMap" else "Secret"
                    throw ApplicationFailure.newNonRetryableFailure(
                        "Required input parameter '$name' could not be resolved: $kind '$namespace/${ref["name"]}' " +
                            "key '${ref["key"]}' not found. The Job would launch with an unresolved value " +
                            "(e.g. an invalid container image). Ensure the $kind exists in namespace '$namespace'.",
                        "MissingParameterSource"
                    )
                }
            }
        }
        return resolved
    }

    private fun fetchConfigMapValue(name: String, namespace: String, key: String): String? =
        runCatching { coreApi.readNamespacedConfigMap(name, namespace).execute().data?.get(key) }
            .getOrElse { log.warn("ConfigMap {}/{} key '{}' not found: {}", namespace, name, key, it.message); null }

    private fun fetchSecretValue(name: String, namespace: String, key: String): String? =
        runCatching {
            val data = coreApi.readNamespacedSecret(name, namespace).execute().data?.get(key) ?: return@runCatching null
            String(Base64.getDecoder().decode(data))
        }.getOrElse { log.warn("Secret {}/{} key '{}' not found: {}", namespace, name, key, it.message); null }

    private fun resolveExpressions(
        containerSpec: Map<String, Any?>,
        parameters: Map<String, Any?>,
        workflowId: String
    ): Map<String, Any?> = resolveMapValue(containerSpec, parameters, workflowId)

    @Suppress("UNCHECKED_CAST")
    private fun resolveMapValue(map: Map<String, Any?>, parameters: Map<String, Any?>, workflowId: String): Map<String, Any?> =
        map.mapValues { (_, v) -> resolveAnyValue(v, parameters, workflowId) }

    @Suppress("UNCHECKED_CAST")
    private fun resolveAnyValue(value: Any?, parameters: Map<String, Any?>, workflowId: String): Any? = when (value) {
        is String -> resolveString(value, parameters, workflowId)
        is List<*> -> value.map { resolveAnyValue(it, parameters, workflowId) }
        is Map<*, *> -> (value as Map<String, Any?>).mapValues { (_, v) -> resolveAnyValue(v, parameters, workflowId) }
        else -> value
    }

    private fun resolveString(value: String, parameters: Map<String, Any?>, workflowId: String): String {
        var s = value.replace("{{workflow.uid}}", workflowId).replace("{{workflow.name}}", workflowId)
        parameters.forEach { (k, pv) ->
            s = s.replace("{{inputs.parameters.$k}}", pv.toString())
                .replace("{{workflow.parameters.$k}}", pv.toString())
        }
        return s
    }

    private fun pollUntilComplete(jobName: String, namespace: String, templateName: String): Map<String, Any?> {
        val timeoutMillis = k8sProperties.jobTimeoutMinutes * 60_000L
        val pollMillis = (k8sProperties.jobPollIntervalSeconds * 1000L).coerceAtLeast(1000L)
        val startedAt = System.currentTimeMillis()
        // Tracks how long the pod has been in an unrecoverable state, to tolerate transient image pulls.
        var podBlockedSince: Long? = null

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val status = runCatching { batchApi.readNamespacedJob(jobName, namespace).execute().status }.getOrNull()
            // Heartbeat each cycle so worker death / cancellation is detected promptly.
            Activity.getExecutionContext().heartbeat(mapOf("jobName" to jobName, "active" to status?.active))

            if ((status?.succeeded ?: 0) > 0) {
                log.info("K8s Job '{}' for template '{}' succeeded", jobName, templateName)
                return mapOf("status" to "SUCCEEDED", "jobName" to jobName, "templateName" to templateName)
            }
            if ((status?.failed ?: 0) > 0) {
                val diag = fetchPodDiagnostics(jobName, namespace)
                log.error("K8s Job '{}' for template '{}' failed.\n{}", jobName, templateName, diag)
                throw ApplicationFailure.newFailure(
                    "K8s Job '$jobName' (template: $templateName) failed.\n$diag",
                    "KubernetesJobFailed"
                )
            }

            // The Job is not yet succeeded/failed. A pod stuck in an unrecoverable waiting state
            // (bad image, missing mounted ConfigMap/Secret, unschedulable) never flips the Job to
            // failed, so without this it would hang until the full timeout. Fail fast — but allow a
            // short grace window so a slow-but-legitimate image pull is not killed prematurely.
            val blocker = detectPodBlocker(jobName, namespace)
            if (blocker != null) {
                val firstSeen = podBlockedSince ?: System.currentTimeMillis().also { podBlockedSince = it }
                if (System.currentTimeMillis() - firstSeen > POD_BLOCKER_GRACE_MILLIS) {
                    val diag = fetchPodDiagnostics(jobName, namespace)
                    log.error("K8s Job '{}' for template '{}' cannot start ({}).\n{}", jobName, templateName, blocker, diag)
                    throw ApplicationFailure.newNonRetryableFailure(
                        "K8s Job '$jobName' (template: $templateName) cannot start: $blocker.\n$diag",
                        "KubernetesJobStuck"
                    )
                }
            } else {
                podBlockedSince = null
            }

            Thread.sleep(pollMillis)
        }

        val diag = fetchPodDiagnostics(jobName, namespace)
        throw ApplicationFailure.newFailure(
            "K8s Job '$jobName' (template: $templateName) timed out after ${k8sProperties.jobTimeoutMinutes} minutes.\n$diag",
            "KubernetesJobTimeout"
        )
    }

    /** Returns a human-readable reason if the pod is in an unrecoverable state, else null. */
    private fun detectPodBlocker(jobName: String, namespace: String): String? = runCatching {
        val pod = firstPod(jobName, namespace) ?: return null
        val waiting = pod.status?.containerStatuses?.firstOrNull()?.state?.waiting
        if (waiting != null && waiting.reason in POD_BLOCKER_REASONS) {
            return "${waiting.reason}: ${waiting.message.orEmpty()}".trim()
        }
        if (pod.status?.phase == "Pending") {
            val unsched = pod.status?.conditions?.firstOrNull { it.type == "PodScheduled" && it.status == "False" }
            if (unsched?.reason == "Unschedulable") return "Unschedulable: ${unsched.message.orEmpty()}".trim()
        }
        null
    }.getOrNull()

    private fun firstPod(jobName: String, namespace: String): V1Pod? =
        coreApi.listNamespacedPod(namespace)
            .labelSelector("batch.kubernetes.io/job-name=$jobName")
            .execute().items.firstOrNull()

    /** Pod phase, container state, recent events, and logs — for diagnosing why a Job failed or stalled. */
    private fun fetchPodDiagnostics(jobName: String, namespace: String): String = runCatching {
        val pod = firstPod(jobName, namespace) ?: return "No pods found for job '$jobName'."
        val podName = pod.metadata?.name
        val phase = pod.status?.phase
        val containerState = pod.status?.containerStatuses?.firstOrNull()?.state?.let { st ->
            when {
                st.waiting != null -> "waiting(${st.waiting!!.reason}: ${st.waiting!!.message.orEmpty()})"
                st.terminated != null -> "terminated(${st.terminated!!.reason}, exit=${st.terminated!!.exitCode})"
                st.running != null -> "running"
                else -> "unknown"
            }
        } ?: "n/a"
        val events = runCatching {
            coreApi.listNamespacedEvent(namespace).execute().items
                .filter { it.involvedObject?.name == podName }
                .takeLast(10)
                .joinToString("\n") { "  [${it.reason}] ${it.message}" }
        }.getOrElse { "" }
        val logs = if (phase != "Pending") fetchPodLogs(podName, namespace) else "(pod not started — no logs)"
        buildString {
            appendLine("Pod: $podName  phase=$phase  container=$containerState")
            if (events.isNotBlank()) {
                appendLine("Recent events:")
                appendLine(events)
            }
            appendLine("Logs (tail):")
            append(logs)
        }
    }.getOrElse { "Could not gather pod diagnostics: ${it.message}" }

    private fun fetchPodLogs(podName: String?, namespace: String): String = runCatching {
        if (podName == null) return "No pods found"
        coreApi.readNamespacedPodLog(podName, namespace)
            .container("main")
            .tailLines(200)
            .execute()
    }.getOrElse { "Could not retrieve logs: ${it.message}" }

    private fun deleteJob(jobName: String, namespace: String) {
        runCatching {
            batchApi.deleteNamespacedJob(jobName, namespace)
                .propagationPolicy("Foreground")
                .execute()
            log.debug("Deleted K8s Job '{}'", jobName)
        }
    }

    companion object {
        // Container waiting reasons that won't resolve on their own (bad image, missing mounted
        // ConfigMap/Secret, etc.). The Job never flips to failed for these, so we fail fast.
        val POD_BLOCKER_REASONS = setOf(
            "InvalidImageName", "ErrImagePull", "ImagePullBackOff",
            "CreateContainerConfigError", "CreateContainerError", "RunContainerError", "CrashLoopBackOff"
        )

        // Grace window before treating a blocked pod as fatal — tolerates a slow first image pull.
        const val POD_BLOCKER_GRACE_MILLIS = 120_000L

        /**
         * Builds a Kubernetes Job name that is deterministic for a given (workflowId, templateName).
         * Activity retries of the same step therefore target the same Job rather than creating a
         * duplicate. The 8-char suffix is a stable hash of both inputs; the whole name is bounded to
         * the 63-char Kubernetes name limit and the `ts-` prefix and sanitization are preserved.
         */
        fun buildJobName(workflowId: String, templateName: String): String {
            val sanitized = templateName.lowercase().replace(Regex("[^a-z0-9]"), "-").take(38)
            val suffix = deterministicSuffix(workflowId, templateName)
            return "ts-$sanitized-$suffix".take(63)
        }

        private fun deterministicSuffix(workflowId: String, templateName: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$workflowId/$templateName".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(8)
        }
    }
}
