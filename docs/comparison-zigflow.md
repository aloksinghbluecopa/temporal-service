# temporal-service vs. zigflow — A Detailed Comparison Report

*Date: 2026-06-15*

## 0. TL;DR

Both projects solve the **same core problem** in nearly the same way: "let people define Temporal workflows as declarative YAML data instead of compiled SDK code, so you don't redeploy workers to change a workflow." They are genuine category peers — a rare, almost head-to-head match.

The decisive differences:

- **zigflow** is a more mature, polished, **standards-based** (CNCF Serverless Workflow) Temporal DSL, distributed as a CLI/worker binary with Homebrew install, a docs site, an MCP server, and ~50 releases. It is a *Temporal-native* tool: you trigger workflows through Temporal's own client/CLI/UI, and your custom logic lives in **separate, polyglot Temporal activity workers**.
- **temporal-service** is a **Spring Boot HTTP service** with its own REST API and a large, unique feature that zigflow does not have at all: **Argo Workflows compatibility** — it parses Argo `Workflow`/`WorkflowTemplate`/`CronWorkflow` manifests, translates them to its DSL, runs them on Temporal, executes leaf steps as **Kubernetes Jobs**, and exposes Argo-Server-shaped APIs. Its custom activities are **in-process Spring beans**, not separate workers.

So: **zigflow is the better general-purpose, standards-compliant Temporal DSL product.** **temporal-service is the better fit if your real goal is an Argo-compatible control plane backed by Temporal, or an HTTP-first workflow microservice that runs Kubernetes Jobs.** They overlap heavily on "YAML workflows on Temporal" but diverge sharply on integration model and on the Argo/Kubernetes story.

---

## 1. Disambiguation — which "zigflow"?

Searching "zigflow" returns several unrelated things. To be honest about the ambiguity:

| Name | What it is | Relevant? |
|------|-----------|-----------|
| **zigflow** (github.com/mrsimonemms/zigflow, zigflow.dev) | A **Temporal DSL** — declarative YAML compiled into Temporal workflows. Written in Go. By Simon Emms (ex-Temporal). | **Yes — this is the comparison target.** |
| **Ziflow** (ziflow.com) | A SaaS **creative-asset proofing / approval-routing** product for marketing/design teams. | No — different category. |
| **Zixflow** (zixflow.com) | A CRM / messaging **automation** SaaS (WhatsApp/SMS/email). | No. |
| **Zigaflow** | A business-management desktop app. | No. |
| **zig.io** | "Organizational workflow automation." | No. |
| **niclaslindstedt/zig** | A small "describe/share/run workflows" CLI. | Tangential, not a Temporal tool. |

There is also a *second GitHub org* `github.com/zigflow/zigflow` ("Define durable workflows in YAML, powered by Temporal") that appears to be the **same project** (mirror/org move; the author's blog and `zigflow.dev` point to the Simon Emms work). I treat `mrsimonemms/zigflow` + `zigflow.dev` as the canonical source.

**Chosen target:** zigflow, the Temporal DSL (Apache-2.0, Go, CNCF Serverless Workflow). This is the only "zigflow" that is genuinely in the same category as temporal-service, and the match is unusually close.

> Honesty note: All zigflow details below come from its README, docs site, and the author's launch blog (see Sources). The source was not read line-by-line, so a few internal specifics (exact validation rules, full task-type coverage of the spec) are summarized from docs and may lag the latest release (v0.13.0, June 2026). Where something is inferred, it is flagged.

---

## 2. What each project is

### temporal-service (this repo)
A **Kotlin / Spring Boot 3.3.10** (Kotlin 1.9.24, JDK 21, Undertow, Temporal Java SDK 1.25.0, official Kubernetes Java client) service that ships **one** generic deterministic workflow, `DslWorkflow`, which interprets a **custom YAML DSL** at runtime. Activities are routed by name through a single `RoutingDynamicActivity` to in-process Spring `DslActivityHandler` beans. Workflows are driven over **HTTP REST** (`/dsl`), and it additionally implements **Argo Workflows compatibility**: it parses Argo manifests, translates them to the DSL, runs them on Temporal, and executes container/script leaves as **Kubernetes Jobs**, while exposing Argo-Server-shaped `/api/v1` endpoints. Source: repo `README.md` and layout under `src/main/kotlin/com/bluecopa/temporalservice/`.

### zigflow
A **Go** CLI + worker (Apache-2.0) that compiles **CNCF Serverless Workflow v1.0** YAML into real Temporal workflows. You run `zigflow run -f workflow.yaml` to register a worker; workflows are then triggered via the Temporal CLI / UI / any Temporal SDK on the configured task queue and workflow type. Custom logic lives in **separate Temporal activity workers** written in any Temporal SDK language. Ships a Homebrew cask, a Helm chart, a docs site (zigflow.dev), JSON-Schema input validation, and a public MCP server for AI-assisted authoring. Source: zigflow.dev, the GitHub README, and the author's blog.

---

## 3. Side-by-side comparison

### 3.1 Workflow definition model

| | temporal-service | zigflow |
|---|---|---|
| Format | YAML/JSON, **custom DSL** | YAML, **CNCF Serverless Workflow v1.0** (`document:` + `do:`) |
| Standard? | No — bespoke schema (`tasks`/`do`, `call`+`with`, `switch`, `fork`, `try/catch/compensate`, `wait`, `run`/`workflow`, `then`, `result`, `timeout`, `retry`) | **Yes** — vendor-neutral CNCF spec; portable across spec-compliant runtimes in principle |
| Expressions | Custom `${ ... }` jq-like language (path access, comparisons, boolean logic, string interpolation, `exists()`) | Serverless Workflow uses runtime expressions (the spec mandates **jq** by default); zigflow follows the spec's `${ }` style |
| Validation | Definition validated; invalid defs fail fast with RFC 7807 errors | **Pre-execution validation + JSON-Schema input validation** with actionable messages (a headline feature) |

**Verdict:** zigflow wins on **standardization and validation**. Building on the CNCF spec means a documented, externally-governed grammar and (in theory) portability. temporal-service's DSL is bespoke; the upside is it's tailored (e.g., explicit `compensate` saga semantics, `result`-named context accumulation) and arguably simpler to reason about, but it's a one-off you must learn and maintain yourself.

### 3.2 Execution & durability

| | temporal-service | zigflow |
|---|---|---|
| Engine | Temporal (Java SDK 1.25.0) | Temporal (Go SDK) |
| Model | One generic interpreted `DslWorkflow`; steps walked deterministically in the workflow, side effects in activities | Compiles YAML into a Temporal workflow; same determinism guarantees |
| Durability | Full Temporal: retries, timeouts, event history, replay | Full Temporal; explicitly implements best-practice defaults: **Continue-As-New, heartbeats, search attributes** |
| Long-running | Inherited from Temporal | Same, plus automatic Continue-As-New for long loops |

**Verdict:** Effectively a tie on the *guarantee* (both are Temporal). zigflow is more explicit about productionizing Temporal idioms (auto Continue-As-New, heartbeats, search attributes). temporal-service documents fail-fast `ApplicationFailure` types and retryable/non-retryable mapping, which is a nice operational touch but does not exceed Temporal's baseline.

### 3.3 Architecture & extensibility (adding custom logic)

This is the **biggest architectural divergence.**

| | temporal-service | zigflow |
|---|---|---|
| Where custom logic lives | **In-process Spring beans** (`DslActivityHandler` `@Component`s) inside the same JVM/worker | **Separate Temporal activity workers**, written in any Temporal SDK language (Go, Python, TS, Java…) |
| Adding an activity | Implement interface + `@Component`, rebuild/redeploy the service | Stand up/extend a Temporal worker on a task queue; reference it via `call: activity` with `name`/`taskQueue` |
| Polyglot? | No — JVM only (Kotlin/Java) | **Yes — language-agnostic** because activities are ordinary Temporal activities |
| Coupling | Tight: orchestration + business logic in one deployable | Loose: orchestration (zigflow) and logic (your workers) are independently deployable |

**Verdict:** Different philosophies. zigflow's model is **more scalable and polyglot** — it embraces Temporal's natural separation of workflow vs. activity workers, so your business code can be in any language and scale independently. temporal-service's in-process bean model is **simpler to start with** (one service, add a Kotlin class) but couples business logic to the orchestrator and locks you to the JVM. For a platform with multiple language runtimes, zigflow's approach is the more standard, looser-coupled design — *unless* your activities are themselves Kubernetes Jobs (see 3.5).

### 3.4 Scheduling / cron

| | temporal-service | zigflow |
|---|---|---|
| Cron | Yes — Temporal native cron (`setCronSchedule`), exposed via Argo `CronWorkflow` semantics (CRUD + suspend/resume/trigger) | Yes — cron expressions and interval triggers in the DSL |
| Surface | Argo-CronWorkflow-shaped REST | DSL-native schedule config |

**Verdict:** Both have it. temporal-service frames scheduling as **Argo CronWorkflow management** (suspend/resume/trigger via REST) — valuable if you're migrating off Argo. zigflow frames it as native DSL schedule config. Tie, with edge to temporal-service *only if* you specifically need Argo CronWorkflow compatibility.

### 3.5 Kubernetes / container execution

| | temporal-service | zigflow |
|---|---|---|
| Deploy on K8s | Yes (it's a Spring Boot app; deploy as you like) | Yes — **Helm chart** provided |
| Run workflow steps **as** K8s Jobs | **Yes — core feature.** `KubernetesJobActivityHandler` turns Argo `container`/`script` templates into `batch/v1` Jobs, polls them, tails logs on failure, cleans up (TTL), merges serviceAccount/volumes/tolerations/imagePullSecrets | **No** — steps are HTTP/gRPC/shell calls or Temporal activities; no built-in "run this container as a K8s Job" primitive |

**Verdict:** **temporal-service wins decisively** on container-step execution. Running each leaf as a Kubernetes Job — with log tailing, TTL cleanup, and Argo-spec field merging — is a substantial capability zigflow simply does not offer. zigflow can *call* HTTP/gRPC services and run shell commands, and it ships a Helm chart for deploying itself, but it has no native batch-Job-per-step model.

### 3.6 Argo / ecosystem compatibility

| | temporal-service | zigflow |
|---|---|---|
| Argo manifests | **Parses & runs** `Workflow`/`WorkflowTemplate`/`CronWorkflow`; translates `{{inputs.parameters.*}}`/`{{workflow.*}}`, step groups → `fork`, `when:` → `switch` | **None** — no Argo support mentioned anywhere |
| Argo API surface | **Yes** — Argo-Server-shaped `/api/v1` for drop-in Argo clients | No |
| Other ecosystem | RFC 7807 problem+json; HTTP callbacks | **CNCF Serverless Workflow** ecosystem; **MCP server** for AI authoring; Homebrew |

**Verdict:** Two different ecosystem bets. **temporal-service is uniquely an Argo control plane backed by Temporal** — if you have existing Argo manifests/clients and want Temporal durability without the Argo controller, this is its killer feature and zigflow can't touch it. **zigflow bets on the open CNCF standard and modern tooling** (MCP, Homebrew, docs site). If you value standards portability and AI-assisted authoring, zigflow wins; if you value Argo drop-in compatibility, temporal-service wins outright.

### 3.7 API surface & integration story

| | temporal-service | zigflow |
|---|---|---|
| Primary driver | **HTTP REST** (`/dsl/definitions`, `/dsl/workflows/start\|run`, `/workflows`, `/workflow-templates`, `/cron-workflows`, `/api/v1`) | **CLI** (`zigflow run -f`) + **Temporal client/CLI/UI/SDKs** to trigger |
| Sync run | Yes — `POST /dsl/workflows/run` blocks and returns the final context map | Via Temporal SDK semantics |
| Definition registry | File-backed registry, register-once/run-many over HTTP | YAML files on disk; worker registers workflow types |
| Best for callers | **Any language with an HTTP client** — no Temporal client needed | Teams already comfortable with the Temporal client/SDKs |

**Verdict:** **temporal-service wins for HTTP-first / language-agnostic *callers*** — you can start workflows from any service with a plain `fetch`, no Temporal SDK on the caller side, and even get a synchronous result. zigflow expects you to **trigger via Temporal's own machinery**, which is more "Temporal-native" but heavier for a simple HTTP integration. (Note the symmetry: zigflow is polyglot on the *activity* side; temporal-service is polyglot on the *caller* side.)

### 3.8 Observability, error handling, retries

| | temporal-service | zigflow |
|---|---|---|
| Observability | Temporal UI/history + RFC 7807 errors + optional completion HTTP callbacks | Temporal UI + optional anonymized telemetry (opt-out via `DISABLE_TELEMETRY`) |
| Errors | **RFC 7807 problem+json** with stable `code`s; workflow failures unwrapped with `failureType`/`retryable` | Pre-execution validation messages; runtime errors via Temporal + `try/catch` |
| Retries | Per-step `retry` (maxAttempts/intervals/backoff) + service-wide defaults | DSL retry/try-catch on Temporal |

**Verdict:** temporal-service has a **more developed HTTP error contract** (RFC 7807, stable codes, callbacks) — meaningful for a service consumed over REST. zigflow leans on Temporal's UI/observability and emphasizes **catching errors before execution**. For API consumers, temporal-service's error model is the stronger story; for in-Temporal debugging, both rely on the same Temporal tooling.

### 3.9 Deployment & operational complexity

| | temporal-service | zigflow |
|---|---|---|
| Footprint | Spring Boot JVM service (Undertow) + Temporal; embeds the worker in the same JVM | Go binary (small), runs as a Temporal worker; Helm chart for K8s |
| K8s needs | Needs cluster access (kubeconfig/in-cluster SA) **for the Argo/Job features** | Helm chart deploys the worker; needs Temporal |
| Install | Build/run jar (`./gradlew bootJar`) | **`brew install`**, Docker, or Helm |

**Verdict:** zigflow is **operationally lighter** (small Go binary, brew/Helm install, no JVM). temporal-service is a heavier JVM deployment and, if you use the Argo/Job path, also needs Kubernetes RBAC. For a minimal footprint, zigflow wins. For a team already standardized on Spring Boot/JVM and Kubernetes, temporal-service fits the existing operational model.

### 3.10 Maturity, community, docs, support

| | temporal-service | zigflow |
|---|---|---|
| Stars / forks | n/a (internal repo) | ~163 stars, ~20 forks |
| Releases | n/a | **~50+ releases**, latest **v0.13.0 (June 2026)**, "active development" |
| Docs | Strong single README | **Dedicated docs site** (zigflow.dev), blog, installation guides |
| Provenance | Internal Bluecopa project | Created by **Simon Emms (ex-Temporal)**; built on CNCF spec |
| Community | Internal | Public, Apache-2.0, sponsorable |

**Verdict:** **zigflow wins clearly on maturity-as-a-product** — public, versioned releases, a docs site, an installer, external authorship with Temporal pedigree, and a standards backing. temporal-service is an internal project (no public community, license "to be added") — but it is *not* less capable; in fact it has more features in the Argo/K8s area. "Maturity" here means *productization and community*, where zigflow leads, not *capability*, where it's mixed.

### 3.11 Licensing & cost

| | temporal-service | zigflow |
|---|---|---|
| License | **Unspecified** ("Internal project — add your license here") | **Apache-2.0** (permissive, commercial-friendly) |
| Cost | Internal build/run cost; Temporal infra | Free OSS; Temporal infra; optional sponsorship |

**Verdict:** zigflow's Apache-2.0 is clear and commercial-friendly. temporal-service has **no license yet**, which is a governance gap if it's ever shared externally. Both incur Temporal infrastructure cost. Edge to zigflow for licensing clarity.

### 3.12 Security / auth

| | temporal-service | zigflow |
|---|---|---|
| Built-in auth | **Optional API-key filter** (`X-Api-Key`, off by default; enable via `TEMPORAL_SERVICE_API_KEY`) | None documented (it's CLI/worker-driven; auth is Temporal's mTLS/namespace auth) |
| Posture | "Put behind your own gateway/mTLS"; in-process secrets in handlers | Relies on Temporal cluster auth and your network |

**Verdict:** temporal-service has a (basic) **HTTP auth story** because it exposes a public API — appropriate, though it explicitly says to add a gateway/mTLS. zigflow has no HTTP surface to secure; it inherits Temporal's auth model. Not directly comparable; temporal-service needs auth because of *what it is* (an HTTP service).

---

## 4. Best-fit use cases / target users

**Choose zigflow if:**
- You want a **standards-based** (CNCF Serverless Workflow) Temporal DSL with a real product around it (docs, releases, brew/Helm).
- Your business logic should live in **separate, polyglot Temporal activity workers** (Python/Go/TS/Java) that scale independently.
- You're a team **already on Temporal** wanting a declarative on-ramp for non-engineers or rapid prototyping.
- You value pre-execution + JSON-Schema validation and AI-assisted authoring (MCP).
- You want a lightweight Go binary, not a JVM.

**Choose temporal-service if:**
- You need **Argo Workflows compatibility** — run existing Argo `Workflow`/`WorkflowTemplate`/`CronWorkflow` manifests on Temporal, with Argo-shaped APIs, *without* the Argo controller. **This is the standout reason; zigflow cannot do it.**
- You need workflow steps to **run as Kubernetes Jobs** (container/script leaves) with log tailing and TTL cleanup.
- You want **HTTP-first** integration: any service starts/runs workflows via REST (sync or async), no Temporal client on the caller, RFC 7807 errors, completion callbacks.
- You're standardized on **Spring Boot / Kotlin / JVM** and want orchestration + activities in one familiar deployable.

---

## 5. Verdict — where each genuinely wins

These two are **real competitors** in the "YAML workflows on Temporal" core, not different categories — the overlap is striking (same elevator pitch, same problem, same engine). But each dominates clear dimensions:

**zigflow is better at:**
1. **Standardization** — built on the CNCF Serverless Workflow spec vs. a bespoke DSL. (decisive)
2. **Polyglot extensibility / decoupling** — activities are independent Temporal workers in any language vs. in-JVM Spring beans. (decisive for multi-language platforms)
3. **Productization & maturity** — public Apache-2.0 project, ~50 releases, docs site, Homebrew/Helm, ex-Temporal author, MCP server. (decisive)
4. **Operational lightness & licensing clarity** — small Go binary; clear Apache-2.0. (clear)

**temporal-service is better at:**
1. **Argo Workflows compatibility** — parsing/translating/serving Argo manifests on Temporal. **zigflow has nothing here.** (decisive, and unique)
2. **Kubernetes Job execution of steps** — container/script leaves as `batch/v1` Jobs with log tailing + cleanup. (decisive)
3. **HTTP-first integration for callers** — REST start/run (incl. synchronous), no Temporal SDK needed by callers, RFC 7807 error contract, completion callbacks. (clear)
4. **Fit with a Spring/Kotlin/JVM + Kubernetes shop** and a built-in API-key auth option. (situational)

**Bottom line:** If the goal is "the best general-purpose, standards-compliant, well-maintained Temporal DSL," **zigflow is the stronger, more mature product and the safer external bet.** If the goal is specifically **"an Argo-compatible control plane backed by Temporal that runs steps as Kubernetes Jobs and is driven over HTTP,"** **temporal-service is purpose-built for that and zigflow does not compete at all.** The honest framing: they share a DSL/Temporal core, but temporal-service has effectively pivoted that core into an **Argo+Kubernetes+REST** product, while zigflow doubled down on being a **clean, standards-based, polyglot Temporal DSL**.

---

## 6. Gaps, unknowns, and honesty caveats

- **zigflow source not read line-by-line.** Task-type coverage, exact validation rules, and how faithfully it implements the full CNCF spec come from docs/README/blog, not code. Some specifics (e.g., whether `try/catch` and `switch` map exactly to spec constructs like `try`/`switch` in Serverless Workflow 1.0, or to `Do`/`Fork`/`For`/`Listen`/`Raise`) should be confirmed against the spec and zigflow's code before relying on portability claims.
- **Two zigflow GitHub locations** (`mrsimonemms/zigflow` and `zigflow/zigflow`) appear to be the same project; their history was not fully reconciled. Stars/release counts cited are as reported by search and may be slightly stale (figures around v0.13.0 / June 2026).
- **temporal-service is internal** — no public stars/community/license, so "maturity" comparisons are inherently apples-to-oranges on the community axis.
- **Performance/scale**: neither side has published benchmarks here; both inherit Temporal's scaling characteristics, but temporal-service's in-JVM activity model and Kubernetes-Job-per-step model have different scaling/throughput profiles than zigflow's separate-worker model. A POC would be needed to compare under load.
- **CNCF spec fidelity vs. temporal-service DSL semantics** (e.g., explicit saga `compensate`) were not cross-walked; if standards portability matters, that comparison needs deeper investigation.

---

## 7. Sources

- temporal-service: `README.md` and source under `src/main/kotlin/com/bluecopa/temporalservice/`
- zigflow GitHub (canonical): https://github.com/mrsimonemms/zigflow
- zigflow GitHub (org mirror): https://github.com/zigflow/zigflow
- zigflow releases: https://github.com/mrsimonemms/zigflow/releases
- zigflow docs — Introduction: https://zigflow.dev/docs/intro/
- zigflow docs — Understanding the DSL: https://zigflow.dev/docs/dsl/intro/
- zigflow homepage: https://zigflow.dev/
- Launch blog — "Zigflow: The Missing Temporal DSL" (Simon Emms): https://simonemms.com/blog/2026/02/02/zigflow-the-missing-temporal-dsl
- CNCF Serverless Workflow specification: https://serverlessworkflow.io/ and https://github.com/serverlessworkflow/specification/blob/main/dsl.md
- Serverless Workflow 1.0 release notes: https://serverlessworkflow.io/blog/releases/release-100/
- Temporal: https://temporal.io/
- (Disambiguation — NOT the target) Ziflow: https://www.ziflow.com/ ; Zixflow: https://zixflow.com/automation ; niclaslindstedt/zig: https://github.com/niclaslindstedt/zig
