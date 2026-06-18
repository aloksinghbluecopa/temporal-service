package com.bluecopa.temporalservice.cncf

import com.bluecopa.temporalservice.api.DslValidationException
import com.bluecopa.temporalservice.dsl.DslDefinition
import com.bluecopa.temporalservice.dsl.DslStep
import com.bluecopa.temporalservice.dsl.ForkBranch
import com.bluecopa.temporalservice.dsl.ForkDef
import com.bluecopa.temporalservice.dsl.SwitchCase
import org.springframework.stereotype.Component

/**
 * Translates the supported subset of a CNCF Serverless Workflow v1.0 document into this service's
 * [DslDefinition], mirroring `ArgoDslTranslator`. The translator is honest about its limits: any
 * construct it cannot faithfully represent is rejected with a [DslValidationException] (→ 400
 * `VALIDATION_FAILED`) that names the offending construct.
 *
 * Supported: `call` (function ref + `with`, optional `taskQueue`), `do`, `fork`, `switch`
 * (inline/`otherwise` form), `try`/`catch`, `wait` (durations), `set`, `raise`, `run` (subflow).
 * Rejected: `for`, `listen`, `emit`, HTTP/gRPC/OpenAPI/AsyncAPI `call` variants, goto-style
 * `switch` `then` jumps, and `wait` `until: <timestamp>`.
 *
 * NOTE(jq): CNCF mandates jq runtime expressions. This translator passes `${ ... }` expressions
 * through unchanged; our `ExpressionEvaluator` handles the common subset (path access, comparisons,
 * boolean logic). jq-only features are an accepted limitation — see roadmap item 9.
 */
@Component
class CncfDslTranslator {

    private val httpStyleCalls = setOf("http", "grpc", "openapi", "asyncapi")

    fun translate(workflow: CncfWorkflow, workflowName: String): DslDefinition {
        if (workflow.`do`.isEmpty()) {
            throw DslValidationException("CNCF workflow 'do' must contain at least one task.")
        }
        return DslDefinition(
            id = workflow.document?.name ?: workflowName,
            version = workflow.document?.version,
            steps = translateTasks(workflow.`do`)
        )
    }

    private fun translateTasks(tasks: List<Map<String, CncfTask>>): List<DslStep> =
        tasks.map { entry -> translateTask(singleEntry(entry)) }

    private fun translateTask(named: Pair<String, CncfTask>): DslStep {
        val (name, task) = named
        rejectUnsupported(name, task)
        return when {
            task.call != null -> translateCall(name, task)
            task.`do`.isNotEmpty() -> DslStep(name = name, then = translateTasks(task.`do`))
            task.fork != null -> translateFork(name, task.fork)
            task.switch.isNotEmpty() -> translateSwitch(name, task.switch)
            task.`try`.isNotEmpty() -> translateTry(name, task)
            task.wait != null -> translateWait(name, task.wait)
            task.set != null -> DslStep(name = name, set = task.set)
            task.raise != null -> translateRaise(name, task.raise)
            task.run != null -> translateRun(name, task.run)
            else -> throw DslValidationException("CNCF task '$name' has no supported task type.")
        }
    }

    private fun rejectUnsupported(name: String, task: CncfTask) {
        when {
            task.`for` != null -> reject(name, "for")
            task.listen != null -> reject(name, "listen")
            task.emit != null -> reject(name, "emit")
        }
    }

    private fun translateCall(name: String, task: CncfTask): DslStep {
        val call = requireNotNull(task.call)
        if (call.lowercase() in httpStyleCalls) {
            throw DslValidationException(
                "CNCF task '$name' uses unsupported call variant '$call'; only function-reference calls are supported."
            )
        }
        // TODO(jq): `with` expressions are passed through as-is; the built-in ExpressionEvaluator
        // only handles the `${ }` common subset, not full jq. See roadmap item 9.
        return DslStep(
            name = name,
            call = call,
            taskQueue = task.taskQueue,
            arguments = task.with,
            result = name
        )
    }

    private fun translateFork(name: String, fork: CncfFork): DslStep =
        DslStep(
            name = name,
            fork = ForkDef(
                branches = fork.branches.map { branch ->
                    val (branchName, branchTask) = singleEntry(branch)
                    ForkBranch(name = branchName, steps = listOf(translateTask(branchName to branchTask)))
                },
                compete = fork.compete
            ),
            result = name
        )

    private fun translateSwitch(name: String, cases: List<Map<String, CncfSwitchCase>>): DslStep {
        val switchCases = cases.map { entry ->
            val (caseName, case) = singleEntry(entry)
            val then = translateSwitchThen(name, caseName, case.then)
            if (case.`when` == null) {
                SwitchCase(otherwise = true, then = then)
            } else {
                SwitchCase(condition = case.`when`, then = then)
            }
        }
        return DslStep(name = name, switchCases = switchCases)
    }

    private fun translateSwitchThen(taskName: String, caseName: String, then: Any?): List<DslStep> =
        when (then) {
            null -> emptyList()
            is Map<*, *> -> listOf(translateTask(singleEntry(asTaskMap(then))))
            is List<*> -> translateTasks(then.map { asTaskMap(it) })
            else -> throw DslValidationException(
                "CNCF switch '$taskName' case '$caseName' uses a goto-style 'then: $then'; only inline tasks " +
                    "and 'otherwise' are supported."
            )
        }

    private fun translateTry(name: String, task: CncfTask): DslStep =
        DslStep(
            name = name,
            // NOTE: CNCF error-type filtering on `catch` is not represented; we catch all errors.
            trySteps = translateTasks(task.`try`),
            catchSteps = task.catch?.`do`?.let { translateTasks(it) } ?: emptyList()
        )

    private fun translateWait(name: String, wait: CncfWait): DslStep {
        if (wait.until != null) {
            throw DslValidationException(
                "CNCF task '$name' uses 'wait.until'; only duration waits (seconds/minutes/hours/days) are supported."
            )
        }
        val millis = (wait.days ?: 0) * 86_400_000 +
            (wait.hours ?: 0) * 3_600_000 +
            (wait.minutes ?: 0) * 60_000 +
            (wait.seconds ?: 0) * 1_000 +
            (wait.milliseconds ?: 0)
        if (millis <= 0) {
            throw DslValidationException("CNCF task '$name' 'wait' must specify a positive duration.")
        }
        return DslStep(name = name, wait = millis.toString())
    }

    private fun translateRaise(name: String, raise: CncfRaise): DslStep {
        val message = raise.error?.detail ?: raise.error?.title
            ?: throw DslValidationException("CNCF task '$name' 'raise' must define an error detail or title.")
        return DslStep(name = name, raise = message)
    }

    private fun translateRun(name: String, run: CncfRun): DslStep {
        val target = run.workflow?.name
            ?: throw DslValidationException("CNCF task '$name' 'run' must define a workflow name.")
        return DslStep(name = name, run = target, input = run.workflow.input, result = name)
    }

    private fun reject(name: String, construct: String): Nothing =
        throw DslValidationException("CNCF task '$name' uses unsupported construct '$construct'.")

    private fun <V> singleEntry(map: Map<String, V>): Pair<String, V> {
        val entry = map.entries.singleOrNull()
            ?: throw DslValidationException("CNCF task entries must be single-key maps; got keys ${map.keys}.")
        return entry.key to entry.value
    }

    @Suppress("UNCHECKED_CAST")
    private fun asTaskMap(value: Any?): Map<String, CncfTask> {
        val map = value as? Map<String, *>
            ?: throw DslValidationException("CNCF inline task must be a single-key map; got '$value'.")
        return map.entries.associate { (key, raw) ->
            key to CncfManifestParser.convert(raw)
        }
    }
}
