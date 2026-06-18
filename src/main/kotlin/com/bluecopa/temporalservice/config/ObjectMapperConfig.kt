package com.bluecopa.temporalservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
class ObjectMapperConfig {
    /**
     * Declaring any ObjectMapper bean makes Spring Boot's JacksonAutoConfiguration back off
     * (it is @ConditionalOnMissingBean(ObjectMapper)), which would otherwise remove the
     * `jacksonObjectMapper` bean that controllers inject and the primary mapper WebMVC uses.
     * So we re-declare the JSON mapper explicitly, built from Boot's auto-configured builder
     * (which applies Boot defaults and auto-registers the Kotlin module from the classpath),
     * and mark it @Primary so plain ObjectMapper injections resolve to it.
     */
    @Bean
    @Primary
    fun jacksonObjectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper = builder.build()

    @Bean
    fun yamlObjectMapper(): ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
}
