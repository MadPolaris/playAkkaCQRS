# Saga Framework Architecture & Components

The Saga engine is composed of three primary components working in orchestration: the **Coordinator**, the **Execution Plan**, and the **Step Executor**.

## 1. SagaTransactionCoordinator

The `SagaTransactionCoordinator` is an Akka Typed `EventSourcedBehavior` that acts as the stateful orchestrator for a single Saga transaction. It is typically accessed via Akka Cluster Sharding.

### Key Responsibilities:
- **State Management**: Persists critical transitions like `TransactionStarted`, `PhaseSucceeded`, and `StepResultReceived`.
- **Phase Control**: Manages the transitions between `Prepare`, `Commit`, and `Compensate` phases.
- **Error Handling**: Determines if a phase failure requires compensation or manual intervention (`Suspended` state).
- **Concurrency**: Manages parallel execution of steps within a `StepGroup`.

### Sequence Diagram: Coordinator Orchestration

```mermaid
sequenceDiagram
    participant Client
    participant STC as SagaTransactionCoordinator
    participant Plan as ExecutionPlan
    participant Executor as StepExecutor
    participant ESB as EventSourcedBehavior (Journal)

    Client->>STC: StartTransaction(steps)
    STC->>ESB: Persist(TransactionStarted)
    ESB-->>STC: State Updated
    
    STC->>Plan: Analyze(steps)
    STC->>STC: startPhase(PreparePhase)

    loop For each StepGroup in phase
        STC->>ESB: Persist(StepGroupStarted)
        loop For each step in StepGroup
            STC->>Executor: Start(step)
            Executor-->>STC: StepResult (Completed/Failed)
            STC->>ESB: Persist(StepResultReceived)
        end
        alt Group Completed
            STC->>ESB: Persist(StepGroupSucceeded)
        end
    end

    alt All phases successful
        STC->>ESB: Persist(TransactionCompleted)
        STC-->>Client: TransactionResult(success=true)
    else Any phase failed
        STC->>ESB: Persist(PhaseFailed)
        STC->>STC: startPhase(CompensatePhase)
        Note over STC,Executor: Only compensate successful steps
        STC->>ESB: Persist(TransactionFailed)
        STC-->>Client: TransactionResult(success=false)
    end
```

## 2. ExecutionPlan & StepGroup

The `ExecutionPlan` is a utility class that organizes `SagaTransactionStep`s. It supports **parallel execution** within a phase by grouping steps.

- **StepGroup**: A set of steps that can be executed concurrently. The Coordinator only moves to the next group once all steps in the current group have completed successfully.
- **Phase Ordering**: The plan ensures that `Prepare` steps are executed before `Commit` steps, and `Compensate` steps are executed in reverse order if necessary.

## 3. StepExecutor

The `StepExecutor` is a short-lived Akka actor responsible for executing a single step of a Saga phase for a specific participant.

### Resilience Features:
- **Retries**: Automatically retries operations that return a `RetryableFailure` (e.g., timeouts, network glitches).
- **Timeouts**: Enforces a per-step timeout duration.
- **Circuit Breaker**: Integrates Akka's `CircuitBreaker` to prevent overwhelming a failing participant service.
- **Event Sourcing**: Like the Coordinator, the `StepExecutor` is event-sourced, allowing it to recover and resume a retry even after a crash.

### Sequence Diagram: Step Execution Lifecycle

```mermaid
sequenceDiagram
    participant STC as SagaCoordinator
    participant Executor as StepExecutor
    participant Part as Participant
    participant CB as CircuitBreaker
    participant Timer as TimerScheduler

    STC->>Executor: Start(step)
    Executor->>Executor: Persist(ExecutionStarted)
    Executor->>Timer: Schedule Timeout
    Executor->>CB: withCircuitBreaker(operation)
    CB->>Part: execute(phase)

    alt Success
        Part-->>CB: Success(result)
        CB-->>Executor: Success(result)
        Executor->>Executor: Persist(OperationSucceeded)
        Executor-->>STC: StepCompleted
    else Retryable Failure
        Part-->>CB: Failure(Retryable)
        CB-->>Executor: Failure(Retryable)
        Executor->>Executor: Persist(RetryScheduled)
        Executor->>Timer: Schedule Retry Operation
    else Non-Retryable Failure
        Part-->>CB: Failure(NonRetryable)
        CB-->>Executor: Failure(NonRetryable)
        Executor->>Executor: Persist(OperationFailed)
        Executor-->>STC: StepFailed
    end
```

## 4. SagaParticipant Interface

Developers implement the `SagaParticipant` trait to integrate their domain logic into the Saga framework.

```scala
trait SagaParticipant[E, R, C] {
  protected def doPrepare(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R]
  protected def doCommit(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R]
  protected def doCompensate(transactionId: String, context: C, traceId: String): ParticipantEffect[E, R]
  
  // Custom error classification logic
  protected def customClassification: PartialFunction[Throwable, RetryableOrNotException]
}
```

- **Context (`C`)**: A read-only context object (e.g., `MoneyTransferContext`) providing access to repositories, configuration, and other dependencies.
- **Effect**: The return type is a `Future[Either[E, SagaResult[R]]]`, where `E` is the domain error type and `R` is the successful result type.
