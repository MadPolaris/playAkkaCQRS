# Architecture Reference Index

Each file defines one architectural concept — what it is, what it contains, what it can import.

| Document | Content |
|----------|---------|
| [layer_domain.md](layer_domain.md) | Domain layer: Entity, ValueObject, DomainEvent, InvariantRule. Zero framework dependencies. |
| [layer_app.md](layer_app.md) | Application layer: Aggregate root, ApplicationService, Saga, Projection. Depends only on domain. |
| [layer_infrastructure.md](layer_infrastructure.md) | Infrastructure layer: Persistence adapter, Bootstrap, DI binding. Depends on all inner layers. |
| [aggregate_root.md](aggregate_root.md) | Aggregate Root pattern: Command→Event→State lifecycle, sharding, cluster. |
| [global_style.md](global_style.md) | Global coding standards: FP conventions, type system rules, naming patterns. |
| [onion-cqrs-reference.md](onion-cqrs-reference.md) | Full Onion+DDD+CQRS/ES reference: all components, their relationships, Saga lifecycle. |
| [projection.md](projection.md) | Projection pattern: event handlers, read-side repository, ScalikeJDBC/MySQL. |
| [saga.md](saga.md) | Saga framework: TCC coordinator, step executor, state machine, backward recovery. |
| [Saga职责边界图.png](Saga职责边界图.png) | Saga responsibility boundary diagram. |

## Reading Order

1. `onion-cqrs-reference.md` — the full picture
2. `layer_domain.md` → `layer_app.md` → `layer_infrastructure.md` — layer-by-layer
3. `aggregate_root.md` + `projection.md` + `saga.md` — per-pattern deep dives
4. `global_style.md` — coding conventions reference
