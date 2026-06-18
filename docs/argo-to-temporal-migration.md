# Argo → Temporal Migration: Research & Strategy

**Status:** Research document (plan-mode deliverable — no implementation).
**Author:** Senior technical researcher (data platform).
**Date:** 2026-06-15.
**Scope repos:** `data-plane-api` (Kotlin / Spring Boot 3.3.10 / JVM 21), `fx-runtime` (Python 3.12+, located at `~/Desktop/fx/fx-runtime`), and the `temporal-service` shim in the current working directory.

> **Important framing finding:** This is **not a greenfield migration**. Both `data-plane-api` and `fx-runtime` already contain substantial, production-shaped Temporal code running *alongside* Argo, and the `temporal-service` repo is purpose-built as an **Argo-compatible control plane backed by Temporal**. The migration is partially executed. This document inventories what exists, maps the concepts, and proposes how to finish the cutover.

---

## 1. Current Argo usage in `data-plane-api`

`data-plane-api` is the primary Argo consumer. It uses **three Argo products**:

| Argo product | Evidence | What it's used for |
|---|---|---|
| **Argo Workflows** | `io.argoproj.workflow.*` imports; `WorkflowServiceApi`, `WorkflowTemplateServiceApi`, `CronWorkflowServiceApi`, `ArchivedWorkflowServiceApi` | The core orchestration engine for all data pipelines. |
| **Argo Events** | `SensorServiceApi`, `EventSourceServiceApi`; `IoArgoprojEventsV1alpha1Sensor`, `IoArgoprojEventsV1alpha1EventSource` | Calendar/cron event sources + sensors that resubmit workflows. |
| **Argo (deploy manifests)** | `k8s-dev-setup/argo/argo-v3_4_4.yaml`, `k8s-dev-setup/argo-events/argo-events-v1_7_3.yaml` | Cluster install manifests (Argo Workflows v3.4.4, Argo Events v1.7.3). This is the Argo Workflows/Events install, **not Argo CD**. No Argo CD usage was found. |

### 1.1 The Argo client wrapper

- **`ArgoWorkflowService`** — `src/main/kotlin/com/bluecopa/dp/service/workflow.kt:1404`. This is the single gateway to the Argo API server (`argo.api.url`, default `https://localhost:2746`, configured in `application.yml:221-225`). It instantiates one `io.argoproj.workflow.ApiClient` and wraps:
  - Workflows: `createWorkflow` (`workflow.kt:1505`), `getWorkflow` (`:1454`), `getAll` (`:1438`), `deleteWorkflow` (`:1457`).
  - Archived workflows: `getAllArchivedWorkflows` (`:1444`), `getArchivedWorkflowByUid` (`:1450`).
  - WorkflowTemplates: `createOrUpdateWorkflowTemplate` (`:1537`), `deleteWorkflowTemplate` (`:1637`), `checkTemplateExists` (`:1641`).
  - CronWorkflows: `createCronWorkflow` (`:1478`), `updateCronWorkflow` (`:1490`), `createOrUpdateCronWorkflow` (`:1513`), `deleteCronWorkflow` (`:1624`), `suspendCronWorkflow` (`:1628`).
  - Argo Events: `createEventSource`/`updateEventSource`/`getEventSource` (`:1572-1597`), `createSensor`/`updateSensor`/`getSensor` (`:1599-1622`).
- Auth: it injects a Kubernetes service-account bearer token (`kubeApi.getAdminAccessToken()`, `workflow.kt:1420`) and disables SSL verification — i.e. it talks to the in-cluster Argo Server.
- The Argo Java client is **vendored as a local jar** (`build.gradle.kts`: *"Argo Java client: vendored as a local jar (not on Maven Central; its old repo is dead)"*). This is a maintenance liability and an argument for migration on its own.

### 1.2 Workflow templates (YAML)

~67 Argo manifests live under `src/main/resources/data-pipelines/argo-templates/`. They are parameterized with `${...}` placeholders and rendered at runtime via Apache Commons `StringSubstitutor` (`cron-service.kt:9`). Categories:

| Pipeline area | Representative templates |
|---|---|
| Source → Bronze | `source-to-bronze/default.yaml`, `.../v1`, `.../v2/default-template.yaml`, `default-cron.yaml`, `default-sensor.yaml`, `calendar-event-source.yaml` |
| Bronze → Silver | `bronze-to-silver/{aws,gcp}/default*.yaml` |
| Silver → Gold | `silver-to-gold/{aws,gcp}/default*.yaml` |
| Combined pipeline | `src-bronze-silver-pipeline/default.yaml`, `pipelines/`, `templated-pipeline/` |
| Exports | `exports/{aws,gcp}/*`, `export-excel/`, `export-dataset/` |
| Recon | `recon/{,gcp}/default_workflow.yaml` |
| Drive processing | `drive-processing/{aws,gcp}/default_workflow.yaml` |
| Robot (RPA) | `robot/{,gcp}/{file,generic}/*` |
| Alerts / Notifications | `alerts/{aws,gcp}/*`, `notifications/*` (+ HTML email templates) |
| Cron scheduling | `cron-schedule/default-cron.yaml` |

Each template's leaf step is a **container** running an `fx-runtime` image (e.g. dbt image refs in `application.yml:241-246`; robot base image `workflow.kt:157`). Templates compose via `templateRef` (e.g. `source-to-bronze/default.yaml:72-74` references a template named `${templateName}`, template `sync`). Workflow-level concerns (`serviceAccountName`, `volumes`, `tolerations`, `imagePullSecrets`, `ttlStrategy`, node affinity) are set in the manifest — see `source-to-bronze/default.yaml:13-70`.

### 1.3 Workflow submission, parameterization, monitoring, results

- **Submission** is driven by the per-domain workflow services (`source-to-bronze-workflow.kt`, `bronze-to-silver-workflow.kt`, `silver-to-gold-workflow.kt`, `export-workflow.kt`, `statement-view-workflow.kt`, `robot-workflow.kt`) and orchestrated by `WorkflowRunInfoService.scheduleWorkflowRun` (`workflow.kt:421`) → `argoWorkflowService.createWorkflow(workflow)`.
- **Parameterization:** templates are read from the classpath, placeholders substituted, then deserialized into `IoArgoprojWorkflowV1alpha1Workflow` and POSTed. Argo template-level params use `{{inputs.parameters.*}}` / `{{workflow.parameters.*}}`.
- **Monitoring:** status is read back via `argoWorkflowService.getWorkflow(...)` and mapped through the `ArgoWorkflowStatus` enum (RUNNING/SUCCEEDED/FAILED/ERROR/UNKNOWN — used heavily in `workflow.kt:267-830`). `WorkflowRunInfoService` walks `status.nodes` to derive per-step status and aggregate parent (e.g. src-bronze-silver) status.
- **Results consumption / callbacks:** completion is **push-based over NATS**, not Argo-native. An Argo workflow's final container POSTs a status, which is published to the NATS subject `argo-workflow-status-update-trigger` (`application.yml:287-288`). `WorkflowUpdateSubscriber` (`workflow-update-subscriber.kt`) consumes it, calls `updateWorkflowRunForStatusRequest` (`workflow.kt:448`), persists `WorkflowRunInfo`, publishes a `ProcessEvent` (`workflow.kt:494`), and fires **dependent-workflow triggers** (`DependentWorkflowTriggerService`).
- **Triggering / sensors:** `default-sensor.yaml` defines an Argo Events `Sensor` that listens to a calendar `EventSource` (`calendar-event-source.yaml`) and **resubmits** a workflow (`operation: resubmit`). Dependency-based triggering (one pipeline firing the next) is modeled in app code via `FlowEvent` / `FlowTriggerCondition` / `DependentWorkflowTriggerService`, not purely in Argo.

### 1.4 Cron

- A pluggable `CronProviderService` abstraction exists with **two implementations switched by `cron.provider`**:
  - `ArgoCronProviderService` — `cron-service.kt:69`, `@ConditionalOnProperty(cron.provider=ARGO)`. Renders `cron-schedule/default-cron.yaml` and calls `createOrUpdateCronWorkflow`.
  - `TemporalCronProviderService` — `cron-service.kt:240`, `@ConditionalOnProperty(cron.provider=TEMPORAL)`. Delegates to `TemporalScheduleService`.
- **Current default is `cron.provider: ARGO`** (`application.yml:519-520`).

---

## 2. Existing Temporal footprint in `data-plane-api` (already migrated)

This is the single most important finding for planning: a large slice of orchestration **already runs on Temporal**.

| Temporal asset | File | Purpose |
|---|---|---|
| `TemporalConfig` (client, stubs, worker factory, OpenTracing interceptors, Jackson data converter, context propagators) | `config/temporal-config.kt` | Wires Temporal to `temporal.server` / `temporal.namespace` (`application.yml:318-320`). |
| `TemporalService` (`signal`) | `service/TemporalService.kt` | Generic signal sender to running workflows. |
| `TemporalScheduleService` + `TemporalCronTriggerWorkflow`/`Impl` + `CronTriggerActivity`/`Impl` | `service/temporal-cron.kt` | Full Temporal **Schedules** implementation: create/update/pause/resume/delete/trigger, on task queue `CRON_TASK_QUEUE`. This is a complete native replacement for Argo CronWorkflows. |
| pubsub job listeners | `service/pubsub/` (`BigQueryJobListener*`, `DatabricksJobListener*`, `PubSubSubscription*`, `KafkaSubscriptionActivity`) | Long-poll warehouse jobs as Temporal workflows/activities — replaces what would otherwise be Argo "wait" steps. |
| Solutions deploy/publish/index-sync workflows | `service/solutions/` | `SOLUTION_QUEUE`, `SOLUTION_PUBLISH_QUEUE`. |
| Report fabric | `service/report-fabric.kt` | `REPORT_FABRIC_QUEUE`. |
| Cascade folder delete | `service/drive-folder-delete-workflow.kt` | `CascadeFolderDeleteWorkflow.TASK_QUEUE`. |
| Extraction/pipeline dispatch | task queues `SAMYX_EXTRACT_QUEUE`, `PIPELINE_V2_QUEUE`, `PROCESS_QUEUE` | `application.yml:568` notes *"Long-running dispatch goes via Temporal direct"*. |

**Implication:** the architectural decision and the client plumbing are settled. Remaining work is moving the container-step pipelines (source→bronze→silver→gold, exports, drive, robot, recon, alerts/notifications) off Argo Workflows + Argo Events.

---

## 3. Current Argo usage in `fx-runtime`

`fx-runtime` does **not call the Argo API**. It is the *workload* that Argo schedules — its job entrypoints are the containers in the Argo templates above. (Note: the local `~/Desktop/fx-runtime` dir is only a `docker-compose` stub; the real code is `~/Desktop/fx/fx-runtime`, 4.3 GB.)

### 3.1 Argo-invoked job entrypoints (the "leaf steps")

- `apps/fx_jobs/*.py` — argv-driven CLI scripts that Argo container steps invoke. Examples: `recon_job.py`, `report_runner.py`, `external_dataset_runner.py`, `dataset_publish_job.py`, `bronze_to_silver_dataset_publish_job.py`, `drive_processing_job.py`, `drop_external_tables_job.py`, `input_table_v2_apply_migration_job.py`, `process_action_workflow_job.py`, `holiday_check_job.py`.
  - Pattern (see `recon_job.py:10-41`): read `workflow_id/run_id/user/workspace` (+ optional solution context) from `sys.argv`, bootstrap an `ExecutionContext` from a client token, `asyncio.run(...)` the real coroutine, then reset context.
- `argo_jobs/context.py` — `init_job_context(...)` bootstraps `ExecutionContext` for *any* Argo step pod from `TriggeredBy` / `ProcessTriggerInfo`, fetching process run info from `data-plane-api`. This is the shared "every Argo pod starts like this" shim.
- `packages/robot/jobs/*.py` — robot (RPA) job steps (`download_job`, `upload_job`, `status_job`, `config_update_job`) with their own `data_plane_client.py` and `robot_context.py`.
- `data_plane_client.py` (integrations + robot) — how jobs call back into `data-plane-api`.

**Orchestration today:** sequencing, retry, and timeout for these jobs are owned by the **Argo template**, not the Python. Retries = Argo `retryStrategy`; timeouts = Argo `activeDeadlineSeconds` / pod limits; sequencing = Argo `steps`/`dag`. The Python jobs are mostly stateless single-shot processes.

### 3.2 Existing Temporal footprint in `fx-runtime` (already migrated)

`fx-runtime` already runs `temporalio` (Python SDK) for several flows:

| Temporal asset | Location | Notes |
|---|---|---|
| Recon workflow + worker | `packages/samyx/samyx_datakit/workflow/` (`recon.py`, `activities.py`, `worker.py`, `start.py`, `config.py`) | `ReconWorkflow` with activities `connect_and_profile`, `fetch_and_match`, `detect_state_changes`, `write_back`, `generate_report`; worker on task queue `recon-tasks` (`worker.py`). Uses `temporalio.common.RetryPolicy`. Notably, it **degrades gracefully**: if `temporalio` isn't installed it runs as a plain async function (`recon.py:24-45`) — a deliberate dual-mode (Argo job *or* Temporal). |
| Process workflow | `packages/workflows/processfx/process_run.py` (+ `process_action_workflow.py`, `record_file_processing_workflow.py`) | Process orchestration on Temporal. |
| Templated flow | `packages/workflows/templated_flow/templated_flow_run.py` | Templated pipeline execution on Temporal. |
| Settings | `packages/core/util/settings.py:40-41,187-190` | `process_temporal_connect`, `temporal_namespace`, `temporal_max_concurrent_activities/workflow_tasks/cached_workflows`. Also `app_label_key = "argo-name"` (`:141`) still couples logging/labels to Argo. |
| Tests | `tests/test_smart_recon_temporal.py`, `test_temporal.py`, `test_smart_recon_activities.py`, `test_templated_pipeline.py`, `test_process_tree.py` | Temporal flows are under test. |
| Build | `cloudbuild-worker.yaml`, `Dockerfile_worker`, `flow-worker.py` | A dedicated Temporal worker deployment already exists. |

**Implication:** recon, process, and templated-flow are the most Temporal-ready domains. The bronze/silver/gold ETL and export/drive/robot jobs are still Argo-driven container steps.

---

## 4. The `temporal-service` shim (the migration bridge already built)

The repo in the cwd is the **strategic bridge**: a Spring Boot service that exposes an **Argo-Workflows-compatible API** but executes everything on Temporal. (See `README.md`.)

Key capabilities already implemented:

- **Generic DSL workflow.** One registered Temporal workflow `DslWorkflow`/`DslWorkflowImpl` (`workflow/`) interprets YAML at runtime via `WorkflowInterpreter` — supports `call`, `switch`, `fork` (parallel, with `compete`), `try/catch/compensate` (saga), `wait`, child workflows, `retry`, `timeout`. No redeploy to add workflows.
- **Dynamic activity routing.** `RoutingDynamicActivity` (`activity/`) dispatches `call: <name>` to Spring `DslActivityHandler` beans (`echo`, `math.add`, `argo.template.execute`, `__status.callback`).
- **Argo translation layer** (`argo/`):
  - `ArgoManifestParser` parses `Workflow`/`WorkflowTemplate`/`CronWorkflow` YAML.
  - `ArgoDslTranslator` (`argo/ArgoDslTranslator.kt`) converts the Argo entrypoint template graph into DSL: step groups with >1 step → DSL `fork`; `when:` → DSL `switch`; `templateRef` resolved against `ArgoResourceRegistry`; `{{inputs/workflow.parameters.*}}` resolved; each container/script template → leaf `call: argo.template.execute`.
  - `ArgoResourceRegistry` — file-backed storage of Argo resources (`registry.storage-path`).
- **Kubernetes Job execution.** `KubernetesJobActivityHandler` (activity `argo.template.execute`) turns an Argo template's `container`/`script` into a `batch/v1` Job, merges workflow-level `serviceAccountName`/`volumes`/`tolerations`/`imagePullSecrets`, resolves `{{workflow.*}}`/`{{inputs.*}}` env, polls to completion, tails pod logs on failure, honors `ttlSecondsAfterFinished`. Uses the official Kubernetes Java client + fabric8.
- **Argo-shaped REST surfaces.** `ArgoV1Controller` (`/api/v1/...`) implements a drop-in Argo Server API: create/list/get/delete/suspend/resume/terminate/stop/**resubmit** workflows, full WorkflowTemplate CRUD, full CronWorkflow CRUD + suspend/resume. Plus `WorkflowController` (`/workflows`), `WorkflowTemplateController`, `CronWorkflowController`, and native `DslWorkflowController` (`/dsl`).
- **CronWorkflow → Temporal cron.** Native Temporal cron scheduling (`setCronSchedule`) for `CronWorkflow` manifests (README §"Argo compatibility").
- **SDK versions:** Temporal Java SDK **1.28.0** in `data-plane-api` build, **1.25.0** noted in temporal-service README — align these during cutover.

**This is the centerpiece of a low-risk migration:** point `data-plane-api`'s `ArgoWorkflowService` (or the Argo Java client `basePath`) at `temporal-service` instead of the real Argo Server, and existing manifests keep working while running on Temporal with steps as K8s Jobs.

---

## 5. Concept mapping: Argo → Temporal

| Argo concept | Temporal concept | Where it already exists here |
|---|---|---|
| `Workflow` (manifest) | Workflow Execution | `DslWorkflow` interprets a translated manifest (`temporal-service/workflow/`) |
| `WorkflowTemplate` | Reusable workflow definition / child workflow | `ArgoResourceRegistry` + `templateRef` translation |
| Template `steps` (sequential) | Sequential activities in workflow code | `WorkflowInterpreter` step walk |
| Parallel step group (`- - a` / `- - b`) | `Promise.allOf` / parallel activity stubs | DSL `fork` (`ArgoDslTranslator` step-group → `ForkDef`) |
| `dag` with `dependencies` | Workflow code expressing the DAG | DSL graph (`then`, `fork`); app-level `FlowTriggerCondition` for cross-pipeline DAG |
| Leaf `container` / `script` template | Activity (here: run a K8s Job) | `KubernetesJobActivityHandler` (`argo.template.execute`) |
| `inputs.parameters` / `arguments` | Workflow/activity input args | `ArgoDslTranslator` param resolution |
| `when:` conditional step | `if`/branch in workflow code | DSL `switch` |
| `retryStrategy` | `RetryPolicy` (`maximumAttempts`, `initialInterval`, `backoffCoefficient`, `maximumInterval`, `nonRetryableErrorTypes`) | DSL `retry`; `temporalio.common.RetryPolicy` in fx recon |
| `activeDeadlineSeconds` / step timeout | Activity `startToCloseTimeout` / `scheduleToCloseTimeout`; workflow `WorkflowRunTimeout` | DSL `timeout` |
| `CronWorkflow` | **Temporal Schedule** (preferred) or `setCronSchedule` (legacy) | `TemporalScheduleService` (data-plane-api) + temporal-service cron |
| `CronWorkflow` `suspend` / resume | Schedule pause / unpause | `TemporalScheduleService.pause/resumeSchedule` |
| **Argo Events `EventSource`** | External event ingestion → Temporal client call / Signal | *Not a 1:1.* Replace with: a calendar EventSource → Temporal Schedule; an external event → app code that calls `WorkflowClient.signalWithStart` / `signal` (`TemporalService.signal` exists) |
| **Argo Events `Sensor`** (`operation: resubmit/submit`) | Schedule action **or** Signal handler that starts/continues a workflow | No direct primitive; modeled in app code (see §7 gaps) |
| `ttlStrategy` / archival | Temporal retention + Visibility / Archival | Temporal namespace retention; Advanced Visibility (ES/SQL) |
| `status.nodes` / phase | Workflow & activity event history; `WorkflowExecutionStatus` | Temporal Web UI / `DescribeWorkflowExecution`; `WorkflowRunInfo` persistence stays app-side |
| Resubmit / retry failed | `ResetWorkflowExecution` / start new run | temporal-service `ArgoV1Controller` `resubmit` endpoint |
| Workflow `priority` | Task Queue partitioning / worker pools | Map to dedicated task queues (`*_QUEUE` already in use) |
| Argo artifacts (S3/GCS passing) | **Not built in.** Pass references; store blobs yourself | *Gap* — see §7 |

---

## 6. Migration strategy (phased)

The guiding principle, validated by Atlan's published Argo→Temporal migration (a "Crossover"/bridge architecture allowing incremental swap while keeping production safe) and Pipekit/xgrid comparisons: **run both engines in parallel behind a stable interface, migrate by domain, never big-bang.** This codebase is already structured for exactly that (`cron.provider` switch, dual-mode fx recon, the temporal-service Argo shim).

### Phase 0 — Foundations & guardrails (low risk)
1. **Pin and align Temporal SDK versions** (Java 1.28.0 in data-plane-api vs 1.25.0 in temporal-service) and Python `temporalio`. Stand up one Temporal namespace per environment with retention + Advanced Visibility configured.
2. **Define the task-queue map** (already partly real): keep `CRON_TASK_QUEUE`, `PIPELINE_V2_QUEUE`, `SAMYX_EXTRACT_QUEUE`, `PROCESS_QUEUE`, `REPORT_FABRIC_QUEUE`, `SOLUTION_QUEUE`, `recon-tasks`; add per-domain queues for the pipelines being migrated.
3. **Observability parity first.** Wire Temporal Web UI + the existing OpenTracing interceptors (`temporal-config.kt`) and OpenObserve/OTel exporters so a migrated pipeline is at least as debuggable as Argo's `status.nodes` view before cutting traffic.

### Phase 1 — Cron first (lowest risk, already built)
- Flip `cron.provider` from `ARGO` to `TEMPORAL` (`application.yml:519`) per workspace/environment using a feature flag. `TemporalScheduleService` + `TemporalCronTriggerWorkflow` already implement create/update/pause/resume/delete/trigger.
- This retires Argo **CronWorkflows** and the calendar **EventSource/Sensor** resubmit pattern (the cron-trigger half of Argo Events) without touching the pipeline bodies — the cron trigger just calls the same `CronComponentService.handleCronTrigger`.
- Validate: schedule fidelity (timezone `Etc/UTC`, jitter, startAt/endAt, startDelay are all handled in `buildCronSpecFromConfig`).

### Phase 2 — Bridge the pipeline manifests through `temporal-service` (parallel run)
- Deploy `temporal-service` in-cluster. Point `data-plane-api`'s Argo client `basePath` (`argo.api.url`) at `temporal-service`'s `/api/v1` instead of the Argo Server, behind a per-workspace flag.
- Existing manifests in `argo-templates/` keep being submitted unchanged; `ArgoDslTranslator` + `KubernetesJobActivityHandler` execute the same container steps as K8s Jobs, now durable on Temporal.
- **Run both in parallel:** route a small % of workspaces (or non-prod first) to the shim; keep the real Argo path for the rest. Compare `WorkflowRunInfo` outcomes.
- **Keep the NATS callback contract intact.** The fx jobs already POST status that flows to `WorkflowUpdateSubscriber`; preserve that so downstream `ProcessEvent` + dependent-trigger logic is unchanged. (temporal-service also supports a completion callback — use it to publish to the same NATS subject if you want to drop the in-job POST later.)

### Phase 3 — Native Temporal for high-value / already-started domains
- Promote **recon** (fx `ReconWorkflow` already exists), **process** (`process_run.py`), and **templated flow** (`templated_flow_run.py`) from "Argo container job" to "native Temporal workflow with fine-grained activities." The fx jobs' dual-mode design (`recon.py:24-45`) means the same logic runs either way — switch the entrypoint from the argv CLI to the Temporal worker.
- Migrate the **pubsub warehouse-job waits** fully (already Temporal): ensure all bronze→silver / silver→gold "wait for BigQuery/Databricks job" steps use `BigQueryJobListenerWorkflow` / `DatabricksJobListenerWorkflow` rather than Argo polling.
- Convert **dependent-workflow triggering** (`FlowEvent`/`FlowTriggerCondition`/`DependentWorkflowTriggerService`) from "NATS event → submit next Argo workflow" to "Signal / `signalWithStart` to a parent Temporal workflow," replacing the Argo Events **Sensor** half.

### Phase 4 — Decommission Argo
- Once every domain is either native-Temporal or running through the shim, remove the Argo Workflows + Argo Events installs (`k8s-dev-setup/argo*`), drop the vendored Argo Java client jar and `io.argoproj.*` imports from `data-plane-api`, delete `ArgoCronProviderService`, and retire the `cron.provider=ARGO` branch.
- Decide the long-term shape of `argo-templates/`: either keep them as the input format to the temporal-service shim (least churn) or rewrite leaves as native Temporal activities (more value, more work).

### Parallel-run / cutover controls
- **Feature flag granularity:** per-workspace + per-pipeline-type + environment. Reuse the existing `@ConditionalOnProperty` pattern (`cron.provider`) and add `workflow.engine = ARGO|TEMPORAL_SHIM|TEMPORAL_NATIVE`.
- **Idempotency:** Temporal gives at-least-once activity execution; ensure each fx job/activity is idempotent (Argo retries already required this, so most are — verify writes to warehouses use deterministic keys / `MERGE`).
- **Rollback:** because both paths share the `WorkflowRunInfo` + NATS contract, flipping the flag back to Argo is a config change, not a redeploy of pipelines.

---

## 7. Risks and gaps where Temporal is NOT a clean 1:1 replacement

1. **Argo Events Sensors have no direct Temporal primitive.** Cron-style sources map to Schedules; *external-event* sources/sensors (e.g. webhook/NATS-driven resubmits) must be re-expressed as application code calling `WorkflowClient.signalWithStart`/`signal`. There is no "Sensor CRD" equivalent — this is the largest conceptual gap. (`TemporalService.signal` is the building block.)
2. **Artifacts / data passing.** Argo's artifact repository (S3/GCS passing between steps) has **no built-in Temporal equivalent**. Temporal payloads are size-limited (default ~2 MB/event). Large intermediate data must be passed by reference (object-store keys), with blob lifecycle managed by your code. Audit templates for `artifacts:` usage before migrating.
3. **One Argo step = one pod vs one activity = code on a worker.** Through the shim, leaves remain K8s Jobs (`KubernetesJobActivityHandler`), so resource isolation is preserved. If/when you go *native*, long/heavy fx jobs must either stay as K8s-Job activities or run on appropriately sized worker pools — don't naively turn a 2-hour pod into an in-worker activity without sizing.
4. **Status model differences.** Argo's `status.nodes` per-step view is consumed in `workflow.kt` (e.g. src-bronze-silver aggregation). Temporal exposes history/event-based status, not the same node tree. The shim must synthesize enough status for `WorkflowRunInfoService`, or that aggregation logic moves into the Temporal workflow. Verify the shim returns Argo-shaped `status` for the resubmit/aggregation paths in `workflow.kt:600-830`.
5. **SDK version skew & vendored Argo jar.** The dead/vendored Argo Java client (build.gradle.kts note) is a reason to migrate but also a risk during parallel run — keep it pinned until Phase 4.
6. **`app_label_key = "argo-name"` and Argo-coupled logging** in fx-runtime (`settings.py:141`) and OpenObserve log routing assume Argo pod labels. Logging/trace correlation needs rework so migrated workflows remain searchable.
7. **Retry semantics nuance.** Argo retries restart a fresh pod (fully stateless); Temporal retries an activity but the *workflow* keeps state. Activities that previously relied on "fresh pod = clean slate" must not assume in-process state resets across retries.
8. **Determinism constraint (native phase only).** Temporal workflow code must be deterministic. fx already handles this (`workflow.unsafe.imports_passed_through()` in `recon.py`); any new native workflow must keep side effects in activities.
9. **Cost/throughput at scale.** Temporal centralizes state in its datastore; high-fan-out pipelines create many events. Size the Temporal persistence store and worker fleet; Argo's per-pod model pushed that cost to K8s instead.

---

## 8. Recommendation

**Proceed with the bridge-first, domain-by-domain strategy — the codebase is already built for it.** Concretely:

1. **Phase 1 (cron) immediately** — flip `cron.provider=TEMPORAL` behind a flag; it is fully implemented and the lowest-risk win, and it retires the calendar EventSource/Sensor.
2. **Phase 2 (shim) next** — route Argo manifests through `temporal-service` per-workspace; this gets pipelines onto Temporal with near-zero manifest changes and an instant config rollback.
3. **Phase 3 (native) for recon/process/templated-flow** — these already have Temporal implementations in fx-runtime; finish promoting them and migrate the dependent-trigger (Sensor) logic to Signals.
4. **Phase 4** — decommission Argo, delete the vendored client.

The two genuine engineering investments (not free) are: **(a) replacing Argo Events Sensors with Signal-based triggering**, and **(b) handling large-artifact passing by reference**. Scope those as dedicated workstreams before Phase 3.

---

## 9. Gaps / unknowns needing a POC or deeper investigation

- **Shim fidelity for status aggregation.** Confirm `temporal-service`'s `/api/v1` returns enough Argo-shaped `status.nodes` for `WorkflowRunInfoService`'s src-bronze-silver aggregation, or plan to move that logic. (POC: submit a real `src-bronze-silver-pipeline` manifest through the shim and diff the status against Argo.)
- **Artifact usage census.** Grep all `argo-templates/` for `artifacts:` / artifact repositories to size the data-passing rework. (Not yet inventoried here.)
- **Throughput sizing.** Load-test the shim with production-like fan-out (many parallel source→bronze jobs) to size Temporal persistence and worker pools.
- **SDK alignment.** Confirm a single Temporal Java SDK version across both Spring services and a compatible `temporalio` for Python.
- **Robot (RPA) jobs** — heaviest, most stateful container jobs; validate they tolerate Temporal activity retry semantics.

---

## 10. Key references

**Codebase (authoritative):**
- `data-plane-api/src/main/kotlin/com/bluecopa/dp/service/workflow.kt` (`ArgoWorkflowService` @ `:1404`)
- `data-plane-api/src/main/kotlin/com/bluecopa/dp/service/cron-service.kt` (`ArgoCronProviderService` @ `:69`, `TemporalCronProviderService` @ `:240`)
- `data-plane-api/src/main/kotlin/com/bluecopa/dp/service/temporal-cron.kt` (`TemporalScheduleService`)
- `data-plane-api/src/main/kotlin/com/bluecopa/dp/service/workflow-update-subscriber.kt`
- `data-plane-api/src/main/kotlin/com/bluecopa/dp/config/temporal-config.kt`, `service/TemporalService.kt`, `service/pubsub/`
- `data-plane-api/src/main/resources/application.yml` (`argo:` @ `:221`, `cron.provider` @ `:519`, `temporal:` @ `:318`, NATS subject @ `:287`)
- `data-plane-api/src/main/resources/data-pipelines/argo-templates/**`
- `data-plane-api/k8s-dev-setup/argo/argo-v3_4_4.yaml`, `k8s-dev-setup/argo-events/argo-events-v1_7_3.yaml`
- `fx/fx-runtime/apps/fx_jobs/*.py`, `argo_jobs/context.py`, `packages/robot/jobs/*.py`
- `fx/fx-runtime/packages/samyx/samyx_datakit/workflow/*` (recon Temporal), `packages/workflows/processfx/process_run.py`, `packages/workflows/templated_flow/templated_flow_run.py`
- `temporal-service/` — `README.md`, `argo/ArgoDslTranslator.kt`, `argo/ArgoManifestParser.kt`, `activity/KubernetesJobActivityHandler.kt`, `api/ArgoV1Controller.kt`, `workflow/WorkflowInterpreter.kt`

**External (concept mapping & migration practice):**
- Atlan, "From Argo to Temporal Migration: How We Rebuilt Atlan's Workflow Orchestration" — https://blog.atlan.com/engineering/argo-to-temporal-migration/
- Pipekit, "Temporal vs. Argo Workflows" — https://pipekit.io/blog/temporal-vs-argo-workflows
- xgrid, "Temporal vs Argo Workflows: Architecture & Use Case Guide" — https://www.xgrid.co/resources/temporal-vs-argo-workflows-architecture-comparison/
- xgrid, "Temporal vs Airflow vs Argo" — https://www.xgrid.co/resources/temporal-vs-airflow-vs-argo-workflow-orchestration/
- Temporal docs: Schedules — https://docs.temporal.io/develop/java/schedules ; Retry policy — https://docs.temporal.io/encyclopedia/retry-policies ; Signals — https://docs.temporal.io/encyclopedia/application-message-passing
