package com.bluecopa.temporalservice.dsl

data class ArtifactRef(
    val store: String,
    val bucket: String,
    val key: String,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    val checksum: String? = null,
    val ttlSeconds: Long? = null,
    val transient: Boolean = false
)

object ArtifactRefCodec {
    fun toMap(ref: ArtifactRef): Map<String, Any?> = mapOf(
        "store" to ref.store,
        "bucket" to ref.bucket,
        "key" to ref.key,
        "sizeBytes" to ref.sizeBytes,
        "contentType" to ref.contentType,
        "checksum" to ref.checksum,
        "ttlSeconds" to ref.ttlSeconds,
        "transient" to ref.transient,
    )

    fun fromMap(map: Map<String, Any?>): ArtifactRef? {
        val store = map["store"] as? String ?: return null
        val bucket = map["bucket"] as? String ?: return null
        val key = map["key"] as? String ?: return null
        return ArtifactRef(
            store = store,
            bucket = bucket,
            key = key,
            sizeBytes = (map["sizeBytes"] as? Number)?.toLong(),
            contentType = map["contentType"] as? String,
            checksum = map["checksum"] as? String,
            ttlSeconds = (map["ttlSeconds"] as? Number)?.toLong(),
            transient = map["transient"] as? Boolean ?: false,
        )
    }

    fun isArtifactRef(map: Map<*, *>): Boolean =
        map["store"] is String && map["bucket"] is String && map["key"] is String
}
