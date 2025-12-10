### Layer: Domain

**Definition**:
The core business logic layer. Contains Entities, Value Objects, Policies, and Domain Services.

**Constraints**:
1.  **Purity**: **STRICTLY PURE SCALA**. NO external frameworks (Akka, Play, JSON, DB) imports allowed.
2.  **Dependencies**: ZERO dependencies on Application or Infrastructure layers.
3.  **Location**: `net.imadz.domain.*`

**Components**:
- **Entity**: `domain.entities.*`. Defines State and Events.
- **EventHandler**: `domain.entities.behaviors.*`. Pure function `(State, Event) => State`.
- **Policy**: `domain.policy.*`. Encapsulates business rules.
- **ValueObject**: `domain.values.*`. Immutable data structures with logic.