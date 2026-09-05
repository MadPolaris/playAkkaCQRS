package net.imadz.monarch

import scala.concurrent.{ExecutionContext, Future}

/** Host-provided knowledge about stages — naming plus lifecycle observation.
  *
  * The engine is policy-free: it walks a queue of opaque `Stage` values and reports
  * progress here. The host typically persists (journal) or publishes (WebSocket) these
  * events; the naming function defines the *cursor* vocabulary that recovery skip logic
  * is expressed in.
  */
trait LifecycleHooks[Stage, State] {

  /** Stable, human-readable name for a stage variant. Repeated variants get a
    * queue-position suffix from the engine, so cursors stay unique. */
  def stageName(stage: Stage): String

  /** Fired before a stage body starts. */
  def onStageStart(cursor: String): Unit = ()

  /** Fired after a stage body completed cleanly and its post-state was accepted.
    * `state` is the stage's post-state (journal it if you resume by count). */
  def onStageComplete(cursor: String, state: State, metadata: Map[String, String] = Map.empty): Unit = ()

  /** Fired when a stage failed and the failure reached the interceptor (or failed the run). */
  def onStageFailed(cursor: String, error: StageError): Unit = ()

  /** Fired when the failure interceptor returned a recovery state — the run continues. */
  def onStageResolved(cursor: String, error: StageError, state: State): Unit = ()
}

/** The engine's one mandatory dependency on the host: how to execute a single stage.
  * Everything else (names, hooks, failure policy, staleness) is optional configuration. */
trait StageInterpreter[Stage, State] {
  def run(stage: Stage, state: State)(implicit ec: ExecutionContext): Future[State]
}

/** Business policy for a failed stage — the Fab demo's OCAP evaluate/resolve is one
  * instance. Return the state the run should continue from; the remaining queue is
  * executed against it. Absent an interceptor, any failure fails the run. */
trait FailureInterceptor[Stage, State] {
  def intercept(cursor: String, error: StageError, state: State)(implicit ec: ExecutionContext): Future[State]
}
