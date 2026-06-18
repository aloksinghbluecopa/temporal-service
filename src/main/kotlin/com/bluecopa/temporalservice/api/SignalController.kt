package com.bluecopa.temporalservice.api

import com.bluecopa.temporalservice.workflow.CoordinatorInit
import com.bluecopa.temporalservice.workflow.TriggerSignal
import com.bluecopa.temporalservice.workflow.WorkflowLauncher
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trigger-coordinators")
class SignalController(
    private val launcher: WorkflowLauncher
) {
    @PostMapping("/{id}/signal")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun signal(@PathVariable id: String, @RequestBody body: SignalRequest): SignalResponse {
        val init = CoordinatorInit(
            coordinatorId = id,
            workflowType = body.workflowType,
            callbackUrl = body.callbackUrl
        )
        val signal = TriggerSignal(
            definitionId = body.definitionId,
            idempotencyKey = body.idempotencyKey,
            triggeredBy = body.triggeredBy,
            input = body.input,
            definitionYaml = body.definitionYaml
        )
        launcher.signalWithStart(id, init, signal)
        return SignalResponse(id, true)
    }
}