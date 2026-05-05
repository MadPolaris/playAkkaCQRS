# Harness Engineering: Documentation as System of Record

## The core insight

When AI agents write most of the code, the documentation isn't for humans to read — it's for agents to reason from. The codebase becomes the **system of record** for all knowledge about the project.

If an agent can't discover it from the repo, it doesn't exist.

This means: Slack discussions, meeting notes, Google Docs, and tacit human knowledge are **invisible** to the agent. Only version-controlled files in the repo are real.

## The three-layer knowledge architecture

This project organizes knowledge into three layers with distinct purposes:

```
Layer 1: CLAUDE.md           →  Rules (the "map")
Layer 2: knowledge_base/      →  Reference (the "terrain")
Layer 3: knowledge_base/prompts/ →  Instructions (the "mission brief")
```

### Layer 1: Rules — `CLAUDE.md` (~70 lines)

The **map**, always loaded into context. It tells the agent:

- Tech stack and versions (so it doesn't guess wrong)
- Architecture invariant (inner layers never import outer layers)
- Coding conventions (Either for errors, no null, immutable data)
- Protobuf "three-piece" rule (must keep three files in sync)
- TDD pitfalls (`.thenStop()` can't use `runCommand`)
- Directory map (where to find things)

**Principle:** Short enough to always fit in context. Acts as a table of contents, not an encyclopedia. When the agent needs detail, it follows the pointer.

### Layer 2: Reference — `knowledge_base/`

The **terrain**, loaded on demand. Split into:

- `architecture/` — What each layer is, what it contains, what it can import
- `artifacts/` — 30 files, each defining one code artifact type with file path pattern, constraints, and reference template
- `saga_framework/` — Saga engine documentation with usage guides and architecture diagrams

**Principle:** Progressive disclosure. The agent starts from CLAUDE.md and follows links to the relevant reference file. It reads 1 file, not 40.

### Layer 3: Instructions — `knowledge_base/prompts/`

The **mission brief**. Pre-written prompts that tell the agent exactly what to produce for each artifact type. These are structured so the agent receives: context, constraints, expected output format.

**Principle:** Reusable. Once a prompt works, it works every time for that artifact type.

## Map, not encyclopedia

The anti-pattern is one massive AGENTS.md file. Why it fails:

1. **Context is scarce.** A giant instruction file crowds out code, tasks, and docs.
2. **Everything important = nothing important.** The agent pattern-matches locally instead of navigating consciously.
3. **It rots immediately.** A monolithic manual becomes a graveyard of stale rules.
4. **It's unverifiable.** A single blob can't be mechanically checked for freshness, ownership, or cross-links.

Instead: `CLAUDE.md` points to deeper sources. Each reference file has one clear responsibility. Cross-links connect related topics.

## Enforcing freshness

Documentation decays unless it's verified. The ideal state (not yet fully implemented in this project):

- Linter rules that check cross-links are valid
- CI jobs that verify architecture constraints (no domain→infra imports)
- A scheduled "doc-gardening" agent that scans for stale docs and opens fix PRs

Without mechanical enforcement, documentation drifts from code. With it, the system stays self-consistent.

## Reference: Harness Engineering principles

These principles are adapted from OpenAI's Codex-driven development experiments:

1. **Codebase is the system of record.** Knowledge outside the repo doesn't exist for agents.
2. **Progressive disclosure.** Start with a map, not an encyclopedia. Point to detail, don't embed it.
3. **Mechanical enforcement.** Rules in markdown are wishes. Rules in linters/CI are facts.
4. **Agents need constraints, not instructions.** Broad boundaries + local autonomy beats micromanagement.
5. **Garbage-collect regularly.** Bad patterns spread. Clean them early and often.
