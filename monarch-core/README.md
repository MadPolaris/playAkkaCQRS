# monarch-core

[中文](#中文)

**Monarch** is a resumable stage-queue execution engine, named for the monarch butterfly:

| Butterfly | Engine |
|---|---|
| Complete metamorphosis: egg → larva → pupa → adult | A run is an **open queue of discrete stages** (`initialize` / `injectHead` / `appendTail`) |
| **Diapause** — development suspends at a checkpoint and resumes months later, exactly where it stopped | **Cursor-based recovery** (`resumeFromIndex` / `resume`) — crash, replay the journal, continue from the cursor |
| Migration completed **across generations** — the route outlives any individual | **Generation tokens** (`RunRegistry`) — a superseded run dies silently at its next stage boundary; a fresh one continues from the journal |

Zero framework dependencies: everything is `scala.concurrent.Future` plus host callbacks, so the same engine runs inside an Akka `EventSourcedBehavior` wrapper, a Play controller, or a plain test.

```scala
import net.imadz.monarch._
import scala.concurrent.duration._

val m = Monarch[MyStage, MyState](
  interpreter = MyStages.run,               // (Stage, State) => Future[State]
  nameOf = MyStages.name,                   // stable cursor names: "<name>#<position>"
  onStageComplete = cursor => journal ! StageDone(cursor),
  failureInterceptor = Some((cursor, err, state) => ocap.evaluate(cursor, err, state)),
  runToken = () => RunRegistry.isFresh("wo-1", myGeneration))

m.initialize(MyStages.happyPath)
m.process(initialState)                     // Future[MyState]
m.injectHead(MyStages.ocapBranch)           // runtime weaving
m.resumeFromIndex(replayedState, completed = 9)
```

Failure policy: a stage body throws `StageFailedException(StageError(...))` for a *classified* business failure; any other `NonFatal` is wrapped as `UNEXPECTED`. Both go to the `failureInterceptor` (the Fab demo's OCAP evaluate/resolve is one instance); without one, they fail the run. Staleness always wins — checked first at every boundary.

This module is the generalization of the Fab demo's `FabPipelineProcessor`; see [docs/CHAINDSL_GUIDE.md](../docs/CHAINDSL_GUIDE.md) for the three-generation story. The Fab port and dag-engine-core's `ChainExecutionActor` (via `BankChain`) are both hosted on this engine.

## Publishing (Maven Central)

Only `monarch-core` is published (`net.imadz:monarch-core`); all other modules set `publish/skip`. Releases are tag-driven via the existing `publish.yml` workflow: push a tag `v*` and `sbt ci-release` signs and uploads to the Central Portal (`central.sonatype.com`). Version comes from the tag via sbt-dynver (e.g. tag `v0.1.0` → `0.1.0`). Required GitHub repo secrets: `OSSRH_USERNAME` / `OSSRH_PASSWORD` (Central Portal token), `PGP_SECRET`, `PGP_PASSPHRASE`.

---

## 中文

**Monarch（帝王斑蝶）**——可断点续跑的阶段队列执行引擎，以帝王斑蝶命名：

| 斑蝶 | 引擎 |
|---|---|
| 完全变态：卵 → 幼虫 → 蛹 → 成虫 | 一次运行 = **开放阶段队列**（`initialize` / `injectHead` / `appendTail`） |
| **滞育**——发育在检查点暂停数月，之后从暂停处精确续跑 | **游标恢复**（`resumeFromIndex` / `resume`）——崩溃、回放日志、从游标继续 |
| 迁徙**跨代完成**——路线比任何个体长寿 | **世代号**（`RunRegistry`）——被取代的旧链在下一个阶段边界静默终止，新链从日志续跑 |

零框架依赖：全部是 `scala.concurrent.Future` + 宿主回调，同一引擎可跑在 Akka `EventSourcedBehavior` 包装、Play Controller 或纯测试里。

失败策略：阶段体内抛 `StageFailedException(StageError(...))` 表示**已分类**的业务失败；其他 `NonFatal` 一律包装为 `UNEXPECTED`。两者都交给 `failureInterceptor`（Fab 演示的 OCAP 评估/处置是一个实例）；未配置拦截器时直接判运行失败。过期判定永远优先——每个阶段边界最先检查。

本模块是 Fab 演示 `FabPipelineProcessor` 的泛化；三代演进的故事见 [docs/CHAINDSL_GUIDE.md](../docs/CHAINDSL_GUIDE.md)。Fab 移植版与 dag-engine-core 的 `ChainExecutionActor`（经 `BankChain`）现均寄宿于本引擎。

## 发布（Maven Central）

仅 `monarch-core` 发布（`net.imadz:monarch-core`），其余模块均设 `publish/skip`。发布由标签驱动：推送 `v*` 标签后，既有的 `publish.yml` workflow 会执行 `sbt ci-release` 签名并上传到 Central Portal（`central.sonatype.com`）。版本号由标签经 sbt-dynver 推导（如 tag `v0.1.0` → `0.1.0`）。需配置的 GitHub secrets：`OSSRH_USERNAME` / `OSSRH_PASSWORD`（Central Portal token）、`PGP_SECRET`、`PGP_PASSPHRASE`。
