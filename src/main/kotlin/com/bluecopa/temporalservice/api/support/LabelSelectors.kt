package com.bluecopa.temporalservice.api.support

object LabelSelectors {
    fun parse(selector: String?): Map<String, String> {
        if (selector.isNullOrBlank()) return emptyMap()
        return selector.split(",")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size != 2) null else parts[0].trim() to parts[1].trim()
            }
            .toMap()
    }

    fun matches(labels: Map<String, String>, filter: Map<String, String>): Boolean =
        filter.all { (key, value) -> labels[key] == value }
}
