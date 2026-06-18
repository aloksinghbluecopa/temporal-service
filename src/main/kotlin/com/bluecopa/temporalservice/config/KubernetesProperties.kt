package com.bluecopa.temporalservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "k8s")
data class KubernetesProperties(
    val namespace: String = "default",
    // When true, every Job (and its ConfigMap/Secret/pod-log/delete calls) runs in `namespace`,
    // ignoring any namespace carried by the submitted workflow manifest. Use to pin all execution
    // to a single namespace regardless of manifest contents.
    val forceNamespace: Boolean = false,
    val inCluster: Boolean = false,
    val jobTtlSeconds: Int = 300,
    val jobTimeoutMinutes: Int = 60,
    val jobPollIntervalSeconds: Long = 5,
    val jobHeartbeatIntervalSeconds: Long = 10,
    // When true, failed Jobs are NOT deleted and have no auto-cleanup TTL, so the pod survives for
    // `kubectl logs`/`describe`. Succeeded Jobs are still deleted. Pods linger until removed manually.
    val keepFailedJobs: Boolean = false,
    // Local-testing aid: override each container's resource *requests* (what the scheduler checks)
    // with small values so heavy steps (e.g. dbt-transform's 1500Mi/2cpu) fit a single small node.
    // Limits are left untouched, so the container can still use more memory/cpu when available.
    val minimalResources: Boolean = false,
    val minimalMemoryRequest: String = "256Mi",
    val minimalCpuRequest: String = "100m"
)
