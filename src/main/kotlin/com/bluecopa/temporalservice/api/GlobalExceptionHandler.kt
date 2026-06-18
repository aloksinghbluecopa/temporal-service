package com.bluecopa.temporalservice.api

import com.fasterxml.jackson.core.JacksonException
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.client.WorkflowFailedException
import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI
import java.time.Instant

/**
 * Single source of truth for HTTP error responses. Every error — domain, framework, transport, or
 * unexpected — is rendered as an RFC 7807 `application/problem+json` document enriched with a stable
 * machine-readable [ErrorCode] (`code`), an ISO-8601 `timestamp`, and the request `path`.
 *
 * Extending [ResponseEntityExceptionHandler] gives correct handling of the full Spring MVC error
 * surface (unreadable body, wrong method, unsupported media type, missing params, bean validation,
 * unknown route); [handleExceptionInternal] funnels all of those through the same enrichment so the
 * payload shape is identical everywhere.
 */
@ControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Deliberate domain errors carry their own status + code. */
    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: WebRequest): ResponseEntity<ProblemDetail> {
        log.warn("{} [{}]: {}", ex.code, ex.status.value(), ex.message)
        return respond(ex.status, ex.code, ex.message ?: ex.status.reasonPhrase, request)
    }

    /** `require(...)` / argument guards in controllers and the registry. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: WebRequest): ResponseEntity<ProblemDetail> {
        log.warn("Validation failed: {}", ex.message)
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, ex.message ?: "Invalid request", request)
    }

    /** Malformed YAML/JSON parsed outside the request-body path (e.g. an Argo manifest string). */
    @ExceptionHandler(JacksonException::class)
    fun handleJackson(ex: JacksonException, request: WebRequest): ResponseEntity<ProblemDetail> {
        log.warn("Malformed payload: {}", ex.originalMessage)
        return respond(
            HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
            "Malformed request payload: ${ex.originalMessage}", request,
        )
    }

    /**
     * A workflow ran and failed. Unwrap the underlying Temporal [ApplicationFailure] so the caller
     * gets the real reason (e.g. an invalid expression or duration) as a clean 422 — not an opaque
     * 500 with a stack trace — along with the failure `type` and whether it is `retryable`.
     */
    @ExceptionHandler(WorkflowFailedException::class)
    fun handleWorkflowFailed(ex: WorkflowFailedException, request: WebRequest): ResponseEntity<ProblemDetail> =
        workflowFailureResponse(ex.cause, ex.message, request)

    /** Extracted so the unwrap/mapping logic is unit-testable without the SDK's exception constructor. */
    internal fun workflowFailureResponse(
        rootCause: Throwable?,
        fallbackDetail: String?,
        request: WebRequest,
    ): ResponseEntity<ProblemDetail> {
        val appFailure = generateSequence(rootCause) { it.cause }
            .filterIsInstance<ApplicationFailure>()
            .firstOrNull()
        val detail = appFailure?.originalMessage ?: fallbackDetail ?: "Workflow execution failed"
        log.warn("Workflow execution failed: {}", detail)
        val problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.WORKFLOW_EXECUTION_FAILED, detail, request)
        if (appFailure != null) {
            problem.setProperty("failureType", appFailure.type)
            problem.setProperty("retryable", !appFailure.isNonRetryable)
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem)
    }

    /** Transport errors talking to the Temporal frontend — mapped from gRPC status codes. */
    @ExceptionHandler(StatusRuntimeException::class)
    fun handleGrpc(ex: StatusRuntimeException, request: WebRequest): ResponseEntity<ProblemDetail> {
        val status = when (ex.status.code) {
            Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            Status.Code.INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST
            Status.Code.ALREADY_EXISTS -> HttpStatus.CONFLICT
            Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
            Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_GATEWAY
        }
        val code = if (status == HttpStatus.NOT_FOUND) ErrorCode.RESOURCE_NOT_FOUND else ErrorCode.UPSTREAM_TEMPORAL_ERROR
        log.warn("Temporal gRPC error ({}): {}", ex.status.code, ex.status.description)
        return respond(status, code, ex.status.description ?: "Temporal error (${ex.status.code})", request)
    }

    /** Genuine illegal server state — domain not-found/validation are typed above, so this is a bug. */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException, request: WebRequest): ResponseEntity<ProblemDetail> {
        log.error("Illegal state", ex)
        return respond(
            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
            "An internal error occurred. Check server logs.", request,
        )
    }

    /** Catch-all — never leak internals. */
    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception, request: WebRequest): ResponseEntity<ProblemDetail> {
        log.error("Unhandled error", ex)
        return respond(
            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
            "An internal error occurred. Check server logs.", request,
        )
    }

    /** All Spring MVC framework exceptions funnel here — enrich their ProblemDetail consistently. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = (body as? ProblemDetail) ?: ProblemDetail.forStatus(statusCode)
        enrich(problem, codeForStatus(statusCode), request)
        log.warn("Request error [{}]: {}", statusCode.value(), ex.message)
        return ResponseEntity(problem, headers, statusCode)
    }

    private fun respond(status: HttpStatus, code: ErrorCode, detail: String, request: WebRequest) =
        ResponseEntity.status(status).body(problem(status, code, detail, request))

    private fun problem(status: HttpStatus, code: ErrorCode, detail: String, request: WebRequest): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).also { enrich(it, code, request) }

    private fun enrich(problem: ProblemDetail, code: ErrorCode, request: WebRequest) {
        problem.type = URI.create("/errors/${code.name.lowercase()}")
        problem.setProperty("code", code.name)
        problem.setProperty("timestamp", Instant.now().toString())
        (request as? ServletWebRequest)?.request?.requestURI?.let { problem.setProperty("path", it) }
    }

    private fun codeForStatus(status: HttpStatusCode): ErrorCode = when (status.value()) {
        400 -> ErrorCode.MALFORMED_REQUEST
        404 -> ErrorCode.RESOURCE_NOT_FOUND
        405 -> ErrorCode.METHOD_NOT_ALLOWED
        415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE
        else -> ErrorCode.INTERNAL_ERROR
    }
}
