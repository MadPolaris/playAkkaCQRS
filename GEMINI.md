# Play-Akka-CQRS Project

## Project Overview

This is a Scala-based software project implementing a robust, distributed application architecture based on **Command Query Responsibility Segregation (CQRS)** and **Event Sourcing** using the **Play Framework** and the **Akka toolkit**.

### Key Technologies & Architecture
*   **Language:** Scala 2.13.14
*   **Web Framework:** Play Framework (with Guice for Dependency Injection)
*   **Core Toolkit:** Akka 2.6.20
    *   **Akka Cluster & Sharding:** For distributed actors and state management.
    *   **Akka Persistence:** Implements Event Sourcing.
        *   **Write Journal & Snapshots:** Stores events and state snapshots using **MongoDB** (via `akka-persistence-mongo-scala`).
    *   **Akka Projections & Query:** Implements the CQRS read-side.
        *   **Read Models:** Projected into **MySQL** using JDBC and `scalikejdbc`.
*   **Distributed Transactions:** Implements the **Saga Pattern** (`SagaTransactionCoordinator`, `StepExecutor`) for orchestrating long-running, cross-aggregate transactions without two-phase commit.
*   **Serialization:** Uses **Protobuf** (`scalapb`) for efficient, schema-evolvable serialization of events, commands, and state.
*   **Build Tool:** `sbt` (Simple Build Tool)

### Directory Structure Highlights
*   `app/net/imadz/application/`: Contains the core Application Services, Aggregate definitions (e.g., `CreditBalanceAggregate`), Projections (e.g., `MonthlyIncomeAndExpenseSummaryProjection`), and Saga implementations.
*   `app/net/imadz/domain/`: Contains Domain Entities, Value Objects, and Invariants (Business Rules).
*   `app/net/imadz/infrastructure/`: Connects the domain/application to the external world (Persistence adapters, Bootstrap modules).
*   `app/protobuf/`: Definitions for Protobuf messages used in events and state serialization.
*   `conf/`: Configuration files (Play's `application.conf`, Akka's `persistence.conf`, `cluster.conf`, etc.).
*   `test/`: Unit and Integration tests.
*   `knowledge_base/`: Project documentation and architecture diagrams.

## Building and Running

### Prerequisites
1.  **Java:** Java 11 (specified in `build.sbt` as `-target:11`).
2.  **sbt:** The standard Scala build tool.
3.  **Docker & Docker Compose:** Required to run the local database instances.

### Local Development Setup

1.  **Start Local Infrastructure:**
    Start the required MongoDB (Write-side) and MySQL (Read-side) databases using Docker Compose.
    ```bash
    docker-compose up -d
    ```

2.  **Compile and Run Tests:**
    ```bash
    sbt clean compile test
    ```

3.  **Run the Application:**
    Start the Play application locally.
    ```bash
    sbt run
    ```
    The application will typically be available at `http://localhost:9000` (Play Framework default).

### Project Specific Notes
*   **Protobuf Compilation:** Protobuf generation is integrated into the build cycle. If you change `.proto` files in `app/protobuf/`, running `sbt compile` will auto-generate the Scala classes.
*   **Database Initialization:** The `docker-compose.yaml` maps `conf/sql/1.sql` to initialize the MySQL database schema for the read-side projections on startup.

## Development Conventions

*   **CQRS Separation:** Strictly separate commands (which change state and emit events) from queries (which read from projected views).
*   **Event Sourcing:** State changes are captured as immutable events appended to the journal. Do not mutate entity state directly; state mutations should only happen by applying events.
- Serialization: All persisted events, snapshots, and cluster messages should be defined using Protobuf to ensure backwards/forwards compatibility across releases. Ensure `SerializationExtension` is correctly configured for new types.
- Saga Transaction Contract:
    - **Backward Recovery Strategy**: The engine defaults to rolling back (Compensate) if either Prepare OR Commit phase fails.
    - **Selective Compensation**: Only steps that successfully finished Prepare are compensated.
    - **Manual Intervention**: If Compensate fails, the transaction enters `SUSPENDED`. Use `ManualFixStep` + `RetryCurrentPhase` to resolve.
- Testing: Akka TestKit (`akka-actor-testkit-typed`, `akka-persistence-testkit`, `akka-projection-testkit`) and ScalaTest (`scalatestplus-play`) are used extensively for testing actor behaviors, event sourcing logic, and application flows.

## Development & Debugging Guidelines (Lessons Learned)

* **Protobuf Synchronization (The "Three-in-One" Rule):** Whenever modifying domain State or Events in Scala, you MUST synchronously update three places: the Scala Case Class, the `.proto` message definitions, and the corresponding Proto Converters (`toProto`/`fromProto`). Failure to do so will break serialization silently.
* **Exception Classification:** NEVER use string matching (`e.getMessage.contains(...)`) for exception control flow or classification. Rely strictly on the type system (e.g., `case e: RetryableFailure =>`).
* **EventSourcing State Transitions:** During state transitions (especially Error paths and Saga Compensations), explicitly ensure that critical context like `replyTo` (ActorRef) and `reason` (failure message) are preserved and copied into the new State.
* **Akka TestKit Timeouts:** When testing long-running Saga steps or retries, be aware that `TestProbe` has a hardcoded default timeout. You MUST manually increase `akka.test.single-expect-default` and `akka.actor.testkit.typed.single-expect-default` in the test's `ConfigFactory` to avoid premature test aborts.
* **TestKit vs. Actor Lifecycle:** Avoid using `EventSourcedBehaviorTestKit.runCommand` for Actors that execute `.thenStop()` upon completion, as it will cause the TestKit to hang waiting for a state response. Use `ref ! Command` and verify via `TestProbe` instead.
* **Validate Test Logic First:** If a test persistently fails with a timeout or unexpected state, question the test's logical premise (e.g., expecting retries *after* `maxRetries` is reached) before altering product code to satisfy a flawed test.

