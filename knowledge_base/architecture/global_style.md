### Global Coding Standards & Tech Stack

**Tech Stack**:
- **Language**: Scala 2.13
- **Core Framework**: Akka Typed (Actor Model), Akka Persistence, Akka Cluster Sharding
- **Web Framework**: Play Framework
- **DB Access**: ScalikeJDBC (Read-Side), MongoDB (Write-Side Journal)

**Mandatory Coding Conventions**:
1.  **Functional Error Handling**:
    -   **BAN**: `throw Exception`, `require`, `assert` in domain logic.
    -   **USE**: `Option[T]` for missing values, `Either[iMadzError, T]` for logic failures.
    -   **BAN**: Cats/ZIO/Scalaz (Keep it simple with std lib Monads).
2.  **Immutability**: All `case class` and Collections (`scala.collection.immutable`) must be immutable.
3.  **Date & Time**: USE `java.time.*` (Instant, LocalDateTime). BAN `java.util.Date`.
4.  **Pattern Matching**: Prefer `match case` over `if/else` for logic branching.
5.  **Naming**: Value Objects (e.g., Money) use symbolic methods (`+`, `-`, `<=`) instead of `plus/minus`.

== [BASIC TYPES USAGE] ==
1. Id:
    - Import: `import net.imadz.common.Id` (Do NOT use `CommonTypes.Id`)
    - Construction: Use `Id.gen` for random IDs, or `Id("UUID-string-value")` for specific ones.
2. iMadzError:
    - Import: `import net.imadz.common.CommonTypes.iMadzError`
    - Construction: Use `iMadzError("errorCode", "errorMessage")`