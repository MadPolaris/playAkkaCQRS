### Layer: Application

**Definition**:
Orchestrates business logic, manages state lifecycle, and coordinates tasks.

**Constraints**:
1.  **Role**: Facade and Orchestrator. Does NOT contain core business rules (delegates to Domain layer).
2.  **Tech**: Akka Typed Actors, Cluster Sharding, Eventsourced Akka Projection, Futures.
3.  **Location**: `net.imadz.application.*`

**Components**:
- **Aggregate Root**: `application.aggregates.*`. Akka Behaviors wrapper around Domain Entities.
- **Saga**: `application.services.transactor.*`. Distributed transaction coordination.
- **Application Service**: `application.services.*`. Bridges HTTP/API to Domain/Aggregates.
- **Projection**: `application.projection.*`. CQRS Read-side logic.