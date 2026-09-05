# Play Akka CQRS

[English](README.md) | [中文](README-zh.md)

Reactive MES kernel for semiconductor wafer fabs. Event Sourcing replaces giant transactions, Saga replaces stored procedures, stream-batch unified DAG engine orchestrates cross-system workflows.

| Milestone | Status |
|---|---|
| M1 — Saga Distributed Transaction Coordinator (v3 engine) | Production quality |
| M2 — Stream-Batch Multi-Level DAG Execution Engine | Production quality |
| M2.5+ — ChainDsl Batch-Chain Component Engine | Production quality |
| M3 — Manufacturing Cybernetics CIMs iPaaS | Building: Lot Context Saga + M3.5 Self-Healing Demo ← Production quality |
| M4 — Instant Feedback Loop + Adaptive DAG Engine | Exploring |

## Quick Start

> Ensure these local ports are free: **2551** (Akka remoting), **27017** (MongoDB journal), **3308** (MySQL read-side).

### 1. Launch Infrastructure

```bash
docker-compose up -d   # MongoDB + MySQL
```

### 2. Run the App

```bash
sbt run                # http://localhost:9000
```

> **Apple Silicon**: `sbt -Djna.nosys=true clean run`

### 3. Build Docker Image

```bash
sbt docker:publishLocal     # build image to local Docker daemon
docker run -p 9000:9000 minimal-cqrs:latest
```

Push to a registry:

```bash
sbt -Ddocker.username=<user> -Ddocker.registry=<registry> docker:publish
```

## Live Demos

| Page | What it shows |
|---|---|
| `/` · `/zh` | Homepage — CQRS/ES banking demo (deposit / withdraw / transfer through a TCC saga) |
| `/saga` | Saga showcase — trigger scenarios, inject faults mid-flight, manual-fix + resume suspended transactions, live WebSocket event stream |
| `/fab-demo/m35` | **M3.5 Self-Healing Fab demo** — photolithography → CD-SEM metrology → OCAP rework on event-sourced work orders; scripted faults *and* actor crashes mid-pipeline; watch RECOVERING → resumed stages → AllCompleted |
| `/architecture` `/projection` `/dag` `/m1` `/m2` `/m3` | Milestone deep-dive pages (English at `/en/m1`, `/en/m2`, `/en/m3`) |

## Project Structure

```
├── common-core/       Shared: Akka persistence, serialization, MongoDB/MySQL adapters
├── saga-core/         M1: saga_v3 engine — SagaDefinition/SagaRunner/SagaRegistry DSL,
│                      sharded coordinator + per-step×phase StepExecutors, journal-first
│                      manual fix, generation guards, backward recovery
├── dag-engine-core/   M2.5+: Akka-free batch-chain components — SubBatchPipeline,
│                      SubBatchProcessor, classifier / reconfirm / router / scheduler
├── fab-simulation/    M3: equipment adapter & simulator protocol (ActorEquipmentAdapter)
├── app/
│   ├── net/imadz/
│   │   ├── domain/          Pure domain: Entity, ValueObject, DomainEvent, Invariant
│   │   ├── application/     Use cases: Aggregates (CreditBalance, Lot, Wafer, WorkOrder),
│   │   │                    Projections, FabSaga participants, M3.5 chain execution,
│   │   │                    net.imadz.m25 (ChainDsl, ChainTemplates, pipeline stages)
│   │   └── infrastructure/  Adapters: Persistence, Bootstrap, DI (Guice)
│   ├── protobuf/            .proto files (ScalaPB) — saga_v3 journal schema
│   └── views/               Play Twirl templates (homepage, demos, docs)
├── conf/               application.conf, persistence.conf, cluster.conf, ocap-rules.conf
├── docs/               Deep-dive guides (SAGA, ChainDsl) + legacy DDD guide
├── knowledge_base/     Architecture & methodology docs
└── test/               Unit + integration tests
```

**Dependency rule**: inner layers NEVER import outer layers. `dag-engine-core` depends only on `scala.concurrent` — no Akka.

## Architecture

Onion Architecture + DDD + CQRS/ES:

```
domain/          ← Pure domain logic, zero external deps, FP only
application/     ← Use-case orchestration, Aggregate, Saga, Projection
infrastructure/  ← Framework adapters (Akka, Play, Guice, MongoDB, MySQL)
```

- **Write side**: Akka EventSourcedBehavior → MongoDB journal (Protobuf / Java serialization per binding)
- **Read side**: Akka Projection → MySQL (ScalikeJDBC), continuously updated from events
- **Saga**: TCC pattern (Try-Confirm-Cancel) → backward recovery with selective compensation, journal-first manual fix

Read the full architecture reference: [`knowledge_base/architecture/onion-cqrs-reference.md`](knowledge_base/architecture/onion-cqrs-reference.md)

## M1 — Saga Distributed Transaction Coordinator (v3 engine)

TCC transactions on Akka Persistence, driven by the **saga_v3 DSL**: define a chain as a `SagaDefinition` (steps, step groups, participants with prepare/commit/compensate), register it in the `SagaRegistry`, and start it through `SagaRunner` with typed args codecs. One sharded `SagaTransactionCoordinator` owns the transaction state machine; each step×phase runs in its own `StepExecutor` with retries, timeouts, circuit breaking, and business-error classification.

Design invariants that make recovery safe:

- **Journal-first manual fix** — a step suspended after failed compensation is repaired with a journaled `StepManuallyFixed` event; on entity recovery the coordinator re-derives step outcomes from the journal instead of re-asking dead executors (no more phantom "Created" placeholders).
- **Deterministic re-attach** — transaction IDs are UUIDv3-derived, so a restarted transaction idempotently re-attaches to its steps.
- **Generation guards** — every in-flight operation carries an attempt number; stale responses from superseded attempts are dropped.
- **Participants never enter the journal** — the journal records definition name/version/args; participants are re-materialized from the registry, and structural drift suspends the transaction rather than guessing.

The same engine instance drives both the banking demo (`MoneyTransferSagaDefinition`) and the Fab lot split/merge/wafer-transfer sagas — wired once via `initSagaEngine[AppSagaContext]`.

Read the details: [Saga Engine Guide](docs/SAGA_GUIDE.md) / [中文](docs/SAGA_GUIDE-zh.md) · [`knowledge_base/architecture/saga.md`](knowledge_base/architecture/saga.md)

## M2 — Multi-Level DAG Execution Engine

6 standard components (Splitter, Merger, Classifier, Processor, Buffer, Router) assembled into pipelines. `ChainExecutionActor` orchestrates multi-level DAG execution. Sub-batches flow through defined pipelines; a global `FailureItemRouter` handles rejected items.

## M2.5+ — ChainDsl Batch-Chain Component Engine

One Akka-free component library (`dag-engine-core`) drives a batch of business items through an external-system interaction cycle — fileGen → upload → waitAck → poll → parse → classify — then sorts every item three ways (success / failure / suspicious): suspicious items are reconfirmed against an authoritative source, failures flow through a policy-driven router (`RetrySameArea` / `RouteToArea` / `Scrap` / `ManualIntervention`), and windowed batching respects physical constraints (FOUP carrier capacity).

`ChainDsl.define` declares a chain as ~20 lines of business parameters; `ChainTemplates` ships recharge / purchase / equipment-area presets. The Fab port (`FabPipelineExecutionActor` + `FabPipelineProcessor`) hardens the pattern with generation-token crash recovery and callback guards.

Read the details: [ChainDsl Guide](docs/CHAINDSL_GUIDE.md) / [中文](docs/CHAINDSL_GUIDE-zh.md)

## M3 — Manufacturing Cybernetics CIMs iPaaS (building)

Decision layer on top of M2.5+: POR Repository → Dynamic Flow Assembler → Saga/LotContext distributed transaction → ChainDSL injection. MU Account Matrix for multi-dimensional accountability. Fact-driven closed loop — zero modification to the M2.5+ engine.

**Lot Context Saga (production quality):** event-sourced `LotEntity` + `WaferEntity` aggregates with TCC saga participants (`SourceLotParticipant`, `TargetLotParticipant`, `WaferTransferParticipant`). Lot split / merge / wafer transfer as distributed transactions with FOUP-capacity invariants and backward recovery — same `InvariantRule` + `CommandHelper` + `SagaParticipant` pattern as M1 CreditBalance/MoneyTransfer.

**M3.5 Self-Healing Demo (live at `/fab-demo/m35`):** a 5-wafer photo → CD-SEM measurement → OCAP flow runs on event-sourced work orders (`FabPipelineExecutionActor`). Fault injection is scripted mid-pipeline — including full actor crashes: the sharded entity stops, restarts under a fresh generation, resumes from its journaled stage cursor (skipping completed phases, re-running the interrupted one), routes the failure through config-driven OCAP rules (rework / scrap / hold sub-flows with their own saga-managed child lots), and reaches `AllCompleted`. The page renders the fab floor with camera-direction animation over a WebSocket event stream.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Scala 2.13.14 |
| Web Server | Play Framework 2.8.18 |
| Actor Model | Akka 2.6.20 (Cluster, Sharding, Persistence) |
| Event Journal | MongoDB (Akka Persistence Mongo plugin) |
| Read Model | MySQL 8.0 (ScalikeJDBC + Akka Projection) |
| Serialization | Protobuf (ScalaPB) |
| DI | Guice |
| Build | sbt + sbt-native-packager (Docker) |

## Further Reading

- [Saga Engine Guide (v3 DSL)](docs/SAGA_GUIDE.md) / [中文](docs/SAGA_GUIDE-zh.md) — TCC saga engine: DSL, resilience, ops, acceptance criteria
- [ChainDsl Guide (M2.5+)](docs/CHAINDSL_GUIDE.md) / [中文](docs/CHAINDSL_GUIDE-zh.md) — batch-chain component engine + declarative DSL: pipelines, three-way classification, failure routing, batching, recovery
- [Architecture Best Practices](knowledge_base/methodology/architecture-best-practices.md)
- [Agent Parallel Development](knowledge_base/methodology/agent-parallel-dev.md)
- [Harness Engineering](knowledge_base/methodology/harness-engineering.md)
