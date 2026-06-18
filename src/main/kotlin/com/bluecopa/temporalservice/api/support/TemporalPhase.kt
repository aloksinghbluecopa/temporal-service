package com.bluecopa.temporalservice.api.support

import com.bluecopa.temporalservice.api.ArgoPhase
import io.temporal.api.enums.v1.WorkflowExecutionStatus

object TemporalPhase {
    fun argo(status: WorkflowExecutionStatus): String = when (status) {
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> ArgoPhase.RUNNING
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> ArgoPhase.RUNNING
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> ArgoPhase.SUCCEEDED
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED -> ArgoPhase.FAILED
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED -> ArgoPhase.ERROR
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED -> ArgoPhase.ERROR
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> ArgoPhase.ERROR
        else -> ArgoPhase.UNKNOWN
    }

    fun native(status: WorkflowExecutionStatus): String = when (status) {
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> "Running"
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> "Succeeded"
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED -> "Failed"
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED -> "Stopped"
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED -> "Terminated"
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> "TimedOut"
        else -> "Unknown"
    }
}
