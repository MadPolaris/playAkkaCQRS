package net.imadz.application.services.transactor

import akka.actor.typed.{ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol._
import net.imadz.common.Id
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.infra.saga.SagaParticipant.SagaResult
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.dsl.{ArgsCodec, ErrorAction, ErrorRules, RecoveryBehavior, ResiliencePolicy, SagaBusinessEvent, SagaDefinition, SagaRegistry, SagaRunner, SagaStep}
import play.api.libs.json.Format

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Declarative Fab wafer-transfer saga — lot transfer / split / merge share one plan:
  * reserve both lots (prepare) -> commit both (confirm) -> cancel/release in reverse
  * order (compensate). Participants resolve Lot aggregates from the shared engine
  * context ([[AppSagaContext]].lots); the journal never stores participants. */
object FabSagaDefinition {

  val Name = "fab-saga"
  val Version = 1

  final case class FabSagaArgs(sourceLotId: String, targetLotId: String, waferIds: List[String], waferNames: List[String])

  /** Shared with the status aggregate so it can encode args for StartSaga. */
  val codec: ArgsCodec[FabSagaArgs] = ArgsCodec.playJson[FabSagaArgs]

  object FabSagaArgs {
    implicit val format: Format[FabSagaArgs] = play.api.libs.json.Json.format[FabSagaArgs]
  }

  /** Business event emitted at the terminal state (consumed by the onResult projector). */
  final case class FabSagaCompleted(transactionId: String, sourceLotId: String, targetLotId: String,
                                    waferCount: Int, successful: Boolean, failReason: String)
    extends SagaBusinessEvent

  /** v2 semantics carried over: an executor recovering mid-flight retries the attempt
    * instead of failing the step (participants dedupe by (txId, stepId, attempt)). */
  private val Policy = ResiliencePolicy(
    maxRetries = 5,
    timeoutPerAttempt = 30.seconds,
    recovery = RecoveryBehavior.RetryIfOngoing)

  private val LotErrorRules: ErrorRules[iMadzError] = ErrorRules(
    business = { case _: iMadzError => ErrorAction.NonRetryable },
    describe = e => s"Lot invariant violation (${e.code}: ${e.message})"
  )

  def definition(implicit ec: ExecutionContext, scheduler: Scheduler): SagaDefinition[iMadzError, AppSagaContext, FabSagaArgs] =
    SagaDefinition[iMadzError, AppSagaContext, FabSagaArgs](
      name = Name,
      version = Version,
      argsCodec = codec,
      steps = args => {
        val sourceLotId = Id.of(args.sourceLotId)
        val targetLotId = Id.of(args.targetLotId)
        val waferIdSet = args.waferIds.map(Id.of).toSet
        val waferNameSet = args.waferNames.toSet
        Seq(
          SagaStep.builder[iMadzError, AppSagaContext]("source-lot")
            .inGroup(1)
            .withErrorRules(LotErrorRules)
            .withTimeout(5.seconds)
            .withResilience(Policy)
            .prepare
              .ref(ctx => ctx.lots.findLotById(sourceLotId))
              .command[WaferRemovalConfirmation]((txId, replyTo) => ReserveWaferRemoval(Id.of(txId), waferIdSet, waferNameSet, replyTo))
              .mapReply(c => c.error.map(Left(_)).getOrElse(Right(SagaResult(c.transferId.toString))))
            .commit
              .ref(ctx => ctx.lots.findLotById(sourceLotId))
              .command[WaferRemovalConfirmation]((txId, replyTo) => CommitWaferRemoval(Id.of(txId), replyTo))
              .mapReply(c => c.error.map(Left(_)).getOrElse(Right(SagaResult(c.transferId.toString))))
            .compensate
              .ref(ctx => ctx.lots.findLotById(sourceLotId))
              .command[WaferRemovalConfirmation]((txId, replyTo) => ReleaseReservedWafer(Id.of(txId), replyTo))
              .mapReply(c => c.error.map(Left(_)).getOrElse(Right(SagaResult(c.transferId.toString))))
            .build(),
          SagaStep.builder[iMadzError, AppSagaContext]("target-lot")
            .inGroup(1)
            .withErrorRules(LotErrorRules)
            .withTimeout(5.seconds)
            .withResilience(Policy)
            .prepare.direct { (txId, ctx, _) =>
              implicit val askTimeout: Timeout = 5.seconds
              val sourceRef = ctx.lots.findLotById(sourceLotId)
              val targetRef = ctx.lots.findLotById(targetLotId)
              val carriedFuture = sourceRef.ask(GetLotState(_)).mapTo[LotConfirmation]
                .map(conf => conf.waferStates.filter { case (k, _) => waferIdSet.contains(k) }.toMap)
                .recover { case _ => Map.empty[Id, net.imadz.domain.entities.LotEntity.WaferState] }
              carriedFuture.flatMap { carried =>
                targetRef.ask(ReserveAddWafer(Id.of(txId), waferIdSet, _, carried))
                  .mapTo[WaferAdditionConfirmation]
                  .map(c => c.error.map[Either[iMadzError, SagaResult[String]]](Left.apply)
                    .getOrElse(Right(SagaResult(c.transferId.toString))))
              }
            }
            .commit
              .ref(ctx => ctx.lots.findLotById(targetLotId))
              .command[WaferAdditionConfirmation]((txId, replyTo) => CommitAddWafer(Id.of(txId), replyTo))
              .mapReply(c => c.error.map(Left(_)).getOrElse(Right(SagaResult(c.transferId.toString))))
            .compensate
              .ref(ctx => ctx.lots.findLotById(targetLotId))
              .command[WaferAdditionConfirmation]((txId, replyTo) => CancelAddWafer(Id.of(txId), replyTo))
              .mapReply(c => c.error.map(Left(_)).getOrElse(Right(SagaResult(c.transferId.toString))))
            .build()
        )
      },
      onResult = (args, result) => List(FabSagaCompleted(
        result.snapshot.transactionId, args.sourceLotId, args.targetLotId,
        args.waferIds.size, result.successful,
        if (result.failReason == null) "" else result.failReason)),
      defaultResilience = ResiliencePolicy.defaults
    )

  /** Registers the definition on this node — must run before sharding recovers any entity. */
  def register(implicit ec: ExecutionContext, scheduler: Scheduler): SagaDefinition[iMadzError, AppSagaContext, FabSagaArgs] = {
    val defn = definition
    SagaRegistry.register(defn)
    defn
  }

  /** One runner per node for this saga. `coordinatorRef` resolves the sharded coordinator by txId. */
  def runner(system: ActorSystem[_], coordinatorRef: String => EntityRef[SagaTransactionCoordinator.Command])(
      implicit ec: ExecutionContext, scheduler: Scheduler): SagaRunner[iMadzError, FabSagaArgs] =
    new SagaRunner(definition, coordinatorRef, system)
}
