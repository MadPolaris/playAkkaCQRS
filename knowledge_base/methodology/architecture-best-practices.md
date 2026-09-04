# Architecture Best Practices: The "Why" Behind the Patterns

## 1. Onion Architecture: Boundaries Enable Parallelism

### The rule
```
domain/          ← 纯领域层：零外部依赖，纯函数为主
application/     ← 用例层：只依赖 domain
infrastructure/  ← 适配层：依赖所有内层，外部框架只在此层
```

**Iron law:** Inner layers never import outer layer packages. A `domain/` file importing `akka.*` is a violation.

### Why this matters

This isn't about aesthetics. It's about **parallel work without locks.**

When the dependency direction is strictly enforced:
- A developer working on `domain/invariants/DepositRule.scala` never touches the same file as someone working on `infrastructure/persistence/CreditBalanceEventAdapter.scala`
- Layers become **synchronization boundaries**. No one waits for anyone else.
- Each layer can be tested independently: domain with pure unit tests, application with Actor test kits, infrastructure with integration tests.

In the Task Radar methodology, this means 10 people can pick 10 tasks from different layers and work simultaneously. The layer boundary is the contract.

### The cost

More files. More indirection. A simple feature touches 5-7 files across 3 layers. This is deliberate: each file is small enough to fit in an AI context window and be reasoned about locally.

---

## 2. Interface-Driven Development: Contracts Over Coordination

### The pattern

Every component exposes a trait (interface) before it has an implementation:

```
application/aggregates/repository/CreditBalanceRepository.scala  ← trait (contract)
infrastructure/repositories/aggregate/CreditBalanceRepositoryImpl.scala ← class (implementation)
```

The same pattern applies to: AggregateFactory, ProjectionRepository, SagaParticipant, DomainService, ApplicationService.

### Why this matters

Interfaces are **contracts that eliminate coordination.**

In the Task Radar workflow:
1. One person defines the interface (signature + semantics)
2. Ten people implement against it — in parallel, without talking to each other
3. The interface is the only thing they need to agree on

This is the same principle as dependency injection, applied at the team level. The interface is the API between teammates.

For AI agents: a trait with a clear signature and documented semantics is a **function specification**. The agent implements the body; the signature guarantees compatibility.

---

## 3. Artifact Decomposition: Small Files for Local Reasoning

### The pattern

What could be one large class is split into 9+ files:

| Artifact | File | Layer |
|----------|------|-------|
| Domain Entity | `CreditBalanceEntity.scala` | domain |
| Event Handler | `CreditBalanceEventHandler.scala` | domain |
| Aggregate Protocol | `CreditBalanceAggregate.scala` | application |
| Command Behaviors | `CreditBalanceBehaviors.scala` | application |
| Command Helpers | `CreditBalanceCommandHelpers.scala` | application |
| Aggregate Factory | `CreditBalanceAggregateFactory.scala` | application |
| Repository Trait | `CreditBalanceRepository.scala` | application |
| Bootstrap | `CreditBalanceBootstrap.scala` | infrastructure |
| Repository Impl | `CreditBalanceRepositoryImpl.scala` | infrastructure |

Each file is ~20-80 lines. Each has a single responsibility.

### Why this matters

This decomposition is designed for AI context windows:

- **Small context = precise reasoning.** An agent fixing a bug in `WithdrawRule` doesn't need to load `CreditBalanceBehaviors`. The invariant rule is a pure function of `(State, Param) => Either[Error, List[Event]]` — fully testable in isolation.
- **No spillover effects.** Changing an event handler doesn't risk breaking the command protocol, because they're in different files with different import dependencies.
- **Each file matches one artifact template.** The 30 files in `knowledge_base/artifacts/` correspond 1:1 with the artifact types that appear in the codebase.

The cost is cognitive load for humans navigating the codebase. The benefit is that AI agents (and humans) can reason locally about any single file.

---

## 4. The "Three-Piece" Protobuf Rule

### The rule

When you modify any one of these, you must synchronize the other two:

1. **Scala Case Class** (in `domain/` or `application/`)
2. **`.proto` file** (in `app/protobuf/`)
3. **Proto Converter** (`toProto` / `fromProto` in `infrastructure/persistence/converters/`)

### Why this matters

Serialization is the single highest-risk area in a CQRS/Event Sourcing system. When an event is persisted to MongoDB and later replayed, the deserialized form must be byte-identical to what was serialized. A mismatch means **corrupted event journals** — data loss.

The three-piece rule makes desynchronization obvious at compile time rather than at runtime during event replay. If you add a field to the Scala case class but forget the `.proto`, the converter won't compile. The compiler enforces consistency.

For AI agents: this rule is stated explicitly in CLAUDE.md because agents tend to modify one file and forget the other two. The rule forces them to check all three.

---

## 5. Functional Programming Patterns

### Either for errors, not exceptions

```scala
// Domain invariant returns Either, never throws
def apply(state: State, param: Money): Either[iMadzError, List[Event]]
```

**Why:** Error paths are explicit in the type signature. The compiler enforces that callers handle both success and failure. In Event Sourcing, an unhandled error on event replay = unrecoverable state. Either makes the error path visible.

### Immutable data structures

```scala
// State evolves through copy, never mutation
state.copy(accountBalance = state.accountBalance + (currency -> newBalance))
```

**Why:** Event Sourcing replays events to rebuild state. If state is mutable, replay can produce different results on different runs. Immutability guarantees deterministic replay.

### Pure functions in domain layer

```scala
// Event handler: (State, Event) => State — no side effects, no IO, no external calls
type CreditBalanceEventHandler = (CreditBalanceState, CreditBalanceEvent) => CreditBalanceState
```

**Why:** Domain logic that depends on database queries or HTTP calls can't be tested in isolation and can't be trusted during event replay. Pure functions in `domain/` guarantee that the core business rules are always testable with simple input/output assertions.

---

## 6. DSL Patterns: Guiding AI Toward Correct Code

### The Command Handler DSL

```scala
case cmd: Deposit =>
  runReplyingPolicy(DepositRule, DepositHelper)(state, cmd)
    .replyWithAndPublish(cmd.replyTo)(context)
```

This is not just syntactic sugar. It's a **structured template** that eliminates variance in how commands are handled:

1. Extract parameters from command (`DepositHelper.toParam`)
2. Run invariant rule (`DepositRule.apply`)
3. On failure: reply with error (`DepositHelper.createFailureReply`)
4. On success: persist events, then reply with success (`DepositHelper.createSuccessReply`)

**Why this matters for agents:** Without this DSL, an agent might write a raw `if/else` with inline error handling, forgetting to persist events, or mixing up reply types. The DSL constrains the agent to a single correct path. The pattern is so strong that `CreditBalanceBehaviors.scala` reads like a declarative routing table, not imperative code.

See source: [`CommandHandlerReplyingBehavior.scala`](../../../common-core/src/main/scala/net/imadz/common/application/CommandHandlerReplyingBehavior.scala)

### The InvariantRule trait

```scala
trait InvariantRule[Event, State, Param] {
  def apply(state: State, param: Param): Either[iMadzError, List[Event]]
}
```

A single interface for all business validation. Every invariant rule (DepositRule, WithdrawRule, ReserveFundsRule...) follows the exact same signature.

**Why this matters:** When every rule has the same shape, adding a new one is mechanical. An agent can be told "add a new invariant rule following the template" and it will produce code that fits the system perfectly. The type parameters (`Event`, `State`, `Param`) are a built-in checklist: did you define what events this produces? What state it reads? What parameter it needs?

See source: [`CommonTypes.scala`](../../../common-core/src/main/scala/net/imadz/common/CommonTypes.scala)

---

## Summary: Architecture as Force Multiplier

Every pattern above serves the same goal: **make the right thing the easy thing.**

- Onion boundaries → impossible to create coupling that blocks parallel work
- Interfaces everywhere → impossible to depend on implementation details
- Small files → impossible to create a file too large for AI context
- Three-piece rule → impossible to forget serialization during refactoring
- Either types → impossible to ignore error paths
- DSL patterns → impossible to write a command handler the wrong way

This is the essence of the project's architecture philosophy: constraints that **prevent mistakes** rather than guidelines that **request compliance.**
