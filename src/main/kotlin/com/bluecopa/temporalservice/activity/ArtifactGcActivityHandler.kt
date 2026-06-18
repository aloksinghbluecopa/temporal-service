package com.bluecopa.temporalservice.activity

import com.bluecopa.temporalservice.dsl.ArtifactRefCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.anyList(key: String): List<Any?> =
    this[key] as? List<*> ?: emptyList<Any?>()

@Component
class ArtifactGcActivityHandler : DslActivityHandler {
    override val name: String = "artifact.gc"
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(input: Map<String, Any?>): Any? {
        val refs = input.anyList("artifacts")
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { ArtifactRefCodec.fromMap(it.entries.associate { (k, v) -> k.toString() to v }) }
        val candidates = refs.filter { it.transient || it.ttlSeconds != null }
        if (candidates.isNotEmpty()) {
            log.info(
                "artifact.gc: {} candidate(s) eligible for deletion (transient/ttl): {}",
                candidates.size, candidates.map { "${it.bucket}/${it.key}" }
            )
        }
        return mapOf("status" to "RECORDED", "candidates" to candidates.map { it.key })
    }
}
