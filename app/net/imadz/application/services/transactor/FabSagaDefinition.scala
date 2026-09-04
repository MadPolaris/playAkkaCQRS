package net.imadz.application.services.transactor

import akka.actor.typed.{ActorSystem, Scheduler}
import akka.cluster.sharding.typed.scaladsl.EntityRef
import net.imadz.common.Id
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.dsl.{ArgsCodec, RecoveryBehavior, ResiliencePolicy, SagaBusinessEvent, SagaDefinition, SagaRunner, SagaRegistry, SagaStep}
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

  def definition(implicit ec: ExecutionContext, scheduler: Scheduler): SagaDefinition[iMadzError, AppSagaContext, FabSagaArgs] =
    SagaDefinition[iMadzError, AppSagaContext, FabSagaArgs](
      name = Name,
      version = Version,
      argsCodec = codec,
      steps = args => {
        val source = SourceLotParticipant(Id.of(args.sourceLotId), args.waferIds.map(Id.of).toSet, args.waferNames.toSet)
        val target = TargetLotParticipant(Id.of(args.targetLotId), args.waferIds.map(Id.of).toSet)
        Seq(
          SagaStep("source-lot", source, Policy, stepGroup = 1),
          SagaStep("target-lot", target, Policy, stepGroup = 1))
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
