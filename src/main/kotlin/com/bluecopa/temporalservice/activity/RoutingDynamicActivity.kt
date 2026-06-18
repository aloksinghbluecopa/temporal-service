package com.bluecopa.temporalservice.activity

import io.temporal.activity.Activity
import io.temporal.activity.DynamicActivity
import io.temporal.common.converter.EncodedValues
import io.temporal.failure.ApplicationFailure
import org.springframework.stereotype.Component

@Component
class RoutingDynamicActivity(
    handlers: List<DslActivityHandler>
) : DynamicActivity {
    private val handlersByName = handlers.associateBy { it.name }

    override fun execute(args: EncodedValues): Any? {
        val activityType = Activity.getExecutionContext().info.activityType
        val handler = handlersByName[activityType]
            ?: throw ApplicationFailure.newNonRetryableFailure(
                "No DslActivityHandler registered for activity '$activityType'",
                "UnknownActivityType"
            )
        val input = if (args.getSize() == 0) {
            emptyMap()
        } else {
            @Suppress("UNCHECKED_CAST")
            args.get(0, Map::class.java, Map::class.java) as Map<String, Any?>
        }
        return handler.handle(input)
    }
}
