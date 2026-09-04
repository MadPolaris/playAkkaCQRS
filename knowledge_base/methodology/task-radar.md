# Task Radar: Parallel Collaboration at Scale

## The origin

A team of 10+ developers needed to build a complex DDD/CQRS system. They were not all in the same office. They could not rely on whiteboard sessions, shoulder-tapping, or daily standups to coordinate.

The question was: how do you get 10+ people to work on the same codebase simultaneously without blocking each other?

The answer: **Task Radar** — a visual collaboration board on Miro.com where every technical component is a task node, every interface is a contract, and everyone works in parallel without locks.

## How it works

### 1. Architecture decomposition comes first

Before anyone writes code, the team jointly decomposes the architecture following the Onion Architecture pattern. This produces:

- **Layer boundaries** (domain / application / infrastructure) with strict dependency rules
- **Interface definitions** for every component (traits in Scala, illustrated as boxes on Miro)
- **Artifact inventory** — a complete list of every file that needs to exist, organized by layer and component

This decomposition is done collaboratively on Miro. The result is a visual "radar screen" where every node is a task waiting to be claimed.

### 2. The radar as a live task board

On the Miro board, each artifact is a card with:
- **Name and type** (e.g., "DepositRule — InvariantRule")
- **Status** (Not Started / In Progress / Done / Blocked)
- **Owner** (who is working on it)
- **Interface contract** (the trait signature it must implement)
- **Dependencies** (which other artifacts it depends on)

The board is **live**. Everyone sees the same state in real time. When someone finishes a task, they update the card. When someone starts a task, they claim it. No one needs to ask "who's working on what?"

### 3. Autonomous task claiming

There is no task assignment meeting. No manager distributes work. Developers autonomously:
- Scan the radar for "Not Started" tasks
- Pick one that matches their skills or interests
- Mark it as "In Progress" with their name
- When done, mark it "Done" and pick the next one

This works because the **interfaces are already defined**. When you pick up `DepositRule`, you know exactly what signature it needs (`(State, Money) => Either[Error, List[Event]]`). You don't need to ask anyone what the function should look like. The interface is the specification.

### 4. Parallelism without locks

Why does this work without merge conflicts or coordination overhead?

- **Different layers = different files.** The person writing `DepositRule` (domain) and the person writing `CreditBalanceBootstrap` (infrastructure) never touch the same file.
- **Interfaces decouple implementation.** The person implementing `CreditBalanceRepositoryImpl` doesn't need the person writing `MoneyTransferService` to be finished — they only need the `CreditBalanceRepository` trait to be defined.
- **No shared state between tasks.** Each artifact is a standalone unit with clear inputs and outputs. The only shared understanding is the interface.

The architecture itself acts as the **collaboration protocol**. The code structure is the team's communication medium.

## What made it possible

### Onion Architecture as prerequisite

Task Radar doesn't work with a flat architecture. If every file can import every other file, tasks are not independent. The strict layer boundaries in Onion Architecture create **natural task isolation**:

- Domain tasks depend on nothing (pure functions, zero imports from other layers)
- Application tasks depend only on domain interfaces
- Infrastructure tasks depend on application interfaces

This dependency graph is a **partial order** — it tells you what can be parallelized and what must be sequential. Most tasks are parallelizable because they sit at the same level of the dependency tree.

### Interface-first design

Every component exposes a trait before it has an implementation. This is not a coding preference — it's a **collaboration prerequisite**. The trait is the contract between teammates. Once the interface is stable, implementation can happen in parallel by anyone.

### Visual real-time board

Miro provides the shared visibility that replaces status meetings. The board answers three questions that normally require synchronous communication:
1. What needs to be done? (all Not Started cards)
2. Who is doing what? (all In Progress cards with owners)
3. What's blocked? (all Blocked cards with dependency markers)

## Lessons learned

### When it works best

- **The architecture is stable.** Major refactors of interfaces break the parallelism model.
- **Interfaces are well-defined.** Ambiguous signatures lead to rework when implementations don't match.
- **The team understands the domain.** Task Radar doesn't teach you the domain — it helps you build it efficiently once you understand it.

### When it breaks down

- **Undiscovered dependencies.** If artifact B secretly needs artifact A's implementation detail (not just its interface), the person working on B gets blocked.
- **Interface churn.** If the trait definition keeps changing, everyone implementing against it has to redo work.
- **Domain discovery during implementation.** When you realize during coding that the domain model is wrong, the radar needs to be updated before work continues. This requires the whole team to re-sync.

### The meta-lesson

The architecture **is** the team's coordination mechanism. A well-designed architecture enables parallel work; a poorly designed one forces sequential work. The Task Radar makes this visible — if the board has lots of Blocked cards, the architecture is the problem, not the team.

## From human teams to agent teams

The same principles apply to AI-agent parallel development:

- **Interface = agent task specification.** A trait with a clear signature is a prompt for an agent to implement it.
- **Radar = agent dashboard.** Each card becomes a task an agent can claim, work on, and report status for.
- **Architecture = coordination protocol.** Agents don't need to communicate with each other if the architecture defines clear boundaries and interfaces.

See [Agent Parallel Development](./agent-parallel-dev.md) for the full vision.
