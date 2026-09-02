package net.imadz.application.services.transactor

import akka.actor.typed.ActorSystem
import akka.actor.typed.Scheduler
import akka.cluster.sharding.typed.scaladsl.EntityRef
import net.imadz.infra.saga.SagaTransactionCoordinator
import net.imadz.infra.saga.dsl.{ArgsCodec, ResiliencePolicy, SagaDefinition, SagaRunner, SagaRegistry, SagaStep}
import play.api.libs.json.{Format, Json}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Showcase saga: Step-A (group 1) then Step-B/Step-C in parallel (group 2), full TCC.
  * Drives the five demo paths: group parallelism, self-healing retry, reverse
  * compensation, ManualFix, and suspended recovery. */
object ShowcaseSagaDefinition {

  val Name = "showcase"
  val Version = 1

  final case class ShowcaseArgs(note: String = "showcase")

  object ShowcaseArgs {
    implicit val format: Format[ShowcaseArgs] = Json.format[ShowcaseArgs]
  }

  def definition(implicit ec: ExecutionContext, scheduler: akka.actor.typed.Scheduler): SagaDefinition[String, Any, ShowcaseArgs] =
    SagaDefinition[String, Any, ShowcaseArgs](
      name = Name,
      version = Version,
      argsCodec = ArgsCodec.playJson[ShowcaseArgs],
      steps = _ => Seq(
        SagaStep("Step-A", new ShowcaseParticipant("Step-A"), ResiliencePolicy(maxRetries = 3), stepGroup = 1),
        SagaStep("Step-B", new ShowcaseParticipant("Step-B"), ResiliencePolicy(maxRetries = 3), stepGroup = 2),
        SagaStep("Step-C", new ShowcaseParticipant("Step-C"), ResiliencePolicy(maxRetries = 3), stepGroup = 2)),
      defaultResilience = ResiliencePolicy(maxRetries = 3, timeoutPerAttempt = 30.seconds)
    )

  def register(implicit ec: ExecutionContext, scheduler: akka.actor.typed.Scheduler): SagaDefinition[String, Any, ShowcaseArgs] = {
    val defn = definition
    SagaRegistry.register(defn)
    defn
  }

  def runner(system: ActorSystem[_], coordinatorRef: String => EntityRef[SagaTransactionCoordinator.Command])(
      implicit ec: ExecutionContext, scheduler: Scheduler): SagaRunner[String, ShowcaseArgs] =
    new SagaRunner(definition, coordinatorRef, system)
}
