package net.imadz.monarch

import scala.concurrent.{ExecutionContext, Future}

/** Fluent builder for assembling a generation-guarded [[Monarch]] engine.
  *
  * Eliminates boilerplate and recursive naming bugs in anonymous [[LifecycleHooks]]
  * implementations, providing clean separation between execution, progress
  * observation, and failure/recovery interception.
  *
  * {{{
  * val monarch = Monarch.builder[BankStage, BankChainState[Item, Raw]]
  *   .namingStages(stage => BankStage.stageName(stage))
  *   .withExecution((stage, state) => BankChain.runStage(stage, state, pipeline))
  *   .onFailure((cursor, err, state) => ocapInterceptor(cursor, err, state))
  *   .withGenerationGuard(() => RunRegistry.isFresh(key, gen))
  *   .onStageStart(cursor => observer.onStageStart(cursor))
  *   .onStageCompleteWithMeta((cursor, state, meta) => observer.onStageComplete(cursor, meta, Some(state)))
  *   .build()
  * }}}
  */
final class MonarchBuilder[Stage, State] private (
    private val interpreterOpt: Option[(Stage, State, ExecutionContext) => Future[State]],
    private val nameOfOpt: Option[Stage => String],
    private val stagesToInit: Option[Seq[Stage]],
    private val startHook: String => Unit,
    private val completeHook: (String, State, Map[String, String]) => Unit,
    private val failedHook: (String, StageError) => Unit,
    private val resolvedHook: (String, StageError, State) => Unit,
    private val failureInterceptorOpt: Option[(String, StageError, State, ExecutionContext) => Future[State]],
    private val runTokenFn: () => Boolean
) {

  def namingStages(nameOf: Stage => String): MonarchBuilder[Stage, State] =
    copy(nameOfOpt = Some(nameOf))

  def withStages(stages: Seq[Stage]): MonarchBuilder[Stage, State] =
    copy(stagesToInit = Some(stages))

  def withExecution(interpreter: (Stage, State) => Future[State]): MonarchBuilder[Stage, State] =
    copy(interpreterOpt = Some((st, s, _) => interpreter(st, s)))

  def withExecutionEc(interpreter: (Stage, State, ExecutionContext) => Future[State]): MonarchBuilder[Stage, State] =
    copy(interpreterOpt = Some(interpreter))

  def onStageStart(hook: String => Unit): MonarchBuilder[Stage, State] =
    copy(startHook = hook)

  def onStageComplete(hook: (String, State) => Unit): MonarchBuilder[Stage, State] =
    copy(completeHook = (c, s, _) => hook(c, s))

  def onStageCompleteWithMeta(hook: (String, State, Map[String, String]) => Unit): MonarchBuilder[Stage, State] =
    copy(completeHook = hook)

  def onStageFailed(hook: (String, StageError) => Unit): MonarchBuilder[Stage, State] =
    copy(failedHook = hook)

  def onStageResolved(hook: (String, StageError, State) => Unit): MonarchBuilder[Stage, State] =
    copy(resolvedHook = hook)

  def onFailure(interceptor: (String, StageError, State) => Future[State]): MonarchBuilder[Stage, State] =
    copy(failureInterceptorOpt = Some((c, e, s, _) => interceptor(c, e, s)))

  def onFailureEc(interceptor: (String, StageError, State, ExecutionContext) => Future[State]): MonarchBuilder[Stage, State] =
    copy(failureInterceptorOpt = Some(interceptor))

  def withGenerationGuard(runToken: () => Boolean): MonarchBuilder[Stage, State] =
    copy(runTokenFn = runToken)

  def build(): Monarch[Stage, State] = {
    val interpFn = interpreterOpt.getOrElse(
      throw new IllegalArgumentException("MonarchBuilder requires an execution interpreter via withExecution")
    )
    val nameFn = nameOfOpt.getOrElse((stage: Stage) => String.valueOf(stage))

    val interpreter = new StageInterpreter[Stage, State] {
      override def run(stage: Stage, state: State)(implicit ec: ExecutionContext): Future[State] =
        interpFn(stage, state, ec)
    }

    val hooks = new LifecycleHooks[Stage, State] {
      override def stageName(stage: Stage): String = nameFn(stage)
      override def onStageStart(cursor: String): Unit = startHook(cursor)
      override def onStageComplete(cursor: String, state: State, metadata: Map[String, String]): Unit =
        completeHook(cursor, state, metadata)
      override def onStageFailed(cursor: String, error: StageError): Unit = failedHook(cursor, error)
      override def onStageResolved(cursor: String, error: StageError, state: State): Unit =
        resolvedHook(cursor, error, state)
    }

    val interceptor = failureInterceptorOpt.map { fi =>
      new FailureInterceptor[Stage, State] {
        override def intercept(cursor: String, error: StageError, state: State)(implicit ec: ExecutionContext): Future[State] =
          fi(cursor, error, state, ec)
      }
    }

    val engine = new Monarch[Stage, State](
      interpreter = interpreter,
      hooks = hooks,
      failureInterceptor = interceptor,
      runToken = runTokenFn
    )

    stagesToInit.foreach(engine.initialize)
    engine
  }

  private def copy(
      interpreterOpt: Option[(Stage, State, ExecutionContext) => Future[State]] = this.interpreterOpt,
      nameOfOpt: Option[Stage => String] = this.nameOfOpt,
      stagesToInit: Option[Seq[Stage]] = this.stagesToInit,
      startHook: String => Unit = this.startHook,
      completeHook: (String, State, Map[String, String]) => Unit = this.completeHook,
      failedHook: (String, StageError) => Unit = this.failedHook,
      resolvedHook: (String, StageError, State) => Unit = this.resolvedHook,
      failureInterceptorOpt: Option[(String, StageError, State, ExecutionContext) => Future[State]] = this.failureInterceptorOpt,
      runTokenFn: () => Boolean = this.runTokenFn
  ): MonarchBuilder[Stage, State] =
    new MonarchBuilder(
      interpreterOpt, nameOfOpt, stagesToInit,
      startHook, completeHook, failedHook, resolvedHook,
      failureInterceptorOpt, runTokenFn
    )
}

object MonarchBuilder {
  def empty[Stage, State]: MonarchBuilder[Stage, State] =
    new MonarchBuilder[Stage, State](
      interpreterOpt = None,
      nameOfOpt = None,
      stagesToInit = None,
      startHook = _ => (),
      completeHook = (_, _, _) => (),
      failedHook = (_, _) => (),
      resolvedHook = (_, _, _) => (),
      failureInterceptorOpt = None,
      runTokenFn = () => true
    )
}
