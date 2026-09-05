# monarch-core

**Monarch** — a resumable stage-queue execution engine, named for the monarch butterfly:

| Butterfly | Engine |
|---|---|
| Complete metamorphosis: egg → larva → pupa → adult | A run is an **open queue of discrete stages** (`initialize` / `injectHead` / `appendTail`) |
| **Diapause** — development suspends at a checkpoint and resumes months later, exactly where it stopped | **Cursor-based recovery** (`resumeFromIndex` / `resume`) — crash, replay the journal, continue from the cursor |
| Migration completed **across generations** — the route outlives any individual | **Generation tokens** (`RunRegistry`) — a superseded run dies silently at its next stage boundary; a fresh one continues from the journal |

Zero framework dependencies: everything is `scala.concurrent.Future` plus host callbacks, so the same engine runs inside an Akka `EventSourcedBehavior` wrapper, a Play controller, or a plain test. Published to Maven Central as `net.imadz:monarch-core` (see [Publishing](#6-publishing-maven-central)).

English | [中文](README-zh.md)

Other guides: [ChainDsl Guide](../docs/CHAINDSL_GUIDE.md) — the two hosts below documented at the library level.

---

## 1. The engine contract

Monarch is policy-free. It walks a queue of **opaque `Stage` values** through an **interpreter you provide**, and reports progress through **hooks**. You supply four things:

```scala
import net.imadz.monarch._

val engine = new Monarch[MyStage, MyState](
  // 1. The ONLY mandatory host code: how to execute one stage.
  interpreter = new StageInterpreter[MyStage, MyState] {
    def run(stage: MyStage, state: MyState)(implicit ec: ExecutionContext): Future[MyState] = ...
  },
  // 2. Naming + observation. stageName defines the CURSOR VOCABULARY that recovery
  //    skip logic is expressed in; the callbacks are where the host journals progress.
  hooks = new LifecycleHooks[MyStage, MyState] {
    def stageName(stage: MyStage): String = ...
    override def onStageStart(cursor: String): Unit = ...
    override def onStageComplete(cursor: String, state: MyState, metadata: Map[String, String]): Unit = ...
    override def onStageFailed(cursor: String, error: StageError): Unit = ...
    override def onStageResolved(cursor: String, error: StageError, state: MyState): Unit = ...
  },
  // 3. Optional business resolution of failures (the Fab demo: OCAP evaluate/resolve).
  failureInterceptor = None,
  // 4. Generation staleness guard (RunRegistry-backed).
  runToken = () => RunRegistry.isFresh(runKey, myGeneration)
)

engine.initialize(Seq(stageA, stageB, stageC))  // set the queue
engine.process(initialState)                     // Future[MyState]
engine.injectHead(Seq(emergencyStage))           // runtime weaving
engine.resumeFromIndex(replayedState, done = 9)  // crash recovery
```

Mechanics you get for free:

| Mechanism | What it does |
|---|---|
| **Cursor** | Every queued entry gets `"<stageName>#<position>"` — stable, unique, human-readable. Recovery skips by count (`resumeFromIndex`) or by cursor names (`resume`). |
| **Guard-first staleness** | Every stage boundary re-checks `runToken()` *before* anything else. A superseded run fails with `StaleRun` and never touches hooks or the interceptor. |
| **Classified failures** | A stage body throws `StageFailedException(StageError(...))` for a *classified* business failure; any other `NonFatal` is wrapped as `UNEXPECTED`. Both go to the `failureInterceptor`; without one they fail the run. A failure is handled **exactly once, at the failing stage's frame** — never re-handled by outer queue frames. |
| **Open queue** | `injectHead` / `appendTail` weave stages into a running plan (an OCAP branch, a rework loop). |

A resolved failure is **not** a completion: after the interceptor returns a recovery state the run continues with the remaining queue, but only `onStageResolved` fires — the host decides what a resolved failure means downstream.

---

## 2. Modeling: from a business flow to a Monarch host

Five decisions turn any "hand a batch through an external system" flow into a Monarch host. Both real hosts in this repo — `BankChain` (recharge/purchase, dag-engine-core) and the Fab pipeline (app layer, M3.5 demo) — followed exactly this path.

### Step 1 — Stage ADT: one case per *meaningful* step

A stage deserves to exist if **any** of these is true: a human can see it start/finish (UI, ticket), the journal must record it individually (recovery boundary, audit), or it has a distinct failure policy.

- Bank chain: `FileGen, Upload, WaitAck, Poll, Parse, Classify` — six file-exchange steps.
- Fab: seventeen — `LoadFoup, Transport(from, to), AtEquipment(area, equipId), TrackIn, RunRecipe, Measure, M35ClassifyWithOcap(rules), OcapActionRouter, AwaitSubLotResult(lotKey), ...` — parameterized cases because the *same* variant recurs with different arguments.

```scala
sealed trait PipelineStage
case object LoadFoup extends PipelineStage
case class Transport(from: String, to: String) extends PipelineStage
case class Measure(equipId: String) extends PipelineStage
case class M35ClassifyWithOcap(rules: List[OcapRuleDefinition]) extends PipelineStage
```

Rule of thumb: if you can't say what a stage's *failure* means, it isn't a stage yet — it's an implementation detail inside one.

### Step 2 — State: one case class, Option slots

Monarch threads **one `State` value** through the queue. Collapse the flow's heterogeneous intermediates into a single case class where every slot is an `Option` until its stage writes it:

```scala
// BankChain: the old for-comprehension had GeneratedFile, UploadReceipt,
// AckResult, ResponseFile, Seq[RawResult] as local vals — now slots:
final case class BankChainState[Item, Raw](
    batchId: String, chainId: String, items: Seq[Item],
    context: Map[String, Any] = Map.empty,
    generatedFile: Option[GeneratedFile] = None,
    receipt: Option[UploadReceipt] = None,
    ack: Option[AckResult] = None,
    responseFile: Option[ResponseFile] = None,
    rawResults: Option[Seq[Raw]] = None,
    classifications: Option[Seq[Classification[Item]]] = None,
    lastStage: Option[BankStage] = None          // ← for metadata derivation
)
```

Chain-order rule: **every stage's input slot must be populated by the time it runs** (FileGen writes `generatedFile`, Upload reads it, ...). This is what makes mid-chain resume possible — see Step 5.

The Fab host already had a living domain state (`FabDemoState`: wafers, lot positions, OCAP actions) — it was adopted as-is. If your flow has a domain model, *that* is your State; don't invent a parallel one.

### Step 3 — Cursor vocabulary: stable, readable, journaled

`stageName` is the contract between the running system and its journal. Rules:

1. **Human-readable** — it appears in logs, the UI, and incident reports (`"RunRecipe_LITHO-01_LITHO-28-001#4"` tells you everything).
2. **Stable across restarts** — derived from the stage case + its parameters, never from a random id.
3. **Keep legacy strings when migrating** — BankChain emits the byte-identical `"file-gen" / "upload" / "wait-ack" / ...` the old processor used, so journaled events replay unchanged.

### Step 4 — Failure taxonomy and the interceptor

Split failures into two classes up front:

| Class | How it's thrown | Who handles it |
|---|---|---|
| **Classified** (business) | `throw StageFailedException(StageError(cursor, Some("ACK_TIMEOUT"), "ACK_TIMEOUT", "..."))` | `failureInterceptor` if configured, else run fails |
| **Unexpected** (defect / infra) | any `NonFatal` — auto-wrapped as `StageError(cursor, None, "UNEXPECTED", ...)` | same path; a good interceptor usually re-routes to manual handling |

Configure an interceptor only if your business has a *resolution policy* for failed stages. The Fab demo's OCAP is the canonical one: evaluate rules against the failure, rework / scrap / hold the wafers, return the state the queue continues from. The bank chains ship **without** one — their failures legitimately terminate the run.

### Step 5 — Host integration: journal, resume, generations

Monarch is a Future queue — it persists nothing. The host wraps it in an event-sourced actor and wires three things:

1. **Journal the hooks.** Map `onStageStart/Complete/Failed/Resolved` to your event protocol. Carry the post-state in the completion event — recovery needs the intermediate values.
2. **Resume from the snapshot.** On recovery, rebuild State from the last completion event's snapshot and call `resumeFromIndex(state, completedCount)`. Count-based skipping needs no cursor matching; name-based `resume(Set(cursors))` is there when the journal stores cursors.
3. **Guard with generations.** `RunRegistry.register(key)` on *both* start and recovery; capture the token into `runToken`; re-check it in every hook before sending anything to self. This kills the double-pipeline race where a pre-crash Future chain journals onto the restarted entity.

---

## 3. Case study 1 — `BankChain`: the recharge chain (dag-engine-core)

**Before** — six heterogeneous intermediates hard-wired in a for-comprehension:

```scala
// SubBatchProcessor.process — order, count, and shape were frozen
for {
  generatedFile   <- pipeline.fileGen.generate(items, ctx)
  receipt         <- pipeline.upload.upload(generatedFile, ctx)
  ack             <- pipeline.waitAck.waitForAck(receipt, ctx)
  pollResult      <- pipeline.pollResp.poll(ctx)
  rawResults      <- pipeline.parse.parse(responseFile, ctx)
  classifications <- pipeline.classify.classify(rawResults, items)
} yield SubBatchResult(...)
```

**After** — the stage ADT + state (Steps 1–2) and a ~40-line interpreter:

```scala
def runStage(stage: BankStage, state: BankChainState[Item, Raw], pipeline: SubBatchPipeline[Item, Raw])
            (implicit ec: ExecutionContext): Future[BankChainState[Item, Raw]] = stage match {
  case BankStage.FileGen =>
    pipeline.fileGen.generate(state.items, state.context)
      .map(f => state.copy(generatedFile = Some(f), lastStage = Some(stage)))
  case BankStage.WaitAck =>
    state.receipt.fold(Future.failed(missing("wait-ack", "receipt"))) { r =>
      pipeline.waitAck.waitForAck(r, state.context).map {
        case AckReceived         => state.copy(ack = Some(AckReceived), lastStage = Some(stage))
        case AckTimeout(ms)      => fail("ACK_TIMEOUT", s"External system ack timeout after ${ms}ms")
        case AckRejected(reason) => fail("ACK_REJECTED", s"External system rejected: $reason")
      }
    }
  // Poll / Parse / Classify follow the same shape
}
```

**The wrapper** (`ChainExecutionActor`, event-sourced) — the Step 5 pattern end to end:

```scala
// StartExecution — register a generation, run from stage 0
val generation = RunRegistry.register(s"$chainId-$batchId")
runBatch(batchId, items, skip = 0, snapshot = None, runToken = ...)

// RecoveryCompleted — new generation, resume from the journaled snapshot
val generation = RunRegistry.register(key)                     // old chain dies at its next boundary
itemLoader(state.batchId).onComplete { items =>
  runBatch(batchId, items, skip = state.completedPhases.size,  // cursor count from replay
    snapshot = state.lastState, runToken = ...)                // PhaseDone carries the snapshot
}
```

`PhaseDone(phase, ts, metadata, snapshot)` stores each stage's post-state; `BankChain.metadataOf(state)` derives the same metadata keys the old processor emitted (`localPath/fileName/byteSize/...`), so journals stay human-auditable. Acceptance specs: `dag-engine-core/src/test/.../BankChainSpec.scala`.

---

## 4. Case study 2 — the Fab pipeline (M3.5 demo, app layer)

The Fab flow is richer: seventeen stage variants (including OCAP evaluation and a rework sub-lot saga), a pre-existing domain state, and a business failure-resolution policy. Monarch still only needs the four extension points — everything Fab-specific lives in one adapter.

**Stage ADT & State** (Steps 1–2): `FabScenarioPipeline.PipelineStage` (seventeen cases) and `FabDemoState` — the domain already modeled them; no new types were invented.

**The adapter** (`app/.../FabPipelineProcessor.scala` — the whole file is an adapter):

```scala
new Monarch[PipelineStage, FabDemoState](
  interpreter = stage =>
    FabScenarioPipeline.runStage(stage, state, ctx).recoverWith {
      // Translate the FAB failure type to the engine's, preserving classification:
      case FabStageFailedException(err) =>
        Future.failed(MonarchStageFailedException(
          MonarchStageError(err.stageName, err.equipId, err.errorCode, err.detail)))
    },
  hooks = new LifecycleHooks[PipelineStage, FabDemoState] {
    def stageName(stage: PipelineStage): String = FabPipelineProcessor.stageName(stage)
    // journal callbacks → actor commands → journaled events
    override def onStageStart(cursor: String) = if (runToken()) ctx.self ! PhaseStarting(cursor)
    override def onStageComplete(cursor: String, state: FabDemoState, _) =
      if (runToken()) ctx.self ! PhaseCompleted(cursor, Map.empty, Some(state))
    override def onStageFailed(cursor: String, error: StageError) =
      if (runToken()) ctx.self ! PhaseFailed(cursor, toFabError(error))
    override def onStageResolved(cursor: String, error: StageError, state: FabDemoState) =
      if (runToken()) ctx.self ! OcapResolved(cursor, toFabError(error), state)
  },
  failureInterceptor = Some((cursor, error, state) =>
    FabScenarioPipeline.invokeOcapInterceptor(state, ctx, toFabError(error))),  // ← OCAP lives here
  runToken = ctx.runToken)
```

Two adapter-only concerns worth noticing: **exception translation** (stage bodies throw the Fab failure type; the engine must see its own, otherwise a classified failure degrades to `UNEXPECTED`) and **the four journal protocols** (`PhaseStarting/PhaseCompleted/PhaseFailed/OcapResolved`) — the journal schema and the WebSocket UI did not change at all during the migration.

**Crash recovery, from the live demo log** — crash injected inside `Measure_CDSEM-01#9`, sharding kills the actor, restart after backoff:

```
20:48:36  >>> STAGE START: Measure_CDSEM-01#9
20:48:38  Crash injected, stopping actor
20:48:48  >>> STAGE START: Measure_CDSEM-01#9   ← same cursor, from resumeFromIndex
20:48:53  <<< STAGE DONE: Measure_CDSEM-01#9
          ... TrackOut#10 → Classify#11 → OCAP#12/#13 → AwaitSubLotResult_rwk#14 (rework saga)
20:49:14  <<< STAGE DONE: SealComplete#16       → AllCompleted
```

**What the engine deliberately does NOT do**: journal persistence, saga coordination, equipment simulators, WebSocket publishing, OCAP rules — all host responsibilities. Monarch only guarantees the *control flow* is correct: right stage order, right resume point, exactly one failure handler per failure, no zombie chains.

---

## 5. Decision guide

| Question | Answer |
|---|---|
| Fixed six steps vs open queue? | If the step list can ever change at runtime (OCAP injection, rework loops), you need the queue. Both hosts use fixed `initialize` today; the queue API is there for when they don't stay fixed. |
| Interceptor or fail-fast? | Fail-fast unless a *business policy* exists for continuing after a failure. Interceptor failures themselves fail the run — keep resolution logic idempotent and total. |
| `resumeFromIndex` or `resume`? | Count-based when your completion events carry state snapshots — simplest, cursor-format agnostic. Name-based when the journal stores cursors but not states. |
| Where do generations come from? | `RunRegistry.register` on **every** new run *and* on recovery; the token goes into `runToken` and is re-checked inside hooks at send time. Registry is per-JVM; cross-node moves need a cluster-wide signal. |
| How do I test a host? | Monarch is a pure Future queue: stub the interpreter, record hooks into a `ListBuffer`, assert exact event sequences — see `monarchCore/src/test/.../MonarchEngineSpec.scala` (15 specs) and `dag-engine-core/.../BankChainSpec.scala` (6 specs). |

---

## 6. Publishing (Maven Central)

Only `monarch-core` is published (`net.imadz:monarch-core`); all other modules set `publish/skip`. Releases are tag-driven via the existing `publish.yml` workflow: push a tag `v*` and `sbt ci-release` signs and uploads to the Central Portal (`central.sonatype.com`). Version comes from the tag via sbt-dynver (e.g. tag `v0.1.0` → `0.1.0`). Required GitHub repo secrets: `OSSRH_USERNAME` / `OSSRH_PASSWORD` (Central Portal token), `PGP_SECRET`, `PGP_PASSPHRASE`.
