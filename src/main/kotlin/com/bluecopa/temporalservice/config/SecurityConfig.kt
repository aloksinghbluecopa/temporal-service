package com.bluecopa.temporalservice.config

import com.bluecopa.temporalservice.api.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

@ConfigurationProperties(prefix = "security")
data class SecurityProperties(
    val apiKey: String = "",
    val headerName: String = "X-Api-Key",
    val enabled: Boolean = true
)

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val props: SecurityProperties,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

        val authRequired = props.enabled && props.apiKey.isNotBlank()

        http.authorizeHttpRequests { auth ->
            auth.requestMatchers("/actuator/health", "/actuator/info").permitAll()
            if (authRequired) auth.anyRequest().authenticated()
            else auth.anyRequest().permitAll()
        }

        if (authRequired) {
            http.addFilterBefore(ApiKeyFilter(props, objectMapper), UsernamePasswordAuthenticationFilter::class.java)
        }

        return http.build()
    }
}

class ApiKeyFilter(
    private val props: SecurityProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val provided = request.getHeader(props.headerName)
        if (props.apiKey.isNotBlank() && provided != null &&
            MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), props.apiKey.toByteArray(Charsets.UTF_8))
        ) {
            chain.doFilter(request, response)
        } else {
            writeUnauthorized(request, response)
        }
    }

    /** Mirror GlobalExceptionHandler's RFC 7807 shape so auth failures look like every other error. */
    private fun writeUnauthorized(request: HttpServletRequest, response: HttpServletResponse) {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Missing or invalid API key").apply {
            type = URI.create("/errors/${ErrorCode.UNAUTHORIZED.name.lowercase()}")
            setProperty("code", ErrorCode.UNAUTHORIZED.name)
            setProperty("timestamp", Instant.now().toString())
            setProperty("path", request.requestURI)
        }
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(problem))
    }
}
