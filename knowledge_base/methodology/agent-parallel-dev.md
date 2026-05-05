# Agent Parallel Development: The Next Evolution

## Vision

The Task Radar methodology was designed for human teams. The next evolution is to make it work for **human-agent hybrid teams** — where AI agents claim tasks from the radar, implement them, run tests, and report status, while humans act as architects, reviewers, and decision-makers.

**Human steers. Agent executes.**

## The three-stage evolution

### Stage 1: Human Team (current)

```
Miro Task Radar  ←→  Human developers
```

- Architecture is decomposed into artifacts
- Interfaces are defined collaboratively
- Humans claim tasks, implement, test, commit
- Task Radar shows real-time status

This stage is **validated** — it worked for 10+ people building this project.

### Stage 2: Agent-Assisted (near future)

```
Miro Task Radar  ←→  Human architects
                    ←→  AI Agents (implementers)
```

- Human architects define interfaces and artifact templates
- AI agents claim tasks from the radar
- Each agent: reads the interface → implements against the template → writes tests → opens PR
- Humans review PRs (but increasingly trust agent-to-agent review)
- Task Radar shows agent progress in real time

The key enabler at this stage is the `knowledge_base/artifacts/` directory. Each artifact template is a **prompt specification** for an agent:

```
Artifact: InvariantRule
Interface: (State, Param) => Either[Error, List[Event]]
Template:  knowledge_base/artifacts/domain_invariant.md
Test:      Verify with pure input/output assertions
```

When an agent claims "Implement WithdrawRule", it reads the template, understands the expected shape, and produces conforming code. The template is the instruction.

### Stage 3: Autonomous Agent Team (future)

```
Task Radar   ←→  Human architects (design only)
              ←→  AI Agents (full lifecycle)
              ←→  Agent reviewers
              ←→  Doc-gardening agents
```

- Agents handle the full development lifecycle: design → implement → test → review → merge
- Humans focus on: domain discovery, architecture evolution, quality standards, edge case identification
- Multiple agents work in parallel, each on different artifacts
- A "meta-agent" orchestrates: assigns tasks, resolves conflicts, escalates to humans when stuck
- Task Radar becomes the **human visibility layer** into autonomous agent work

## The code generation pipeline

The technical backbone that makes this possible:

```
DDD DSL Metadata
    ↓
Claude Code + Skills + MCP
    ↓
Generated Code Scaffold (directory structure, interfaces, protobuf)
    ↓
Claude Code fills in implementation (following artifact templates)
    ↓
TDD 7-Step Commit Cycle
    ↓
Agent Self-Review → PR → Merge
```

### DDD DSL Metadata

A domain-specific language captures the domain model: entities, value objects, aggregates, events, commands, sagas. This metadata drives the initial code generation, producing a scaffold with correct directory structure and interface definitions.

### Claude Code + Skills + MCP

Skills provide domain-specific capabilities (e.g., "generate an InvariantRule", "add a Saga participant"). MCP (Model Context Protocol) connects Claude to external tools: file system, git, build system, test runner.

### TDD 7-Step Commit Cycle

For each artifact implementation, the agent follows a disciplined commit sequence:

1. **Write failing test** — define expected behavior
2. **Commit test** — `test: add failing test for [artifact]`
3. **Run test** — verify it fails (Red)
4. **Implement** — write the minimal code to pass
5. **Run test** — verify it passes (Green)
6. **Refactor** — clean up while tests stay green
7. **Commit implementation** — `feat: implement [artifact]`

Each commit is a checkable unit of progress. If anything goes wrong, the agent can revert to the last known-good state. For human observers, the commit history tells the story of what was built and how.

## The human role

In this model, humans **stop being file editors** and become:

| Role | Responsibility |
|------|---------------|
| **Domain Expert** | Define what the system should do, clarify business rules |
| **Architect** | Design interfaces, enforce boundaries, evolve the structure |
| **Quality Gate** | Review critical PRs, define acceptance criteria, catch edge cases |
| **Tool Builder** | Create skills, templates, linters, and prompts that make agents more effective |
| **Troubleshooter** | Investigate when agents get stuck, identify missing constraints or tools |

## The agent role

Agents take over the **execution** layer:

| Role | Responsibility |
|------|---------------|
| **Implementer** | Write code that conforms to interfaces and templates |
| **Tester** | Write and run tests following TDD discipline |
| **Reviewer** | Review other agents' PRs against architectural constraints |
| **Gardener** | Detect and fix code drift, stale documentation, quality regressions |
| **Status Reporter** | Update Task Radar with progress, blockers, and completion |

## Why this project is the reference

This project (`playAkkaCQRS`) serves as the **gold standard reference implementation** for the code generation pipeline because:

1. **It was built with the exact architecture the pipeline generates.** Onion + DDD + CQRS/ES + Saga.
2. **Every artifact type has a template** in `knowledge_base/artifacts/` — these are the prompts for code generation.
3. **The coding conventions are mechanized** — FP patterns, DSLs, type safety rules are baked into the architecture, not just documented.
4. **It demonstrates the full stack** — from domain entities to infrastructure persistence, from Web UI to WebSocket streaming, from happy path to edge case recovery.

When the pipeline generates code, the output should look like this project. When an agent implements a feature, the quality bar is "does it match the patterns in playAkkaCQRS?"

## What still needs to be built

The vision requires infrastructure not yet in place:

- **Agent-accessible Task Radar.** The Miro board needs a machine-readable API so agents can query task status.
- **Mechanical constraint enforcement.** Linters that verify layer dependencies, protobuf sync, and coding conventions at CI time.
- **Agent self-review capability.** Scripts that let an agent run `sbt test`, check coverage, and verify its own work before opening a PR.
- **Multi-agent orchestration.** A coordinator that assigns non-conflicting tasks to agents, detects when an agent is stuck, and escalates.
- **Human visibility dashboard.** The Task Radar as a live dashboard showing agent progress, not just human progress.

These are the engineering challenges that will unlock the Stage 2 → Stage 3 transition.
