# Remediation Roadmap — closing the gaps vs zigflow

*Derived from [`comparison-zigflow.md`](comparison-zigflow.md). Every recommendation is additive and backward-compatible: the bespoke DSL, the Argo path, in-process `DslActivityHandler` beans, the REST API, and the RFC-7807 contract all keep working.*

## Prioritized roadmap (ranked)

| # | Dimension | Change | Effort | Risk | Back-compat | Value |
|---|-----------|--------|--------|------|-------------|-------|
| 1 | Op./licensing | `LICENSE` (Apache-2.0) + NOTICE | S | Low | Yes | High (governance) |
| 2 | Scalability | Heartbeats + `heartbeatTimeout` + deterministic Job name in `KubernetesJobActivityHandler` | S | Low | Yes | **High (real defect)** |
| 3 | Scalability | Custom Search Attributes replace in-memory label/suspend maps; visibility queries + pagination in list | M | Med | Yes (flag + fallback) | **High (real defect; data-plane needs first)** |
| 4 | Productization | JSON-Schema input validation on submit | M | Low | Yes | High |
| 5 | Polyglot | `call` with optional `taskQueue` → external untyped activity stub | M | Low | Yes | **High (real capability gap)** |
| 6 | Standardization | CNCF Serverless Workflow → our-DSL translator (mirror Argo path) | L | Med | Yes (additive) | Med-High |
| 7 | Productization | Helm chart + structured docs + SemVer | M | Low | Yes | Med |
| 8 | Scalability | Continue-As-New — only when `for`/`listen` loops land | M | Med | Yes | Low now |
| 9 | Standardization | jq engine option (jackson-jq) behind a flag | M | Med | Yes (opt-in) | Med |
| 10 | Op. lightness | Distroless/slim container + CDS | S | Low | Yes | Med |
| 11 | Op. lightness | GraalVM native-image | L | **High** | Risky | Low (skip/spike) |
| 12 | Productization | MCP server for AI authoring | M | Low | Yes | Low (defer) |

## Phased plan

**Phase 1 — quick wins + two real defect fixes (do first):** 1 LICENSE · 2 K8s heartbeat + idempotent Job naming · 3 Search Attributes + visibility listing (flag, in-memory fallback — *data-plane-api needs this first*) · 4 JSON-Schema input validation.

**Phase 2 — capability gaps:** 5 polyglot external activities (`call` + `taskQueue`) · 6 CNCF→DSL translator (common subset, reject unsupported with clear RFC-7807 errors) · 9 jq engine behind a flag.

**Phase 3 — productization + advanced CNCF:** 7 Helm + docs + SemVer · 10 distroless/CDS · 6-phase-2 `for`/`listen` then 8 Continue-As-New · 12 MCP last.

**Skip / spike-only:** 11 GraalVM native-image (Temporal Java SDK is reflection/proxy-heavy; no confirmed GraalVM metadata — spike before any commitment); Homebrew formula (not appropriate for a JVM server).

## Design notes per item

- **1 LICENSE** — Apache-2.0 (matches zigflow + CNCF spec + Temporal SDK; explicit patent grant; no copyleft conflict with the dep stack). Add `LICENSE`, `NOTICE`, replace README license section. *Confirm Bluecopa IP/legal stance.*
- **2 K8s heartbeat/idempotency** — `Activity.getExecutionContext().heartbeat(...)` in the watch loop; set `heartbeatTimeout` (new optional `TimeoutDef.heartbeat`, default for the leaf activity ~2× poll interval); deterministic `buildJobName` from `workflowId`+`templateName`(+step name for multi-invocation) instead of `UUID.randomUUID()`; treat `createNamespacedJob` 409 as "adopt existing Job and resume watch". Add `k8s.job-heartbeat-interval-seconds`.
- **3 Search Attributes** — register a `KeywordList` attr `DslLabels` (encode `"key=value"`), plus `DslDefinitionId` (Keyword) and `DslSuspended` (Bool); set via `WorkflowOptions.setTypedSearchAttributes` in `WorkflowLauncher`; rewrite `WorkflowController.list`/`ArgoV1` list to use visibility `listExecutions(query)` with pagination instead of pulling all open+closed; read labels from `DescribeWorkflowExecution`. Gate behind `dsl.visibility.search-attributes-enabled` with the in-memory map as fallback for one release. *Requires Advanced Visibility on the target cluster; `start-dev` needs attribute registration.* Prefer Temporal **Schedules** (native pause/unpause) over `DslSuspended` for cron suspend as a follow-up.
- **4 JSON-Schema validation** — `com.networknt:json-schema-validator` (Draft 2020-12, Jackson, Apache-2.0). Add optional `DslDefinition.inputSchema`; new `InputSchemaValidator`; validate `request.input` at the submit boundary in `DslWorkflowController` → `DslValidationException` → existing 400 `VALIDATION_FAILED`. Only runs when `inputSchema` present.
- **5 Polyglot `call` + `taskQueue`** — add optional `DslStep.taskQueue`; in `WorkflowInterpreter.executeActivity` add `step.taskQueue?.let { setTaskQueue(it) }`; untyped stub routes to external workers' queue. No change to `RoutingDynamicActivity`; omitted `taskQueue` = today's in-process behavior. Keep `with` (named map) as the arg shape; defer positional `args`.
- **6 CNCF translator** — new `cncf/` package mirroring `argo/`; `POST /cncf/workflows`. Map `call`/`do`/`fork`/`switch`/`try`/`wait`/`run`/`set`/`raise` to our constructs; **reject** `for`/`listen`/`emit` and HTTP/gRPC `call` variants with clear RFC-7807 errors (honest partial portability). Keep saga `compensate` as a documented temporal-service extension. *`switch` is goto-style in CNCF (`then: <task-name>`) vs our inline `then:[steps]` — needs a flattening design spike.*
- **9 jq** — `net.thisptr:jackson-jq` behind `dsl.expressions.engine: builtin|jq`; default stays `builtin`. **Never** make jq the global default — that changes semantics for every existing definition (the single biggest back-compat risk).
- **7 Helm/docs/releases** — `deploy/helm/temporal-service/` (Deployment, Service, ConfigMap, Secret for API key, RBAC for `batch/jobs`,`pods`,`pods/log`,`configmaps`,`secrets`); SemVer + CHANGELOG; lightweight docs only if going external.
- **10/11 lightness** — distroless/`temurin:21-jre` + App CDS + container-aware JVM flags (S, do). Native-image: not worth it for a long-running server + reflection-heavy Temporal SDK (spike only).
- **12 MCP** — defer until JSON-Schema (4) + CNCF grammar (6) stabilize; then a thin server exposing `validate_definition`/`list_activities`/`get_schema`.

## Honesty caveats (verify before committing)
- Apache-2.0 needs Bluecopa legal sign-off.
- Search Attributes assume Advanced Visibility (Elasticsearch / modern SQL visibility) + custom-attribute registration — confirm on the data-plane-api Temporal deployment.
- Temporal Java SDK + GraalVM metadata is unconfirmed — treat native-image as unproven.
- Full CNCF portability is aspirational even for zigflow (its docs say it diverges); deliver "CNCF import for the common subset."

*Sources: CNCF Serverless Workflow spec (`serverlessworkflow.io`, `specification/dsl.md`), zigflow docs (`zigflow.dev/docs/dsl/tasks/call`), Temporal Search Attributes/visibility docs, networknt json-schema-validator, jackson-jq, Spring Boot GraalVM docs.*
