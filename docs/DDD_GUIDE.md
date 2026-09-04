# DDD Guide — How Every Concept Lands in This Codebase

English | [中文](DDD_GUIDE-zh.md)

This guide maps each **Domain-Driven Design** tactical pattern to the exact files that implement it in this repository, with file counts, code excerpts, and the wiring between layers. Read it top-down the first time; use the summary matrix at the end as a lookup table afterwards.

Other guides: [README](../README.md) · [Saga Guide](SAGA_GUIDE.md)

---

## Table of Contents

1. [The Dependency Rule (Onion Architecture)](#1-the-dependency-rule-onion-architecture)
2. [Concept-by-Concept Mapping](#2-concept-by-concept-mapping)
   - [Value Object](#21-value-object)
   - [Domain Event](#22-domain-event)
   - [Aggregate State](#23-aggregate-state)
   - [Event Handler (state evolution)](#24-event-handler-state-evolution)
   - [Invariant Rule](#25-invariant-rule)
   - [Domain Service](#26-domain-service)
   - [Command & Protocol](#27-command--protocol)
   - [Command Handler & Command Helper](#28-command-handler--command-helper)
   - [Aggregate Root (clustered entity)](#29-aggregate-root-clustered-entity)
   - [Factory](#210-factory)
   - [Repository (write side)](#211-repository-write-side)
   - [Application Service](#212-application-service)
   - [Query (CQRS read side)](#213-query-cqrs-read-side)
   - [Read Model & Projection](#214-read-model--projection)
   - [Persistence Adapter (anti-corruption)](#215-persistence-adapter-anti-corruption)
   - [Composition Root / Bootstrap](#216-composition-root--bootstrap)
   - [Presentation](#217-presentation)
3. [End-to-End Traceability](#3-end-to-end-traceability)
4. [File-Count Summary](#4-file-count-summary)
5. [Testing by Layer](#5-testing-by-layer)

---

## 1. The Dependency Rule (Onion Architecture)

Dependencies point **inward only**. The domain knows nothing about Akka persistence, Play, or MySQL.

```
            ┌────────────────────────────────────────────┐
            │  Presentation (controllers, views)         │
            │  ┌──────────────────────────────────────┐  │
            │  │  Application (services, queries,     │  │
            │  │  projections, aggregate wiring)      │  │
            │  │  ┌────────────────────────────────┐  │  │
            │  │  │  Domain (entities, values,     │  │  │
            │  │  │  invariants, domain services)  │  │  │
            │  │  └────────────────────────────────┘  │  │
            │  └──────────────────────────────────────┘  │
            │  Infrastructure (adapters implement        │
            │  application/domain SPIs)                  │
            └────────────────────────────────────────────┘
```

Enforced mechanically by package layout:

| Layer | Package | Scala files | May depend on |
|---|---|---|---|
| Domain | `app/net/imadz/domain` | **13** | `common-core` abstractions only (`Money` uses `java.util.Currency`; rules use the `InvariantRule` SPI from `common-core`) |
| Application | `app/net/imadz/application` | **22** | Domain + Akka Typed (actors, sharding) + saga-core DSL |
| Infrastructure | `app/net/imadz/infrastructure`, `app/modules` | **12** | Application-defined SPIs + concrete tech (Mongo, Protobuf, ScalikeJDBC) |
| Presentation | `app/controllers`, `app/views` | **3 + 3** | Application services & queries |
| Shared kernel | `common-core` (separate module) | — | Akka Typed only |

The domain has **zero imports** from `akka.persistence`, `play.api`, or any driver — the event-sourced machinery lives in the application layer's aggregate wiring, and serialization lives in infrastructure adapters.

---

## 2. Concept-by-Concept Mapping

### 2.1 Value Object

> An immutable object with no identity, defined wholly by its attributes; equality by value.

**Implementation (1 file)** — `app/net/imadz/domain/values/Money.scala`

```scala
case class Money(amount: BigDecimal, currency: Currency)
```

The interesting part is that illegal operations are **unrepresentable rather than thrown**: `+`, `-` and `<=` return `Option[Money]`/`Option[Boolean]` and yield `None` when currencies differ. Callers in the domain rules must therefore handle the cross-currency case explicitly — no runtime surprise.

`Money` appears in commands, events, and state (see below), and is serialized inside the protobuf event adapter.

### 2.2 Domain Event

> Immutable facts about something that happened, named in past tense; the *only* write model of record.

**Implementation (defined in 1 file)** — `app/net/imadz/domain/entities/CreditBalanceEntity.scala`

Seven events, all case classes:

| Event | Meaning |
|---|---|
| `BalanceChanged(update, timestamp)` | Balance of one currency changed (+ deposit, − withdraw) |
| `FundsReserved(transferId, amount)` | Transfer funds reserved (TCC *Try* on payer) |
| `FundsDeducted(transferId, amount)` | Reserved funds definitively deducted (TCC *Confirm* on payer) |
| `ReservationReleased(transferId, amount)` | Reservation rolled back (TCC *Cancel* on payer) |
| `IncomingCreditsRecorded(transferId, amount)` | Incoming credit registered (TCC *Try* on payee) |
| `IncomingCreditsCommited(transferId)` | Incoming credit added to balance (TCC *Confirm* on payee) |
| `IncomingCreditsCanceled(transferId)` | Incoming credit rolled back (TCC *Cancel* on payee) |

Events are the contract between layers: the event handler turns them into state, the projection turns them into read models, and the protobuf adapter turns them into bytes. Note the event names carry the TCC vocabulary — this aggregate was designed *for* the saga from day one.

### 2.3 Aggregate State

> The full current facts of an aggregate, rebuilt by folding events.

**Implementation (same file)** — `CreditBalanceEntity.scala`

```scala
case class CreditBalanceState(
  userId: String,
  accountBalance: Map[String, Money],       // currency code -> balance
  reservedAmount: Map[Id, Money],           // transferId -> reserved (payer side)
  incomingCredits: Map[Id, Money]           // transferId -> pending credit (payee side)
)
```

`CreditBalanceEntity.empty(userId)` is the factory for a fresh aggregate. Because pending TCC work (reservations, incoming credits) lives **in state**, it survives crashes with no external transaction coordinator — the saga engine replays both its own journal and these aggregates to resume.

### 2.4 Event Handler (state evolution)

> The pure function `(State, Event) => State` that defines what each event *means*.

**Implementation (1 file)** — `app/net/imadz/domain/entities/behaviors/CreditBalanceEventHandler.scala`

Rules of interest:

- `FundsReserved` decreases the balance **and** records the reservation (money is "set aside", not gone).
- `FundsDeducted` only removes the reservation — the money left the balance at reserve time (that's what makes the TCC *Try* safe).
- `ReservationReleased` adds the amount back and drops the reservation.
- `IncomingCreditsCommited` moves the pending credit into the balance; `…Canceled` just drops it.

Being a pure function makes this the most test-friendly artifact in the codebase: no actor, no I/O.

### 2.5 Invariant Rule

> Business rules that must hold *before* an event may be emitted — the transactional boundary of the aggregate. This repo models them as first-class, composable objects instead of scattered `if` statements.

**Implementation (9 files)** — `app/net/imadz/domain/invariants/`

All rules implement the `InvariantRule[Event, State, P]` SPI from `common-core`: given the current state and a parameter, return `Either[iMadzError, List[Event]]` — i.e. **decide which events may be appended**.

| Rule file | Guards | Emits | Error code |
|---|---|---|---|
| `AddInitialOnlyOnceRule` | initial credit only when all balances are zero/empty | `BalanceChanged` | 60000 |
| `DepositRule` | deposit amount must be positive | `BalanceChanged` | 60001 |
| `WithdrawRule` | amount positive and covered by balance | `BalanceChanged` (negative) | 60002 |
| `ReserveFundsRule` | delegates to `TransferDomainService` (below); duplicate reservation (60008) is an idempotent `Right(Nil)` — no events | `FundsReserved` | 60003/60004 |
| `DeductFundsRule` | reservation must exist | `FundsDeducted` | 60006 |
| `ReleaseReservedFundsRule` | reservation must exist | `ReservationReleased` | 60006 |
| `RecordIncomingCreditsRule` | transferId must not be registered twice | `IncomingCreditsRecorded` | 60007 |
| `CommitIncomingCreditsRule` | credit must be registered first | `IncomingCreditsCommited` | 60008 |
| `CancelIncomingCreditRule` | credit must be registered first | `IncomingCreditsCanceled` | 60009 |

Error codes form a stable business-error vocabulary (`iMadzError`), which the saga participants later classify into *retryable vs non-retryable* failures — the glue between DDD and the saga engine.

### 2.6 Domain Service

> Stateless business logic that doesn't naturally belong to one entity.

**Implementation (1 file)** — `app/net/imadz/domain/services/TransferDomainService.scala`

`validateTransfer(transferId, reservedAmount, fromBalance, amount)` checks: no duplicate reservation (60008), sufficient funds (60003), positive amount (60004). `ReserveFundsRule` composes it — showing the rule/service split: rules orchestrate, services compute.

### 2.7 Command & Protocol

> The aggregate's public message API: commands in, confirmations out.

**Implementation (1 file)** — `app/net/imadz/application/aggregates/CreditBalanceProtocol.scala`

- **10 commands**: `AddInitial`, `Deposit`, `Withdraw`, `GetBalance`, and the six TCC commands `ReserveFunds` / `DeductFunds` / `ReleaseReservedFunds` / `RecordIncomingCredits` / `CommitIncomingCredits` / `CancelIncomingCredits` (each keyed by `transferId`).
- **Replies**: `CreditBalanceConfirmation(error, balances)` plus per-command confirmations.
- Declares the `CreditBalanceCommandHandler` type alias used by the aggregate.

This protocol is the *contract* the saga participants program against (see [Saga Guide §Participants](SAGA_GUIDE.md#quick-start-in-code)).

### 2.8 Command Handler & Command Helper

> Where commands meet rules: validate → persist events → reply.

**Implementation (2 files)** — `app/net/imadz/application/aggregates/behaviors/`

- `CreditBalanceBehaviors.scala` groups the ten commands into three handlers — *Direct* (AddInitial/Deposit/Withdraw/GetBalance), *Reserve*, *IncomingCredit* — each running the same template: `runReplyingPolicy(Rule, Helper)`.
- `CreditBalanceCommandHelpers.scala` contains nine `CommandHelper` instances — the "glue" that maps a command to its rule's parameter and maps `Right(events)` / `Left(error)` back to a reply. Adding a new command touches exactly: protocol + helper + behaviors + one rule.

This indirection keeps a uniform shape across all ten commands (single code path for validation/persistence/reply), at the cost of one more file — a deliberate trade documented in `knowledge_base/artifacts/`.

### 2.9 Aggregate Root (clustered entity)

> The consistency boundary, reachable by stable id — here a cluster-sharded, event-sourced Akka Typed entity.

**Implementation spans two layers (by design):**

| Part | File | Layer |
|---|---|---|
| Entity type key, event tags (`credit-balance-0..4`), entity behavior composition | `app/net/imadz/application/aggregates/CreditBalanceAggregate.scala` | Application |
| Sharding init, `EventSourcedBehavior` config (snapshot every 100 events, persist-failure backoff, event/snapshot adapters, tagger) | `app/net/imadz/infrastructure/bootstrap/CreditBalanceBootstrap.scala` | Infrastructure |

The domain state (§2.3) and rules (§2.5) stay pure; the *actor* is just a shell wired around them in the application layer, and its Akka configuration is infrastructure.

### 2.10 Factory

> Creates aggregate instances / entity references.

**Implementation (1 file)** — `app/net/imadz/application/aggregates/factories/CreditBalanceAggregateFactory.scala`

Used by `CreateCreditBalanceService` to open a new account (emitting `AddInitial`) and by the repository to obtain `EntityRef`s.

### 2.11 Repository (write side)

> Abstracts "fetch an aggregate by id" behind an interface owned by the application layer.

**Implementation (2 files, interface + adapter):**

- Trait: `app/net/imadz/application/aggregates/repository/CreditBalanceRepository.scala` — `findCreditBalanceByUserId(id): EntityRef[CreditBalanceCommand]`
- Impl: `app/net/imadz/infrastructure/repositories/aggregate/CreditBalanceRepositoryImpl.scala` — `ClusterSharding.entityRefFor(...)`

Classic DDD repositories return *materialized objects*; in an Akka system the "materialization" is a message channel to a (possibly dormant) sharded entity — the abstraction survives the translation.

### 2.12 Application Service

> Use-case orchestration: one public method per business transaction; no business rules of its own.

**Implementation (4 files)** — `app/net/imadz/application/services/`

| Service | Use case | Talks to |
|---|---|---|
| `CreateCreditBalanceService` | Open an account (optional initial deposit) | Factory → aggregate |
| `DepositService` | Deposit | Repository → `Deposit` command |
| `WithdrawService` | Withdraw | Repository → `Withdraw` command |
| `MoneyTransferService` | Cross-account transfer | `MoneyTransferSagaDefinition.runner` (TCC saga) — idempotent by `txId`, returns `TransferSubmission` with a completion future + `statusOf(txId)` polling |

The first three are one-liners around ask(); the transfer service swaps *in-process orchestration* for the saga engine — the point where DDD meets distributed transactions.

### 2.13 Query (CQRS read side)

> Reads never go through rules or events; they hit either the live aggregate or a read model.

**Implementation (2 files)** — `app/net/imadz/application/queries/`

- `GetBalanceQuery` — asks the aggregate's `GetBalance` command: **strongly consistent** (in-memory state of the sharded entity).
- `GetRecent12MonthsIncomeAndExpenseReport` — queries the MySQL read-side repository: **eventually consistent** by design.

### 2.14 Read Model & Projection

> Denormalized views built from event streams.

**Implementation (4 files)** — `app/net/imadz/application/projection/`

| File | Consumes | Produces | Semantics |
|---|---|---|---|
| `MonthlyIncomeAndExpenseSummaryProjection` | Mongo read-journal streams tagged `credit-balance-0..4` | MySQL `monthly_income_and_expense_summary` | exactly-once (`JdbcProjection.exactlyOnce`, ScalikeJDBC session) |
| `MonthlyIncomeAndExpenseSummaryProjectionHandler` | `BalanceChanged` (±), `FundsDeducted` (−) | monthly income/expense rows keyed by user × month | — |
| `SagaBusinessEventProjection` | coordinator journal tags | resolved saga `onResult` business events → event stream | at-least-once; consumers dedupe by `txId` |
| `repository/MonthlyIncomeAndExpendsSummaryRepository` | — | read-side repository trait + table model | — |

The tag-based fan-out (5 tags) is what lets five projection instances run in parallel (ShardedDaemonProcess).

### 2.15 Persistence Adapter (anti-corruption)

> Translates the domain's vocabulary into the database's bytes — and keeps the domain ignorant of both.

**Implementation (3 files)** — `app/net/imadz/infrastructure/persistence/` (+ `converters/`)

- `CreditBalanceEventAdapter` / `CreditBalanceSnapshotAdapter` — Akka `EventAdapter`s between domain events/state and protobuf `CreditBalanceEventPO` / `CreditBalanceStatePO`.
- `CreditBalanceProtoConverters` — the field-level mappings (schema lives in protobuf definitions; journals therefore have an explicit, evolvable wire format).

### 2.16 Composition Root / Bootstrap

> Wires everything at startup — the only place allowed to know the whole graph.

**Implementation (6 files)**

- `app/net/imadz/infrastructure/bootstrap/ApplicationBootstrap.scala` — the ordered startup: ① register saga definitions → ② init CreditBalance sharding → ③ init saga engine (shared coordinator sharding for all definitions) → ④ saga business-event projection → ⑤ monthly summary projection.
- `bootstrap/SagaEngineBootstrap.scala`, `bootstrap/SagaBusinessEventProjectionBootstrap.scala`, `bootstrap/MonthlyIncomeAndExpenseBootstrap.scala` — the individual steps.
- `infrastructure/SuffixCollectionNames.scala` — Mongo collection naming policy.
- `app/modules/BootstrapModule.scala` — Guice module binding `ApplicationBootstrap` as an eager singleton.

### 2.17 Presentation

**Implementation (6 files)** — `app/controllers/` (2), `app/controllers/filter/LoggingFilter.scala` (1), `app/views/` (3 Twirl templates, incl. the Saga Showcase single-page UI), plus `conf/routes`.

Controllers stay thin: parse request → call an application service or query → serialize the reply. `ShowcaseController` additionally owns the WebSocket hub that publishes `SagaProgressEvent`s.

---

## 3. End-to-End Traceability

**"Deposit 100 CNY" touches:**

`HomeController.deposit` → `DepositService` → `CreditBalanceRepository` (trait) → `CreditBalanceRepositoryImpl` → sharding `EntityRef` → `CreditBalanceBehaviors` (Direct group) → `CreditBalanceCommandHelpers.DepositHelper` → `DepositRule` → event `BalanceChanged` → `CreditBalanceEventHandler` → (async) `MonthlyIncomeAndExpenseSummaryProjectionHandler` → MySQL row.

**"Transfer 10 CNY A→B" touches:**

`HomeController.transfer` → `MoneyTransferService` → `MoneyTransferSagaDefinition` (`preCheck` → saga start) → saga coordinator → `FromAccountParticipant` (`ReserveFunds`/`DeductFunds`/`ReleaseReservedFunds` via the same rules as above) + `ToAccountParticipant` (`Record`/`Commit`/`Cancel` incoming credits) → on completion `SagaBusinessEventProjection` resolves `onResult` → `MoneyTransferCompleted` business event.

**"Fix a stuck saga" touches:**

`ShowcaseController.fixStep`/`resume` → `SagaRunner.admin` → coordinator `ManualFixStep`/`ResolveSuspended` → journaled `StepManuallyFixed` → phase re-drive with the fixed step skipped (see [Saga Guide §Manual intervention](SAGA_GUIDE.md#manual-intervention)).

---

## 4. File-Count Summary

| DDD concept | Files | Where |
|---|---:|---|
| Value Object | 1 | `domain/values` |
| Domain Event + Aggregate State | 1 | `domain/entities` |
| Event Handler | 1 | `domain/entities/behaviors` |
| Invariant Rule | 9 | `domain/invariants` |
| Domain Service | 1 | `domain/services` |
| Command/Protocol | 1 | `application/aggregates` |
| Command Handler + Helpers | 2 | `application/aggregates/behaviors` |
| Aggregate Root wiring | 1 | `application/aggregates` (+1 infra bootstrap) |
| Factory | 1 | `application/aggregates/factories` |
| Repository (write) | 2 | `application/.../repository` + `infrastructure/repositories/aggregate` |
| Application Service | 4 | `application/services` (+6 in `transactor/`) |
| Query | 2 | `application/queries` |
| Read Model / Projection | 4 | `application/projection` (+1 infra bootstrap) |
| Persistence Adapter | 3 | `infrastructure/persistence` |
| Composition Root | 6 | `infrastructure/bootstrap`, `modules` |
| Presentation | 6 | `controllers`, `views` |
| **Domain total** | **13** | |
| **Application total** | **22** | |
| **Infrastructure total** | **11 (+1 module)** | |

## 5. Testing by Layer

| Layer | Tested by | Style |
|---|---|---|
| Domain rules/state | `test/net/imadz/banking/.../CreditBalanceCommandHelpersSpec` (+ `CommandHelperTestKit`) | pure-function assertions |
| Saga engine | `saga-core/src/test` — 53 cases, AC-1.1…AC-1.12 + AC-MF | acceptance on the persistence testkit (in-memory journal) |
| Whole app | `sbt acceptance` (`= test`) | gate alias; any failure breaks the build |
