# Play Akka CQRS

[English](README.md) | [中文](README-zh.md)

Reactive MES kernel for semiconductor wafer fabs. Event Sourcing replaces giant transactions, Saga replaces stored procedures, stream-batch unified DAG engine orchestrates cross-system workflows.

| Milestone | Status |
|---|---|
| M1 — Saga Distributed Transaction Coordinator | Production quality |
| M2 — Stream-Batch Multi-Level DAG Execution Engine | Production quality |
| M3 — Manufacturing Cybernetics CIMs iPaaS | Exploring |
| M4 — Instant Feedback Loop + Adaptive DAG Engine | Exploring |

See the project homepage: `/` (EN) and `/zh` (Chinese).

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

## Project Structure

```
├── common-core/       Shared: Akka persistence, serialization, MongoDB/MySQL adapters
├── saga-core/         M1: Event-sourced Saga coordinator, step executor, backward recovery
├── app/
│   ├── net/imadz/
│   │   ├── domain/          Pure domain: Entity, ValueObject, DomainEvent, Invariant
│   │   ├── application/     Use cases: Aggregate, ApplicationService, Projection, Saga
│   │   └── infrastructure/  Adapters: Persistence, Bootstrap, DI (Guice)
│   ├── protobuf/            .proto files (ScalaPB)
│   └── views/               Play Twirl templates (homepage, demos, docs)
├── conf/               application.conf, persistence.conf, cluster.conf
├── knowledge_base/     Architecture & methodology docs
└── test/               Unit + integration tests
```

**Dependency rule**: inner layers NEVER import outer layers.

## Architecture

Onion Architecture + DDD + CQRS/ES:

```
domain/          ← Pure domain logic, zero external deps, FP only
application/     ← Use-case orchestration, Aggregate, Saga, Projection
infrastructure/  ← Framework adapters (Akka, Play, Guice, MongoDB, MySQL)
```

- **Write side**: Akka EventSourcedBehavior → MongoDB journal (Protobuf serialized)
- **Read side**: Akka Projection → MySQL (ScalikeJDBC), continuously updated from events
- **Saga**: TCC pattern (Try-Confirm-Cancel) → Backward Recovery with selective compensation

Read the full architecture reference: [`knowledge_base/architecture/onion-cqrs-reference.md`](knowledge_base/architecture/onion-cqrs-reference.md)

## M1 — Saga Distributed Transaction Coordinator

TCC pattern backed by Akka Persistence. Each step runs in its own `StepExecutor` actor; the `SagaTransactionCoordinator` drives the state machine through Prepare → Commit → Compensate. Failed compensations enter `SUSPENDED` state for manual intervention.

Read the details: [`knowledge_base/architecture/saga.md`](knowledge_base/architecture/saga.md)

## M2 — Multi-Level DAG Execution Engine

6 standard components (Splitter, Merger, Classifier, Processor, Buffer, Router) assembled into pipelines. `ChainExecutionActor` orchestrates multi-level DAG execution. Sub-batches flow through defined pipelines; a global `FailureItemRouter` handles rejected items.

Includes M2.5+ runtime component engine with business DSL for Fab M3 integration.

## M3 — Manufacturing Cybernetics CIMs iPaaS (in exploration)

Decision layer on top of M2.5+: POR Repository → Dynamic Flow Assembler → Saga/LotContext distributed transaction → ChainDSL injection. MU Account Matrix for multi-dimensional accountability. Fact-driven closed loop — zero modification to M2.5+ engine.

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

- [Architecture Best Practices](knowledge_base/methodology/architecture-best-practices.md)
- [Agent Parallel Development](knowledge_base/methodology/agent-parallel-dev.md)
- [Harness Engineering](knowledge_base/methodology/harness-engineering.md)
