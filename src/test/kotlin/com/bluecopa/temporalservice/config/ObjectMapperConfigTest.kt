package com.bluecopa.temporalservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test

/**
 * Guards the Jackson bean wiring: declaring a custom ObjectMapper (yamlObjectMapper) makes
 * Spring Boot's JacksonAutoConfiguration back off, so ObjectMapperConfig must re-declare the
 * JSON `jacksonObjectMapper` bean. Without it the app fails to start (ArgoV1Controller injects
 * @Qualifier("jacksonObjectMapper") and StatusCallbackActivityHandler injects a plain ObjectMapper).
 */
class ObjectMapperConfigTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
        .withUserConfiguration(ObjectMapperConfig::class.java)

    @Test
    fun `both json and yaml object mappers are available and resolvable`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasBean("jacksonObjectMapper")
            assertThat(context).hasBean("yamlObjectMapper")
            // The @Qualifier("jacksonObjectMapper") injection used by ArgoV1Controller resolves.
            assertThat(context.getBean("jacksonObjectMapper", ObjectMapper::class.java)).isNotNull()
            // A plain ObjectMapper injection (e.g. StatusCallbackActivityHandler) resolves via @Primary.
            assertThat(context.getBean(ObjectMapper::class.java)).isNotNull()
        }
    }
}
