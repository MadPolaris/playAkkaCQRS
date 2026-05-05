# Methodology Docs

> The "why" and "how" behind the architecture — not what the rules are, but why they exist.

If you're looking for **coding rules** (what to do / what not to do), see [`CLAUDE.md`](../../CLAUDE.md).
If you're looking for **architecture definitions** (what each layer/artifact is), see [`knowledge_base/architecture/`](../architecture/).
If you're looking for **artifact templates** (what each file should look like), see [`knowledge_base/artifacts/`](../artifacts/).

---

## Documents

| Document | Problem it addresses |
|----------|---------------------|
| [Harness Engineering](./harness-engineering.md) | Why is the documentation structured this way? What is the philosophy behind CLAUDE.md + knowledge_base/? |
| [Architecture Best Practices](./architecture-best-practices.md) | Why Onion Architecture? Why interfaces everywhere? Why tiny files? Why FP and DSL patterns? |
| [Task Radar](./task-radar.md) | How did 10+ developers collaborate in parallel without locks? What made that possible? |
| [Agent Parallel Development](./agent-parallel-dev.md) | What does the future look like? How do Task Radar + AI Agents combine into a new development model? |

## How to read

- **New to the project?** Start with `architecture-best-practices.md` — it explains the motivation behind every design decision you'll encounter in the code.
- **Curious about the team workflow?** Read `task-radar.md` — it tells the story of how this architecture enabled a team to work without friction.
- **Building an AI-assisted project?** Read `harness-engineering.md` — it explains how to structure a codebase so AI agents can navigate it effectively.
- **Thinking about the future?** Read `agent-parallel-dev.md` — it maps the path from human teams to human-agent hybrid teams.

## Principle

Every document here follows one rule: **one document, one question.** No document tries to answer "what is X" — the architecture and artifact docs already do that. These documents answer "why X" and "how to think about X."
