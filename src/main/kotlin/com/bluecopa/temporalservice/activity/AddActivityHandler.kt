package com.bluecopa.temporalservice.activity

import io.temporal.failure.ApplicationFailure
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class AddActivityHandler : DslActivityHandler {
    override val name: String = "math.add"

    override fun handle(input: Map<String, Any?>): Any? {
        val values = input["values"] as? List<*> ?: emptyList<Any?>()
        val sum = values.fold(BigDecimal.ZERO) { acc, value -> acc + toBigDecimal(value) }
        return mapOf("sum" to sum)
    }

    private fun toBigDecimal(value: Any?): BigDecimal =
        when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal.valueOf(value.toDouble())
            is String -> value.toBigDecimalOrNull()
                ?: throw ApplicationFailure.newNonRetryableFailure("math.add cannot sum non-numeric value '$value'", "MathAdd")
            else -> throw ApplicationFailure.newNonRetryableFailure("math.add cannot sum value of type ${value?.let { it::class.simpleName }}", "MathAdd")
        }
}
