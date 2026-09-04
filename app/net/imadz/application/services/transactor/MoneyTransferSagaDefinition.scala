package net.imadz.application.services.transactor

import akka.actor.typed.ActorSystem
import akka.actor.typed.Scheduler
import akka.cluster.sharding.typed.scaladsl.EntityRef
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.common.CommonTypes.iMadzError
import net.imadz.domain.values.Money
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.dsl.{ArgsCodec, ResiliencePolicy, SagaBusinessEvent, SagaDefinition, SagaRunner, SagaRegistry, SagaStep}
import play.api.libs.json.{Format, Json}

import scala.concurrent.ExecutionContext

/** Declarative money-transfer saga — the single artifact a business developer writes.
  * Participants are pure functions of (definition, args); the journal never stores them. */
object MoneyTransferSagaDefinition {

  val Name = "money-transfer"
  val Version = 1

  final case class TransferArgs(fromUserId: String, toUserId: String, amount: BigDecimal, currency: String)

  object TransferArgs {
    implicit val format: Format[TransferArgs] = Json.format[TransferArgs]

    def of(from: String, to: String, money: Money): TransferArgs =
      TransferArgs(from, to, money.amount, money.currency.getCurrencyCode)

    def toMoney(args: TransferArgs): Money = Money(args.amount, java.util.Currency.getInstance(args.currency))
  }

  /** Business events emitted at the terminal state (consumed by the onResult projector). */
  final case class MoneyTransferCompleted(transactionId: String, fromUserId: String, toUserId: String,
                                          amount: BigDecimal, currency: String, successful: Boolean, failReason: String)
      extends SagaBusinessEvent

  object MoneyTransferCompleted {
    implicit val writes: play.api.libs.json.Writes[MoneyTransferCompleted] = Json.writes[MoneyTransferCompleted]
  }

  def definition(implicit ec: ExecutionContext, scheduler: Scheduler): SagaDefinition[iMadzError, AppSagaContext, TransferArgs] =
    SagaDefinition[iMadzError, AppSagaContext, TransferArgs](
      name = Name,
      version = Version,
      argsCodec = ArgsCodec.playJson[TransferArgs],
      steps = args => {
        val money = TransferArgs.toMoney(args)
        Seq(
          SagaStep("transfer-out", FromAccountParticipant(net.imadz.common.Id.of(args.fromUserId), money), ResiliencePolicy(maxRetries = 5), stepGroup = 1),
          SagaStep("transfer-in", ToAccountParticipant(net.imadz.common.Id.of(args.toUserId), money), ResiliencePolicy(maxRetries = 5), stepGroup = 1))
      },
      preCheck = args =>
        if (args.amount > 0 && args.fromUserId != args.toUserId) Right(args)
        else if (args.amount <= 0) Left(iMadzError("40001", s"amount must be positive, got ${args.amount}"))
        else Left(iMadzError("40002", "from and to accounts must differ")),
      errorText = e => (e.code, e.message),
      onResult = (args, result) => List(MoneyTransferCompleted(
        result.snapshot.transactionId, args.fromUserId, args.toUserId, args.amount, args.currency,
        result.successful, if (result.failReason == null) "" else result.failReason)),
      defaultResilience = ResiliencePolicy.defaults
    )

  /** Registers the definition on this node — must run before sharding recovers any entity. */
  def register(implicit ec: ExecutionContext, scheduler: Scheduler): SagaDefinition[iMadzError, AppSagaContext, TransferArgs] = {
    val defn = definition
    SagaRegistry.register(defn)
    defn
  }

  /** One runner per node for this saga. `coordinatorRef` resolves the sharded coordinator by txId. */
  def runner(system: ActorSystem[_], coordinatorRef: String => EntityRef[SagaTransactionCoordinator.Command])(
      implicit ec: ExecutionContext, scheduler: Scheduler): SagaRunner[iMadzError, TransferArgs] =
    new SagaRunner(definition, coordinatorRef, system)
}
