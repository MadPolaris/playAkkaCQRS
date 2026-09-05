package net.imadz.monarch

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** Monarch — a resumable stage-queue execution engine.
  *
  * Named for the monarch butterfly: a life lived in *discrete stages* (egg → larva →
  * pupa → adult = the stage queue), *diapause* — suspending development at a checkpoint
  * and resuming it months later exactly where it stopped (= cursor-based crash recovery),
  * and a *migration completed across generations* — the route's cursor outlives any
  * individual runner (= a superseded run dies, a fresh one continues from the journal).
  *
  * Mechanics (all proven in production Fab use before this generalization):
  *   - `entries: Vector[(Stage, position)]` — an open queue, not a fixed shape. Stages can
  *     be woven in at runtime (`injectHead`/`appendTail`), e.g. an OCAP branch.
  *   - Every stage gets a stable cursor `"<name>#<position>"`; recovery skips by cursor
  *     count (`resumeFromIndex`) or by cursor names (`resume`).
  *   - Every stage boundary re-checks the generation token ([[RunRegistry.isFresh]]): a
  *     superseded run dies silently with [[StaleRun]] instead of racing the new run —
  *     guard-first, before any interceptor can touch it.
  *   - A classified [[StageFailedException]] — or any NonFatal, wrapped as UNEXPECTED —
  *     is offered to the optional [[FailureInterceptor]]; its returned state becomes the
  *     continuation state. Without an interceptor, the failure fails the run.
  *
  * Zero framework dependencies: everything is `Future` + host callbacks, so the engine
  * runs the same inside an EventSourcedBehavior wrapper, a Play controller, or a test.
  *
  * (This is the generalization of the Fab demo's `FabPipelineProcessor`; see
  * docs/CHAINDSL_GUIDE.md for the three-generation story.)
  */
class Monarch[Stage, State](
    interpreter: StageInterpreter[Stage, State],
    hooks: LifecycleHooks[Stage, State],
    failureInterceptor: Option[FailureInterceptor[Stage, State]] = None,
    runToken: () => Boolean = () => true
) {

  /** Queue entries carry a position so repeated stage variants get unique cursors. */
  private var entries: Vector[(Stage, Int)] = Vector.empty
  private var nextPos: Int = 0

  /** Initialise the queue with a full stage list (clears any previous content). */
  def initialize(stages: Seq[Stage]): Unit = {
    entries = stages.zipWithIndex.map { case (s, i) => (s, nextPos + i) }.toVector
    nextPos += stages.size
  }

  /** Weave stages in at the head (runtime branches, OCAP injections). */
  def injectHead(stages: Seq[Stage]): Unit = {
    entries = stages.zipWithIndex.map { case (s, i) => (s, nextPos + i) }.toVector ++ entries
    nextPos += stages.size
  }

  /** Append stages to the tail. */
  def appendTail(stages: Seq[Stage]): Unit = {
    entries = entries ++ stages.zipWithIndex.map { case (s, i) => (s, nextPos + i) }.toVector
    nextPos += stages.size
  }

  /** Current queue size. */
  def pendingCount: Int = entries.size

  /** Stable cursor for a queued stage: `"<stageName>#<position>"`. */
  def cursorOf(stage: Stage, pos: Int): String = s"${hooks.stageName(stage)}#$pos"

  /** Execute the whole queue from `initial`. */
  def process(initial: State)(implicit ec: ExecutionContext): Future[State] =
    executeQueue(entries, initial)

  /** Resume, skipping the completed prefix identified by cursor names. */
  def resume(state: State, completedCursors: Set[String])(implicit ec: ExecutionContext): Future[State] =
    executeQueue(entries.dropWhile { case (stage, pos) => completedCursors.contains(cursorOf(stage, pos)) }, state)

  /** Resume, skipping the first `completedCount` entries (journal replay count). */
  def resumeFromIndex(state: State, completedCount: Int)(implicit ec: ExecutionContext): Future[State] =
    executeQueue(entries.drop(completedCount), state)

  // ====================================================================
  // Internal
  // ====================================================================

  private def executeQueue(remaining: Vector[(Stage, Int)], state: State)(implicit ec: ExecutionContext): Future[State] =
    remaining match {
      case v if v.isEmpty =>
        Future.successful(state)
      case (stage, pos) +: tail =>
        // A superseded run terminates silently — no interceptor, no events, no side effects.
        if (!runToken()) Future.failed(StaleRun)
        else {
          val cursor = cursorOf(stage, pos)
          hooks.onStageStart(cursor)
          // transformWith wraps ONLY this stage body: a failure is handled exactly once,
          // here — never re-handled by outer queue frames with the wrong cursor.
          interpreter.run(stage, state).transformWith {
            case Success(nextState) =>
              hooks.onStageComplete(cursor, nextState)
              executeQueue(tail, nextState)
            case Failure(e) if !runToken() =>
              // Guard-first: staleness wins over any failure policy.
              Future.failed(e)
            case Failure(StageFailedException(err)) =>
              handleFailedThenContinue(cursor, err, state, tail)
            case Failure(NonFatal(ex)) =>
              handleFailedThenContinue(cursor, StageError(cursor, None, "UNEXPECTED",
                Option(ex.getMessage).getOrElse(ex.toString)), state, tail)
            case Failure(e) =>
              Future.failed(e)
          }
        }
    }

  /** Failure policy: journal the failure, then either continue from the interceptor's
    * recovered state or (no interceptor) fail the run. */
  private def handleFailedThenContinue(cursor: String, error: StageError, state: State, tail: Vector[(Stage, Int)])(implicit ec: ExecutionContext): Future[State] = {
    hooks.onStageFailed(cursor, error)
    failureInterceptor match {
      case Some(interceptor) =>
        interceptor.intercept(cursor, error, state).flatMap { recoveredState =>
          hooks.onStageResolved(cursor, error, recoveredState)
          // NOTE: no onStageComplete here — a resolved failure is not a clean completion.
          executeQueue(tail, recoveredState)
        }
      case None =>
        Future.failed(StageFailedException(error))
    }
  }
}

object Monarch {

  /** Convenience factory: hook functions instead of the trait (multi-method traits are not
    * SAM-convertible, so this takes them explicitly). */
  def apply[Stage, State](
      interpreter: (Stage, State) => Future[State],
      nameOf: Stage => String,
      onStart: String => Unit = _ => (),
      onComplete: (String, State) => Unit = (_: String, _: State) => (),
      onFailed: (String, StageError) => Unit = (_, _) => (),
      onResolved: (String, StageError, State) => Unit = (_: String, _: StageError, _: State) => (),
      failureInterceptor: Option[(String, StageError, State) => Future[State]] = None,
      runToken: () => Boolean = () => true
  ): Monarch[Stage, State] =
    new Monarch[Stage, State](
      interpreter = new StageInterpreter[Stage, State] {
        override def run(stage: Stage, state: State)(implicit ec: ExecutionContext): Future[State] =
          interpreter(stage, state)
      },
      hooks = new LifecycleHooks[Stage, State] {
        override def stageName(stage: Stage): String = nameOf(stage)
        override def onStageStart(cursor: String): Unit = onStart(cursor)
        override def onStageComplete(cursor: String, state: State, metadata: Map[String, String]): Unit = onComplete(cursor, state)
        override def onStageFailed(cursor: String, error: StageError): Unit = onFailed(cursor, error)
        override def onStageResolved(cursor: String, error: StageError, state: State): Unit = onResolved(cursor, error, state)
      },
      failureInterceptor = failureInterceptor.map { fi =>
        new FailureInterceptor[Stage, State] {
          override def intercept(cursor: String, error: StageError, state: State)(implicit ec: ExecutionContext): Future[State] =
            fi(cursor, error, state)
        }
      },
      runToken = runToken
    )
}
