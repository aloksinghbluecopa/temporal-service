package com.bluecopa.temporalservice.api

import org.springframework.http.HttpStatus

/**
 * Stable, machine-readable error codes returned in the `code` field of every error response.
 * Clients should branch on these rather than on HTTP status or human-readable messages.
 */
enum class ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    RESOURCE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    MISSING_PARAMETER,
    WORKFLOW_EXECUTION_FAILED,
    UPSTREAM_TEMPORAL_ERROR,
    CONFLICT,
    UNAUTHORIZED,
    INTERNAL_ERROR,
}

/**
 * Base type for all errors the API raises deliberately. Each carries the HTTP status and the
 * machine-readable [ErrorCode] it should surface as, so the exception handler never has to guess.
 */
sealed class ApiException(
    val status: HttpStatus,
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The requested resource (workflow, template, cron, definition) does not exist → 404. */
class ResourceNotFoundException(message: String) :
    ApiException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, message)

/** The submitted definition/manifest/request is well-formed HTTP but semantically invalid → 400. */
class DslValidationException(message: String, cause: Throwable? = null) :
    ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message, cause)
