package com.bluecopa.temporalservice.config

import io.temporal.api.enums.v1.IndexedValueType
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest
import io.temporal.serviceclient.OperatorServiceStubs
import io.temporal.serviceclient.OperatorServiceStubsOptions
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Best-effort registration of the custom DSL search attributes against the namespace. Only runs when
 * visibility search attributes are enabled. Clusters without Advanced Visibility (e.g. `start-dev`
 * without Elasticsearch) reject this call, so failures are logged and swallowed — startup must never
 * crash on registration. Re-registering an existing attribute is also a benign failure here.
 */
@Component
class SearchAttributeRegistrar(
    private val temporalProperties: TemporalProperties,
    private val visibilityProperties: VisibilityProperties
) : ApplicationRunner {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!visibilityProperties.searchAttributesEnabled) return

        runCatching {
            val stubs = OperatorServiceStubs.newServiceStubs(
                OperatorServiceStubsOptions.newBuilder()
                    .setTarget(temporalProperties.target)
                    .build()
            )
            try {
                stubs.blockingStub().addSearchAttributes(
                    AddSearchAttributesRequest.newBuilder()
                        .setNamespace(temporalProperties.namespace)
                        .putSearchAttributes(SearchAttributes.DSL_LABELS.name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD_LIST)
                        .putSearchAttributes(SearchAttributes.DSL_DEFINITION_ID.name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
                        .build()
                )
                log.info("Registered DSL custom search attributes on namespace '{}'", temporalProperties.namespace)
            } finally {
                stubs.shutdownNow()
            }
        }.onFailure {
            log.warn(
                "Could not register DSL custom search attributes on namespace '{}' (already registered, or cluster lacks Advanced Visibility): {}",
                temporalProperties.namespace, it.message
            )
        }
    }
}
