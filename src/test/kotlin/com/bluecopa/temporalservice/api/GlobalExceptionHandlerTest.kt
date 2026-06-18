package com.bluecopa.temporalservice.api

import io.grpc.Status
import io.temporal.failure.ApplicationFailure
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalExceptionHandlerTest {

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(BoomController())
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `resource not found maps to 404 with code and path`() {
        mvc.perform(get("/boom/not-found"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("missing thing"))
            .andExpect(jsonPath("$.path").value("/boom/not-found"))
            .andExpect(jsonPath("$.timestamp").exists())
    }

    @Test
    fun `dsl validation maps to 400 VALIDATION_FAILED`() {
        mvc.perform(get("/boom/validation"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `illegal argument maps to 400`() {
        mvc.perform(get("/boom/illegal-arg"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `illegal state maps to 500 without leaking`() {
        mvc.perform(get("/boom/illegal-state"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An internal error occurred. Check server logs."))
    }

    @Test
    fun `generic exception maps to 500 without leaking internals`() {
        mvc.perform(get("/boom/generic"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An internal error occurred. Check server logs."))
    }

    @Test
    fun `grpc not found maps to 404`() {
        mvc.perform(get("/boom/grpc-not-found"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `grpc unavailable maps to 503 upstream`() {
        mvc.perform(get("/boom/grpc-unavailable"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("UPSTREAM_TEMPORAL_ERROR"))
    }

    @Test
    fun `malformed json body maps to 400 MALFORMED_REQUEST`() {
        mvc.perform(post("/boom/echo").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
    }

    @Test
    fun `workflow failure is unwrapped to 422 with failure type and retryable`() {
        // The real handler receives a WorkflowFailedException whose cause chain contains the
        // ApplicationFailure; we exercise that unwrap directly via the internal helper.
        val cause = ApplicationFailure.newNonRetryableFailure("Unsupported expression: .x ?", "DslExpression")
        val problem = GlobalExceptionHandler().workflowFailureResponse(
            cause,
            "Workflow execution failed",
            ServletWebRequest(MockHttpServletRequest("POST", "/dsl/workflows/run")),
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, problem.statusCode)
        val body = problem.body!!
        assertEquals("DslExpression", body.properties?.get("failureType"))
        assertEquals(false, body.properties?.get("retryable"))
        assertEquals("WORKFLOW_EXECUTION_FAILED", body.properties?.get("code"))
        assertEquals("Unsupported expression: .x ?", body.detail)
    }

    @RestController
    class BoomController {
        @GetMapping("/boom/not-found")
        fun notFound(): Nothing = throw ResourceNotFoundException("missing thing")

        @GetMapping("/boom/validation")
        fun validation(): Nothing = throw DslValidationException("bad definition")

        @GetMapping("/boom/illegal-arg")
        fun illegalArg(): Nothing = throw IllegalArgumentException("bad arg")

        @GetMapping("/boom/illegal-state")
        fun illegalState(): Nothing = throw IllegalStateException("unexpected state")

        @GetMapping("/boom/generic")
        fun generic(): Nothing = throw RuntimeException("kaboom with secret detail")

        @GetMapping("/boom/grpc-not-found")
        fun grpcNotFound(): Nothing = throw Status.NOT_FOUND.withDescription("nope").asRuntimeException()

        @GetMapping("/boom/grpc-unavailable")
        fun grpcUnavailable(): Nothing = throw Status.UNAVAILABLE.withDescription("down").asRuntimeException()

        @PostMapping("/boom/echo")
        fun echo(@RequestBody body: Map<String, Any?>): Map<String, Any?> = body
    }
}
