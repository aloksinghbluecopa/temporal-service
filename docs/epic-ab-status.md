# Epic A & B implementation status (Argo → Temporal)

Implements the two workstreams from `docs/argo-to-temporal-migration.md`:
**A** = replace Argo Events Sensors with Temporal Signal-based triggering;
**B** = pass large artifacts by reference. Full plan: `~/.claude/plans/misty-bouncing-owl.md`.

## ✅ Done — temporal-service (this repo)
- **A2:** `workflow/TriggerCoordinatorWorkflow.kt`, `workflow/TriggerCoordinatorWorkflowImpl.kt`,
  `activity/TriggerLaunchActivityHandler.kt` (`trigger.launch`), `api/SignalController.kt`
  (`POST /trigger-coordinators/{id}/signal`); edits to `workflow/WorkflowLauncher.kt`
  (`signalWithStart`/`signal`), `config/TemporalConfig.kt` (second worker on `trigger-coordinator-queue`),
  `api/ArgoApiModels.kt` (`SignalRequest`/`SignalResponse`), `application.yml`.
- **A5 (shim half):** `activity/StatusCallbackActivityHandler.kt` additive `workflowType`/`workspaceId` pass-through.
- **B2/B3/B5 (shim half):** `dsl/ArtifactRef.kt` (+`ArtifactRefCodec`), `dsl/DslModel.kt` KDoc,
  `activity/KubernetesJobActivityHandler.kt` (`ARTIFACT_<NAME>_*` env injection + produced-ref capture),
  `activity/ArtifactGcActivityHandler.kt` (`artifact.gc` log-and-record stub — NO blob client),
  `workflow/DslWorkflowImpl.kt` (invokes `artifact.gc` on terminal success).

## ✅ Done — data-plane-api
All in the single `service/temporal-service-client.kt` (per project convention):
Feign `signalCoordinator`, `DelegatingBodyEncoder`, `SignalRequestDto`/`SignalResponseDto`/`ArtifactRefDto`,
`TemporalTriggerGateway` bean, `TemporalCallbackController` NATS re-publish (A5).
Plus `service/workflow-dependency.kt` `triggerWorkflow` branch on `workflow.engine` and `application.yml`
(`workflow.engine: ARGO` default).

## ⛔ TODO — fx-runtime (`~/Desktop/fx/fx-runtime`) — NOT DONE
Deferred by user decision (2026-06-15); writes were also blocked because the repo is outside the
temporal-service working dir. **Epic B is not functionally closed until this lands** — the shim's
`artifact.gc` stub only records intent; the real blob deletion lives here.

- **B2:** NEW `packages/service/cloud/artifact_ref.py` — `@dataclass ArtifactRef`
  (`store/bucket/key/size_bytes/content_type/checksum/ttl_seconds/transient`), `to_dict`/`from_dict`
  (camelCase keys to match Kotlin: `sizeBytes`, `contentType`, `ttlSeconds`…), `from_env(name)` reading
  `ARTIFACT_<NAME>_STORE/_BUCKET/_KEY`.
- **B4:** module helpers `upload_as_artifact`/`download_artifact` delegating to `BlobStoreService`
  (`cloud_host_services.py`); EDIT `apps/fx_jobs/export_job.py` to dual-emit `artifactRef` alongside the
  existing `artifactLocation`/`artifactSize`; EDIT `apps/fx_jobs/robot_job.py` to read `ARTIFACT_*` env +
  best-effort produced ref.
- **B5:** `delete_artifact(blob_store, ref)` → `BlobStoreService.delete_blob_file`, GUARDED to only delete
  `transient`/expired refs (never delete durable exports).

To resume: grant write access to the fx repo (or run a session from there) and implement B2/B4/B5 above.

## Not yet run (implement-before-testing)
No gradle/pytest/build executed and no test files written, per project convention. When ready: coordinator
`TestWorkflowEnvironment` tests, Mockk tests for the `triggerWorkflow` branch + NATS re-publish, pytest for
`ArtifactRef` round-trip + `delete_artifact` guard, and a `ARGO` vs `TEMPORAL_SHIM` parallel-run diff.
