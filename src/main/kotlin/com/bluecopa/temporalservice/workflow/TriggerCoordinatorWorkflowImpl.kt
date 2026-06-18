package com.bluecopa.temporalservice.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration

class TriggerCoordinatorWorkflowImpl : TriggerCoordinatorWorkflow {
    private val queue = ArrayDeque<TriggerSignal>()
    private var drained = 0
    private lateinit var init: CoordinatorInit

    override fun coordinate(init: CoordinatorInit) {
        this.init = init
        val options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .build()
        val activity = Workflow.newUntypedActivityStub(options)
        while (true) {
            Workflow.await { queue.isNotEmpty() }
            while (queue.isNotEmpty()) {
                val signal = queue.removeFirst()
                activity.execute(
                    "trigger.launch",
                    String::class.java,
                    mapOf(
                        "definitionId" to signal.definitionId,
                        "definitionYaml" to signal.definitionYaml,
                        "input" to signal.input,
                        "callbackUrl" to init.callbackUrl,
                        "targetWorkflowId" to "${init.workflowType}-${signal.idempotencyKey}",
                        "labels" to mapOf(
                            "coordinatorId" to init.coordinatorId,
                            "workflowType" to init.workflowType
                        )
                    )
                )
                drained++
            }
            if (drained >= MAX_DRAIN && queue.isEmpty()) {
                Workflow.continueAsNew(init)
            }
        }
    }

    override fun onTrigger(signal: TriggerSignal) {
        queue.add(signal)
    }

    override fun pending(): List<TriggerSignal> = queue.toList()

    companion object {
        const val MAX_DRAIN = 1000
    }
}