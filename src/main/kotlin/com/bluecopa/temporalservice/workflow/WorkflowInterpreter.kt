package com.bluecopa.temporalservice.workflow

import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.dsl.DslParser
import com.bluecopa.temporalservice.dsl.DslStep
import com.bluecopa.temporalservice.dsl.ExpressionEvaluator
import com.bluecopa.temporalservice.dsl.ForkDef
import com.bluecopa.temporalservice.dsl.RetryDef
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.Workflow
import java.time.Duration

class WorkflowInterpreter(
    private val definition: DslDefinition,
    input: Map<String, Any?>,
    private val settings: DslEngineSettings
) {
    private val context = ExecutionContext(input.toMutableMap())

    fun execute(): Map<String, Any?> {
        executeSteps(definition.steps, context)
        return context.data.toMap()
    }

    private fun executeSteps(steps: List<DslStep>, ctx: ExecutionContext) {
        for (step in steps) {
            executeStep(step, ctx)
            if (step.compensate.isNotEmpty()) ctx.compensations += step.compensate
        }
    }

    private fun executeStep(step: DslStep, ctx: ExecutionContext) {
        when {
            step.wait != null -> Workflow.sleep(parseDuration(ExpressionEvaluator.evaluate(step.wait, ctx.data).toString()))
            step.call != null -> executeActivity(step, ctx)
            step.switchCases.isNotEmpty() -> executeSwitch(step, ctx)
            step.fork != null -> executeFork(step, step.fork, ctx)
            step.trySteps.isNotEmpty() -> executeTry(step, ctx)
            step.run != null || step.workflow != null -> executeChildWorkflow(step, ctx)
            step.set != null -> executeSet(step.set, ctx)
            step.raise != null -> executeRaise(step.raise, ctx)
        }

        if (step.then.isNotEmpty()) executeSteps(step.then, ctx)
    }

    private fun executeActivity(step: DslStep, ctx: ExecutionContext) {
        val activityType = step.call ?: error("call step is missing activity name")
        val options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(parseDuration(step.timeout?.startToClose ?: settings.defaultActivityStartToClose))
            .setRetryOptions(retryOptions(step.retry?.effective() ?: settings.defaultRetry.effective()))
            .apply {
                step.timeout?.scheduleToClose?.let { setScheduleToCloseTimeout(parseDuration(it)) }
                step.timeout?.heartbeat?.let { setHeartbeatTimeout(parseDuration(it)) }
                step.taskQueue?.let { setTaskQueue(it) }
            }
            .build()
        val activity = Workflow.newUntypedActivityStub(options)
        val input = ExpressionEvaluator.evaluate(step.arguments.ifEmpty { ctx.data }, ctx.data)
        val result = activity.execute(activityType, Any::class.java, input)
        writeResult(step, result, ctx)
    }

    private fun executeSwitch(step: DslStep, ctx: ExecutionContext) {
        val selected = step.switchCases.firstOrNull { case ->
            case.otherwise || ExpressionEvaluator.truthy(case.condition?.let { ExpressionEvaluator.evaluate(it, ctx.data) })
        }
        if (selected != null) executeSteps(selected.then, ctx)
    }

    private fun executeFork(step: DslStep, fork: ForkDef, ctx: ExecutionContext) {
        if (fork.branches.isEmpty()) return
        val branches = fork.branches.mapIndexed { index, branch ->
            val name = branch.name ?: "${settings.branchNamePrefix}-$index"
            lateinit var promise: io.temporal.workflow.Promise<Map<String, Any?>>
            val scope = Workflow.newCancellationScope(Runnable {
                promise = Async.function<Map<String, Any?>> {
                    val branchContext = ExecutionContext(ctx.data.toMutableMap(), ctx.compensations)
                    executeSteps(branch.steps, branchContext)
                    branchContext.data.toMap()
                }
            })
            scope.run()
            ForkExecution(name, scope, promise)
        }

        if (fork.compete) {
            Workflow.await { branches.any { it.promise.isCompleted } }
            val winner = branches.first { it.promise.isCompleted }
            branches.filterNot { it === winner }.forEach { it.scope.cancel(settings.competeCancelReason) }
            writeResult(step, mapOf(winner.name to winner.promise.get()), ctx)
            return
        }

        val branchResults = linkedMapOf<String, Any?>()
        for (branch in branches) {
            branchResults[branch.name] = branch.promise.get()
        }
        writeResult(step, branchResults, ctx)
    }

    private fun executeTry(step: DslStep, ctx: ExecutionContext) {
        try {
            executeSteps(step.trySteps, ctx)
        } catch (failure: Exception) {
            compensate(ctx)
            if (step.catchSteps.isEmpty()) throw failure
            executeSteps(step.catchSteps, ctx)
        }
    }

    private fun compensate(ctx: ExecutionContext) {
        val compensations = ctx.compensations.asReversed().flatten()
        ctx.compensations.clear()
        for (step in compensations) {
            executeStep(step, ctx)
        }
    }

    private fun executeSet(assignments: Map<String, Any?>, ctx: ExecutionContext) {
        val evaluated = ExpressionEvaluator.evaluate(assignments, ctx.data) as? Map<*, *> ?: return
        evaluated.forEach { (key, value) -> ctx.data[key.toString()] = value }
    }

    private fun executeRaise(message: String, ctx: ExecutionContext) {
        val resolved = ExpressionEvaluator.evaluate(message, ctx.data)?.toString() ?: message
        throw ApplicationFailure.newNonRetryableFailure(resolved, "DslRaise")
    }

    private fun executeChildWorkflow(step: DslStep, ctx: ExecutionContext) {
        val target = step.run ?: step.workflow ?: error("run step is missing workflow target")
        val input = ExpressionEvaluator.evaluate(step.input.ifEmpty { ctx.data }, ctx.data) as? Map<*, *> ?: emptyMap<String, Any?>()
        val result = if (definition.workflows.containsKey(target)) {
            val child = Workflow.newChildWorkflowStub(DslWorkflow::class.java)
            child.run(DslWorkflowRequest(DslParser.toYaml(definition.workflows.getValue(target)), input.asStringMap(), settings))
        } else {
            val child = Workflow.newUntypedChildWorkflowStub(
                target,
                ChildWorkflowOptions.newBuilder().build()
            )
            child.execute(Any::class.java, input.asStringMap())
        }
        writeResult(step, result, ctx)
    }

    private fun writeResult(step: DslStep, result: Any?, ctx: ExecutionContext) {
        val key = step.result ?: step.name
        if (key != null) ctx.data[key] = result
    }

    private fun retryOptions(retry: EffectiveRetry): RetryOptions =
        RetryOptions.newBuilder()
            .setMaximumAttempts(retry.maxAttempts)
            .setBackoffCoefficient(retry.backoffCoefficient)
            .apply {
                // Blank/empty duration values (e.g. an unset `max-interval:` in YAML, which Spring
                // binds as "") are treated as "unset" and fall back to Temporal's defaults rather
                // than failing parseDuration.
                retry.initialInterval.takeIf { it.isNotBlank() }?.let { setInitialInterval(parseDuration(it)) }
                retry.maxInterval?.takeIf { it.isNotBlank() }?.let { setMaximumInterval(parseDuration(it)) }
            }
            .build()

    private fun RetryDef.effective(): EffectiveRetry =
        EffectiveRetry(
            maxAttempts = maxAttempts ?: settings.defaultRetry.maxAttempts,
            initialInterval = initialInterval ?: settings.defaultRetry.initialInterval,
            maxInterval = maxInterval ?: settings.defaultRetry.maxInterval,
            backoffCoefficient = backoffCoefficient ?: settings.defaultRetry.backoffCoefficient
        )

    private fun DslRetrySettings.effective(): EffectiveRetry =
        EffectiveRetry(
            maxAttempts = maxAttempts,
            initialInterval = initialInterval,
            maxInterval = maxInterval,
            backoffCoefficient = backoffCoefficient
        )

    private fun parseDuration(value: String): Duration {
        val trimmed = value.trim()
        if (trimmed.startsWith("P")) {
            return runCatching { Duration.parse(trimmed) }.getOrElse {
                throw ApplicationFailure.newNonRetryableFailure("Unsupported duration format: $value", "DslDuration")
            }
        }
        trimmed.toLongOrNull()?.let { return Duration.ofMillis(it) }
        if (trimmed.endsWith("ms")) {
            trimmed.dropLast(2).toLongOrNull()?.let { return Duration.ofMillis(it) }
        }
        val amount = trimmed.dropLast(1).toLongOrNull()
        if (amount != null) {
            when (trimmed.last()) {
                's' -> return Duration.ofSeconds(amount)
                'm' -> return Duration.ofMinutes(amount)
                'h' -> return Duration.ofHours(amount)
                'd' -> return Duration.ofDays(amount)
            }
        }
        throw ApplicationFailure.newNonRetryableFailure("Unsupported duration format: $value", "DslDuration")
    }

    private fun Map<*, *>.asStringMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key.toString() to value }

    private data class ExecutionContext(
        val data: MutableMap<String, Any?>,
        val compensations: MutableList<List<DslStep>> = mutableListOf()
    )

    private data class ForkExecution(
        val name: String,
        val scope: CancellationScope,
        val promise: io.temporal.workflow.Promise<Map<String, Any?>>
    )

    private data class EffectiveRetry(
        val maxAttempts: Int,
        val initialInterval: String,
        val maxInterval: String?,
        val backoffCoefficient: Double
    )
}
