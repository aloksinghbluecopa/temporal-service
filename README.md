# Temporal Service

A **Temporal-backed workflow engine** that interprets declarative YAML workflow definitions at runtime. Instead of compiling one Temporal workflow class per business process, this service ships a **single, generic, deterministic workflow** (`DslWorkflow`) that reads a YAML/DSL definition, walks the task graph, and dispatches each step to Temporal activities.

This gives you the durability, retries, and observability of [Temporal](https://temporal.io/) while letting you define and change workflows as data — no worker redeploys required. It also speaks an **Argo Workflows–compatible API**, so existing Argo manifests (`Workflow`, `WorkflowTemplate`, `CronWorkflow`) and clients can drive Temporal-backed executions that run their steps as Kubernetes Jobs.

---

## Table of Contents

- [Why this exists](#why-this-exists)
- [Architecture](#architecture)
- [How it works (end to end)](#how-it-works-end-to-end)
- [Prerequisites](#prerequisites)
- [Project setup](#project-setup)
- [Running the service](#running-the-service)
- [The workflow DSL](#the-workflow-dsl)
- [Expression language](#expression-language)
- [Built-in activities](#built-in-activities)
- [Adding your own activities](#adding-your-own-activities)
- [REST API reference](#rest-api-reference)
- [Error responses](#error-responses)
- [Argo compatibility](#argo-compatibility)
- [CNCF Serverless Workflow (experimental import)](#cncf-serverless-workflow-experimental-import)
- [Kubernetes job execution](#kubernetes-job-execution)
- [Configuration reference](#configuration-reference)
- [Using this service from other applications](#using-this-service-from-other-applications)
- [Testing](#testing)
- [Project layout](#project-layout)

---

## Why this exists

A normal Temporal application defines each workflow as compiled code. Every new workflow or change means a code change, a build, and a worker redeploy.

This service flips that around:

- **One workflow type, many definitions.** `DslWorkflow` is the only registered workflow implementation. It accepts a YAML definition + input and interprets it.
- **Activities are routed by name at runtime.** A single `DynamicActivity` (`RoutingDynamicActivity`) receives every `call` and forwards it to the matching Spring `DslActivityHandler` bean. New definitions referencing already-registered activities run instantly — no restart.
- **Workflows are submitted over HTTP.** You `POST` a YAML/JSON definition and get back a workflow id + run id. Status, suspension, and termination are all REST calls.
- **Argo-compatible.** Argo `Workflow` / `WorkflowTemplate` / `CronWorkflow` manifests are translated into the DSL and executed on Temporal, with leaf steps launched as Kubernetes Jobs.

---

## Architecture

```
                ┌──────────────────────────────────────────────────────────┐
   HTTP         │                  Spring Boot service                      │
 client ──────► │                                                            │
                │  Controllers                                               │
                │   • DslWorkflowController      /dsl/...                    │
                │   • WorkflowController         /workflows                  │
                │   • WorkflowTemplateController /workflow-templates         │
                │   • CronWorkflowController     /cron-workflows             │
                │   • ArgoV1Controller           /api/v1/...                 │
                │         │                                                  │
                │         │ translate (Argo → DSL) + start workflow          │
                │         ▼                                                  │
                │   WorkflowClient ───────────────┐                          │
                └─────────────────────────────────┼──────────────────────────┘
                                                  │ gRPC (127.0.0.1:7233)
                                                  ▼
                                        ┌────────────────────┐
                                        │   Temporal server  │
                                        └─────────┬──────────┘
                                                  │ task queue: dsl-task-queue
                ┌─────────────────────────────────┼──────────────────────────┐
                │            Worker (same JVM, started by WorkerFactory)       │
                │                                  ▼                           │
                │   DslWorkflowImpl  ──►  WorkflowInterpreter                  │
                │        (deterministic)     walks steps, evaluates            │
                │                            expressions, schedules            │
                │                            activities / child workflows      │
                │                                  │ call "name"               │
                │                                  ▼                           │
                │   RoutingDynamicActivity  ──►  DslActivityHandler beans      │
                │                                  • echo                      │
                │                                  • math.add                  │
                │                                  • argo.template.execute ──► K8s Job
                │                                  • __status.callback         │
                └──────────────────────────────────────────────────────────────┘
```

**Key components**

| Component | File | Responsibility |
|-----------|------|----------------|
| `DslWorkflow` / `DslWorkflowImpl` | `workflow/` | The single deterministic Temporal workflow. Parses the definition and runs the interpreter; fires an optional completion callback. |
| `WorkflowInterpreter` | `workflow/WorkflowInterpreter.kt` | Walks the DSL graph: `call`, `switch`, `fork`, `try/catch/compensate`, `wait`, child workflows, retries, timeouts. |
| `RoutingDynamicActivity` | `activity/RoutingDynamicActivity.kt` | The single `DynamicActivity` that routes a `call` by activity name to the right handler bean. |
| `DslActivityHandler` | `activity/DslActivityHandler.kt` | Interface every activity implements (`name` + `handle(input)`). |
| `ExpressionEvaluator` | `dsl/ExpressionEvaluator.kt` | Evaluates `${ ... }` expressions (path access, comparisons, boolean logic). |
| `DslParser` / `DslModel` | `dsl/` | YAML ⇄ DSL model (Jackson). |
| `ArgoManifestParser` / `ArgoDslTranslator` | `argo/` | Parse Argo manifests and translate them into the DSL. |
| `KubernetesJobActivityHandler` | `activity/` | Leaf activity that turns an Argo template into a Kubernetes Job and waits for it. |
| `WorkflowDefinitionRegistry` / `ArgoResourceRegistry` | `registry/`, `argo/` | File-backed storage of DSL definitions and Argo resources. |
| `TemporalConfig` | `config/TemporalConfig.kt` | Wires the Temporal client, worker factory, and data converter. |

---

## How it works (end to end)

1. A client submits a definition — either raw DSL YAML/JSON (`/dsl/...`) or an Argo manifest (`/workflows`, `/api/v1/...`).
2. For Argo manifests, `ArgoDslTranslator` converts the entrypoint template (and any referenced templates) into the DSL model.
3. The controller starts a `DslWorkflow` execution on Temporal via `WorkflowClient`, passing `DslWorkflowRequest(definitionYaml, input, settings, callbackUrl?)`.
4. The worker picks up the task. `DslWorkflowImpl.run` parses the YAML and hands it to `WorkflowInterpreter`.
5. The interpreter walks the steps deterministically. Each `call` becomes an untyped activity invocation routed by `RoutingDynamicActivity` to the matching handler bean. `${ ... }` expressions are resolved against the accumulating execution context.
6. Step results are written back into the context under `result` (or the step `name`), so later steps can reference them.
7. On completion (or failure) an optional HTTP callback fires with the final result.

The whole execution is durable: retries, timeouts, `fork` parallelism, and `try/catch/compensate` (saga-style) are all backed by Temporal's event history.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 21 | Gradle toolchain targets Java 21. |
| **Gradle** | 8.5 | Use the bundled wrapper (`./gradlew`); no separate install needed. |
| **Temporal server** | any recent | Reachable at `127.0.0.1:7233` by default. |
| **Kubernetes** | optional | Only needed if you use the `argo.template.execute` activity / Argo manifests. A kubeconfig (out of cluster) or in-cluster service account is required. |
| **Docker** | optional | Easiest way to run a local Temporal dev server. |

### Start a local Temporal server

The simplest option is the Temporal CLI dev server:

```bash
# Install the Temporal CLI (https://docs.temporal.io/cli), then:
temporal server start-dev
# UI at http://localhost:8233, gRPC at 127.0.0.1:7233
```

Or with Docker Compose using the official [temporalio/docker-compose](https://github.com/temporalio/docker-compose) repo.

---

## Project setup

```bash
# 1. Clone
git clone <your-repo-url> temporal-service
cd temporal-service

# 2. Build (downloads dependencies, compiles, runs tests)
./gradlew build

# 3. (optional) Build a runnable jar
./gradlew bootJar
# -> build/libs/temporal-service-0.0.1-SNAPSHOT.jar
```

> On Windows use `gradlew.bat` instead of `./gradlew`.

The build uses Spring Boot 3.3.10, Kotlin 1.9.24, Undertow (Tomcat is excluded), the Temporal Java SDK 1.25.0, and the official Kubernetes Java client.

---

## Running the service

```bash
# Run from source
./gradlew bootRun

# Or run the jar
java -jar build/libs/temporal-service-0.0.1-SNAPSHOT.jar
```

By default the service:

- listens on **`http://localhost:8082`**,
- connects to Temporal at **`127.0.0.1:7233`**, namespace **`default`**,
- runs a worker on task queue **`dsl-task-queue`**,
- stores registered definitions/resources under **`/tmp/temporal-service/registry`**.

Override any of these via environment variables or JVM args (Spring Boot relaxed binding):

```bash
# point at a remote Temporal cluster and a different port
TEMPORAL_TARGET=temporal.internal:7233 \
TEMPORAL_NAMESPACE=production \
SERVER_PORT=9000 \
./gradlew bootRun
```

### Quick smoke test

```bash
# Start the bundled example order workflow synchronously and see the result
curl -s -X POST http://localhost:8082/dsl/workflows/run \
  -H 'content-type: application/json' \
  -d @examples/start-order.json | jq
```

---

## The workflow DSL

A definition is a YAML (or JSON) document with an `id` and a list of `tasks` (alias: `do`). Each task is one of several step types. Steps execute sequentially; their results accumulate in a shared **context** that expressions read from.

```yaml
id: order
version: "1.0"
tasks:
  - name: reserve
    call: echo                 # activity to invoke (routed by name)
    with:                      # arguments passed to the activity
      sku: "${ .sku }"
      quantity: "${ .quantity }"
    result: reservation        # where to store the activity result in context
    timeout:
      startToClose: 10s
    retry:
      maxAttempts: 3
      initialInterval: 1s
```

### Step types

| Field(s) | Meaning |
|----------|---------|
| `call` + `with` | Invoke an activity by name with the (expression-evaluated) `with` arguments. |
| `switch` | List of `{ when: <expr>, then: [...] }` cases plus an `{ otherwise: true, then: [...] }` fallback. First matching case runs. |
| `fork` | Run branches in parallel. `compete: true` cancels the losers once the first branch completes. |
| `try` / `catch` / `compensate` | Saga-style error handling. On failure, registered compensations run in reverse order; `catch` steps run after. |
| `wait` | Sleep for a duration (expression-evaluated, e.g. `"${ .delay }"` or `30s`). |
| `run` / `workflow` | Invoke a child workflow — either a named sub-workflow defined under top-level `workflows:`, or an external workflow type by name. |
| `then` | Steps to run immediately after this step (used for nesting / sequencing). |
| `result` | Context key to store this step's output (defaults to the step `name`). |
| `timeout` | `startToClose` and/or `scheduleToClose` for `call` steps. |
| `retry` | `maxAttempts`, `initialInterval`, `maxInterval`, `backoffCoefficient`. |
| `taskQueue` | Route a `call` to a separate worker's task queue (any language). Omit for in-process routing. See [External (polyglot) activity workers](#external-polyglot-activity-workers). |

### Example: switch + parallel fork

```yaml
id: order
tasks:
  - name: approval
    switch:
      - when: "${ .total >= 100 }"
        then:
          - name: review
            call: echo
            with: { status: manual-review }
            result: approval
      - otherwise: true
        then:
          - name: approve
            call: echo
            with: { status: auto-approved }
            result: approval

  - name: fulfillment
    fork:
      branches:
        - name: notify
          tasks:
            - call: echo
              with: { channel: email, template: order-confirmed }
        - name: ledger
          tasks:
            - call: math.add
              with:
                values: ["${ .total }", 0]
    result: fulfillment
```

See [`examples/order.yaml`](examples/order.yaml) and [`examples/start-order.json`](examples/start-order.json) for complete, runnable samples.

---

## Expression language


1

Expressions are written as `${ ... }`. They can be used in `with` arguments, `switch` conditions, `wait` durations, and child-workflow `input`.

**Path access** (jq-like, rooted at the context):

```
${ .sku }                 # context["sku"]
${ .order.customer.id }   # nested map access
${ .items[0].price }      # list index access
${ . }  or  ${ $ }        # the whole context
```

**Operators**

| Category | Supported |
|----------|-----------|
| Comparison | `==`, `!=`, `>`, `>=`, `<`, `<=` (numeric when both sides parse as numbers, else string) |
| Boolean | `&&`, `\|\|`, `!` (`!` binds tighter than comparison: `!a == b` parses as `(!a) == b`, so write `!(a == b)` for a negated equality) |
| Helpers | `exists(<expr>)`, parentheses for grouping |
| Literals | numbers, `'single'` / `"double"` quoted strings, `true`, `false`, `null` |

**Truthiness:** `null`/`false`/`0`/empty string/empty collection are falsy; everything else is truthy.

**String interpolation:** a string containing `${ ... }` among other text is interpolated (`"order-${ .id }"`); a string that is _exactly_ one `${ ... }` returns the raw typed value (number, map, etc.).

---

## Built-in activities

| Activity name | Handler | Behavior |
|---------------|---------|----------|
| `echo` | `EchoActivityHandler` | Returns its input unchanged. Useful for testing / passing data through. |
| `math.add` | `AddActivityHandler` | Sums the numbers in the `values` list; returns `{ "sum": <number> }`. |
| `argo.template.execute` | `KubernetesJobActivityHandler` | Launches a Kubernetes Job from an Argo template's `container`/`script`, polls to completion, returns status. |
| `__status.callback` | `StatusCallbackActivityHandler` | Internal — POSTs the final workflow status to a callback URL. Only registered when `dsl.callback.enabled=true`. |

The leaf activity name (`argo.template.execute`) is configurable via `argo.compat.leaf-activity-name` (see [Configuration](#configuration-reference)); `echo` and `math.add` use fixed names.

---

## Adding your own activities

This is the main extension point. Implement `DslActivityHandler` and annotate it as a Spring `@Component` — it is auto-discovered and routable by name. **No worker or workflow changes needed.**

```kotlin
package com.bluecopa.temporalservice.activity

import org.springframework.stereotype.Component

@Component
class SendEmailHandler : DslActivityHandler {
    override val name = "notifications.email"   // the value you put in `call:`

    override fun handle(input: Map<String, Any?>): Any? {
        val to = input["to"] as String
        val template = input["template"] as String
        // ... do the work, call out to your systems ...
        return mapOf("delivered" to true, "to" to to)
    }
}
```

Then any definition can use it:

```yaml
- name: confirm
  call: notifications.email
  with:
    to: "${ .customer.email }"
    template: order-confirmed
  result: emailResult
```

> Activities run on the worker thread pool — they may block, do I/O, and call external services. The **workflow** code (the interpreter) must stay deterministic; all side effects belong in handlers.

### External (polyglot) activity workers

In-process `DslActivityHandler` beans run on this service's own worker (task queue `dsl-task-queue`). But a `call` does not have to run here at all. Add a `taskQueue` to the step and the activity is dispatched to **whatever worker polls that queue** — an ordinary Temporal activity, registered by a separate worker in **any language** (Go, Python, TypeScript, Java):

```yaml
- name: fetchProfile
  call: FetchProfile          # the external activity *type name*
  taskQueue: profile-worker   # routes to a separate worker; omit for in-process
  with: { userId: "${ .userId }" }
  result: profile
  timeout: { startToClose: 30s }
  retry: { maxAttempts: 3 }
```

- `call` is the **activity type name** the external worker registered.
- `with` is serialized to a single JSON argument — the common Temporal convention. Your activity receives that one object.
- `timeout`, `retry`, and `heartbeat` apply identically to external activities; they flow straight into the `ActivityOptions`.
- The external worker just needs to point at the **same Temporal namespace** and poll the named task queue. It does not import or depend on this service.

**In-process vs external tradeoff.** In-process handlers are the simplest path — no extra deployment, instant routing by name, and they share this JVM. External workers cost an extra deployment and a queue to operate, but let you keep activity code in another language or service, scale and version it independently, and isolate its dependencies and failure domain from the workflow engine. Use in-process for lightweight glue; use a `taskQueue` when the work belongs to a team or runtime that owns its own worker.

When `taskQueue` is omitted, behavior is exactly as before: the `call` is routed in-process by `RoutingDynamicActivity` on `dsl-task-queue`.

---

## REST API reference

The service exposes several controller surfaces. Use the native `/dsl` API for new integrations; the others exist for Argo compatibility.

### Native DSL API (`/dsl`)

| Method & path | Body | Description |
|---------------|------|-------------|
| `POST /dsl/definitions/{id}` | YAML | Register/replace a named definition (persisted to disk). |
| `GET /dsl/definitions/{id}` | — | Fetch a registered definition's YAML. |
| `POST /dsl/workflows/start` | JSON `StartWorkflowRequest` | Start a workflow **async**; returns `{ workflowId, runId }`. |
| `POST /dsl/workflows/run` | JSON `StartWorkflowRequest` | Run a workflow **synchronously**; returns the final result map. |

`StartWorkflowRequest`:

```json
{
  "workflowId": "optional-explicit-id",
  "definitionId": "order",          // reference a registered definition, OR
  "definitionYaml": "id: order\n...",// inline definition (one of the two required)
  "input": { "sku": "sku-123", "quantity": 2, "total": 125 }
}
```

Examples:

```bash
# Register a reusable definition
curl -X POST http://localhost:8082/dsl/definitions/order \
  -H 'content-type: text/plain' \
  --data-binary @examples/order.yaml

# Start it asynchronously with input
curl -X POST http://localhost:8082/dsl/workflows/start \
  -H 'content-type: application/json' \
  -d '{"definitionId":"order","input":{"sku":"sku-1","quantity":1,"total":50}}'

# Or run an inline definition synchronously
curl -X POST http://localhost:8082/dsl/workflows/run \
  -H 'content-type: application/json' \
  -d @examples/start-order.json
```

### Argo-style management APIs

These accept Argo YAML manifests (content type `text/plain`, `application/yaml`, or `application/x-yaml`) and manage executions through Temporal.

| Surface | Base path | Purpose |
|---------|-----------|---------|
| `WorkflowController` | `/workflows` | Submit a `Workflow` manifest, get/list (with `?label=k=v`), terminate, suspend, resume. |
| `WorkflowTemplateController` | `/workflow-templates` | CRUD for `WorkflowTemplate` resources. |
| `CronWorkflowController` | `/cron-workflows` | CRUD + suspend/resume for `CronWorkflow` resources. |
| `ArgoV1Controller` | `/api/v1` | Argo Server–shaped REST API (`/workflows/{namespace}`, `/workflow-templates/{namespace}`, `/cron-workflows/{namespace}`) for drop-in Argo clients. |

Example — submit an Argo workflow manifest:

```bash
curl -X POST http://localhost:8082/workflows \
  -H 'content-type: application/yaml' \
  --data-binary @my-argo-workflow.yaml

# check status
curl http://localhost:8082/workflows/<workflow-name>
```

---

## Error responses

Every error — validation, not-found, malformed payload, transport, or unexpected — is returned as a
single consistent [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) `application/problem+json` document:

```json
{
  "type": "/errors/validation_failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid workflow definition: Workflow definition must contain tasks or do steps.",
  "code": "VALIDATION_FAILED",
  "timestamp": "2026-06-14T20:10:26.606Z",
  "path": "/dsl/workflows/run"
}
```

Branch on the stable `code` field rather than on HTTP status or the human-readable `detail`.

| HTTP | `code` | When |
|------|--------|------|
| 400 | `VALIDATION_FAILED` | Definition/manifest is well-formed but invalid (missing entrypoint, unknown template, no steps). |
| 400 | `MALFORMED_REQUEST` | Body/manifest is not valid JSON/YAML, or a request binding error. |
| 400 | `MISSING_PARAMETER` | A required query parameter is absent. |
| 401 | `UNAUTHORIZED` | API key required but missing/invalid (see [Configuration](#configuration-reference)). |
| 404 | `RESOURCE_NOT_FOUND` | Workflow / template / cron / definition does not exist. |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for the route. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Unsupported `Content-Type`. |
| 422 | `WORKFLOW_EXECUTION_FAILED` | A synchronously-run workflow failed. |
| 409 / 502 / 503 / 504 | `CONFLICT` / `UPSTREAM_TEMPORAL_ERROR` | Mapped from the Temporal frontend gRPC status. |
| 500 | `INTERNAL_ERROR` | Unexpected server error (details are logged, never leaked in the response). |

**Workflow failures (`POST /dsl/workflows/run`)** are unwrapped to the real cause. When a workflow
fails because of a bad definition/expression/duration, the 422 carries the failure type and whether
Temporal considered it retryable:

```json
{
  "status": 422,
  "code": "WORKFLOW_EXECUTION_FAILED",
  "detail": "Unsupported expression: .total >>= 100",
  "failureType": "DslExpression",
  "retryable": false
}
```

Workflow-side failures use stable `ApplicationFailure` types — `DslExpression`, `DslDuration`,
`MathAdd`, `UnknownActivityType`, `KubernetesJobFailed`, `KubernetesJobTimeout` — and the DSL
interpreter is registered with fail-fast semantics, so an invalid definition fails the run cleanly
instead of stalling the task queue.

---

## Argo compatibility

The service can stand in for an Argo Workflows control plane while running everything on Temporal:

- **`Workflow`** manifests are translated by `ArgoDslTranslator` (entrypoint template → DSL steps) and started immediately.
- **`WorkflowTemplate`** manifests are stored and can be referenced via `templateRef` from other steps.
- **`CronWorkflow`** manifests are started with Temporal's native cron scheduling (`setCronSchedule`), and can be suspended/resumed/triggered.
- Argo step groups with multiple parallel steps become DSL `fork` branches; `when:` conditions become `switch` cases; `{{inputs.parameters.x}}` / `{{workflow.parameters.x}}` references are resolved.

Each Argo template that has a `container` or `script` becomes a leaf `call: argo.template.execute`, which runs as a Kubernetes Job.

---

## CNCF Serverless Workflow (experimental import)

The service can import a subset of the [CNCF Serverless Workflow v1.0](https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md)
DSL. A CNCF YAML document is parsed and **translated** into this service's native DSL (mirroring the
Argo path) and then started on Temporal — the interpreter is unchanged. This closes the standardization
gap by letting standard CNCF workflows run on the engine for the common subset.

Submit a CNCF manifest (content type `text/plain`, `application/yaml`, or `application/x-yaml`):

```bash
# start async -> { "workflowId": "...", "runId": "..." }
curl -X POST http://localhost:8082/cncf/workflows \
  -H 'content-type: application/yaml' \
  --data-binary @my-cncf-workflow.yaml

# or run synchronously -> final context map
curl -X POST http://localhost:8082/cncf/workflows/run \
  -H 'content-type: application/yaml' \
  --data-binary @my-cncf-workflow.yaml
```

### Supported constructs

| CNCF construct | Translated to |
|----------------|---------------|
| `call` (function ref) + `with` | `call` activity (optional `taskQueue` passthrough for external workers) |
| `do` (sub-tasks) | nested `then` steps |
| `fork` (`branches`, `compete`) | `fork` |
| `switch` (inline `then` / `otherwise`) | `switch` cases |
| `try` / `catch` | `try` / `catch` (catches all errors) |
| `wait` (durations: seconds/minutes/hours/days/milliseconds) | `wait` |
| `set` | merges evaluated assignments into the context |
| `raise` | non-retryable `DslRaise` failure carrying the error detail/title |
| `run` (workflow/subflow) | `run` child workflow |

### Rejected constructs (clear 400 `VALIDATION_FAILED`)

`for`, `listen`, `emit`, HTTP/gRPC/OpenAPI/AsyncAPI `call` variants, goto-style `switch` `then`
jumps to named tasks, and `wait` `until: <timestamp>`. The error message names the offending
construct so partial portability stays honest.

### Limitations

- **jq expressions.** CNCF mandates jq runtime expressions. This translator passes `${ ... }`
  expressions through unchanged; the built-in `ExpressionEvaluator` handles the common subset
  (path access, comparisons, boolean logic). jq-only features are an accepted limitation — a jq
  engine is a separate follow-up (roadmap item 9).
- **`try`/`catch` error filtering.** CNCF error-type matching on `catch` is not represented; all
  errors are caught.
- The saga `compensate` step is a temporal-service extension and is never emitted from CNCF input.

---

## Kubernetes job execution

`KubernetesJobActivityHandler` (activity `argo.template.execute`) turns a template into a Kubernetes `batch/v1` Job:

- Builds a Job from the template's `container` or `script` spec, merging workflow-level `serviceAccountName`, `volumes`, `tolerations`, and `imagePullSecrets`.
- Resolves Argo expressions in env vars (`{{workflow.uid}}`, `{{workflow.name}}`, `{{inputs.parameters.*}}`, `{{workflow.parameters.*}}`).
- Polls the Job until success/failure or timeout; on failure it tails pod logs into the Temporal failure.
- Cleans up the Job afterward (also honors `ttlSecondsAfterFinished`).

Configure via the `k8s` block — set `in-cluster: true` when running inside the cluster; otherwise it uses your default kubeconfig.

---

## Configuration reference

All settings live in [`src/main/resources/application.yml`](src/main/resources/application.yml) and can be overridden via environment variables / JVM args.

| Key | Default | Description |
|-----|---------|-------------|
| `server.port` | `8082` | HTTP port. |
| `temporal.target` | `127.0.0.1:7233` | Temporal frontend gRPC address. |
| `temporal.namespace` | `default` | Temporal namespace. |
| `temporal.task-queue` | `dsl-task-queue` | Task queue the worker and workflows use. |
| `dsl.api.base-path` | `/dsl` | Base path for the native DSL API. |
| `dsl.engine.workflow-id-prefix` | `dsl` | Prefix for generated workflow ids. |
| `dsl.engine.default-activity-start-to-close` | `30s` | Default activity timeout. |
| `dsl.engine.default-retry.*` | maxAttempts `5`, initialInterval `1s`, backoffCoefficient `2.0` | Default retry policy for steps. |
| `dsl.callback.enabled` | `false` | Enable the completion HTTP callback activity. |
| `dsl.callback.url` / `timeout` | `""` / `5s` | Callback target and timeout. |
| `argo.compat.leaf-activity-name` | `argo.template.execute` | Activity that runs translated Argo leaves. |
| `argo.compat.default-entrypoint` | `main` | Entrypoint used when an Argo spec omits one. |
| `registry.storage-path` | `/tmp/temporal-service/registry` | Where definitions and Argo resources are persisted. |
| `k8s.namespace` | `default` | Namespace for launched Jobs. |
| `k8s.in-cluster` | `false` | `true` when deployed inside Kubernetes. |
| `k8s.job-ttl-seconds` | `300` | Job TTL after completion. |
| `k8s.job-timeout-minutes` | `60` | Max time to wait for a Job. |
| `k8s.job-poll-interval-seconds` | `5` | Job status poll interval. |
| `security.enabled` | `true` | Enable the API-key filter (only enforced when `security.api-key` is non-empty). |
| `security.header-name` | `X-Api-Key` | Header that carries the API key. |
| `security.api-key` | `""` (env `TEMPORAL_SERVICE_API_KEY`) | Required API key. When blank, all requests are allowed. |

---

## Using this service from other applications

There are three integration patterns, from simplest to most advanced.

### 1. Call the REST API (recommended for most apps)

Treat this service as a workflow microservice. Any language with an HTTP client can drive it.

**Define once, run many times** — register a definition, then start it with per-request input:

```bash
# one-time: register
POST /dsl/definitions/onboarding   (body: your YAML)

# per request: start async, get ids back
POST /dsl/workflows/start
{ "definitionId": "onboarding", "input": { "userId": "u-42" } }
# -> { "workflowId": "...", "runId": "..." }
```

**Fire-and-get-result** — for short workflows, `POST /dsl/workflows/run` blocks and returns the final context map directly.

**Inline definitions** — pass `definitionYaml` instead of `definitionId` to run ad-hoc workflows without registering them.

Example client call (TypeScript):

```ts
const res = await fetch("http://temporal-service:8082/dsl/workflows/start", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({
    definitionId: "onboarding",
    input: { userId, plan: "pro" },
  }),
});
const { workflowId, runId } = await res.json();
```

### 2. Get notified when a workflow finishes (callbacks)

Enable the completion callback so your app is told when a workflow ends instead of polling:

```yaml
dsl:
  callback:
    enabled: true
    url: "https://your-app/internal/workflow-callback"
    timeout: 5s
```

Your endpoint receives a POST with `{ workflowId, status, result, error, completedAt }`. (For Argo-submitted workflows the callback URL is taken from this config; the native DSL request can also carry a per-run `callbackUrl`.)

### 3. Add domain activities and embed your logic

When your workflows need to touch your systems (DBs, queues, third-party APIs), add `DslActivityHandler` beans (see [Adding your own activities](#adding-your-own-activities)) to this service or a fork of it. Your business steps then become simple `call:` entries in the DSL. This keeps orchestration declarative while your code stays in plain Kotlin/Java handlers.

### 4. Reuse existing Argo manifests

If you already have Argo `Workflow`/`WorkflowTemplate`/`CronWorkflow` YAML, point your Argo client or `kubectl`-style tooling at the `/api/v1` endpoints. The manifests run on Temporal, with container steps executed as Kubernetes Jobs — no Argo controller required.

> **Note:** API-key auth is available but **disabled by default** — `security.api-key` is empty, so all requests are allowed. Set `security.api-key` (env `TEMPORAL_SERVICE_API_KEY`) to require the `X-Api-Key` header, and still put the service behind your own gateway/mTLS/network policy before exposing it.

---

## Testing

```bash
./gradlew test
```

Tests use `io.temporal:temporal-testing` (an in-memory Temporal test environment) and cover the DSL parser, the expression evaluator, and full workflow execution:

- `dsl/DslParserTest.kt`
- `dsl/ExpressionEvaluatorTest.kt`
- `workflow/DslWorkflowTest.kt`

---

## Project layout

```
src/main/kotlin/com/bluecopa/temporalservice/
├── TemporalServiceApplication.kt    # Spring Boot entrypoint
├── api/                             # REST controllers (DSL + Argo-compat surfaces)
├── workflow/                        # DslWorkflow, interpreter, request/settings models
├── activity/                        # DynamicActivity router + handler beans
├── dsl/                             # DSL model, YAML parser, expression evaluator
├── argo/                            # Argo manifest model, parser, DSL translator, registry
├── registry/                        # File-backed definition registry
└── config/                          # Temporal wiring + @ConfigurationProperties

src/main/resources/application.yml   # All configuration
examples/                            # order.yaml + start-order.json samples
```

---

## License

Licensed under the Apache License, Version 2.0. See [`LICENSE`](LICENSE) for the full text
and [`NOTICE`](NOTICE) for attribution notices.
