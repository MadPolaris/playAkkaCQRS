package net.imadz.infra.saga.acceptance

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaTransactionCoordinator
import org.scalatest.wordspec.AnyWordSpecLike

/** AC-1.9 — every cross-node message type must ride a non-Java serializer. */
class SerializationBindingAcceptanceSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  loglevel = warning
      |}
      |""".stripMargin
  )
) with AnyWordSpecLike {

  private val classic = system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]
  private val serialization = akka.serialization.SerializationExtension(classic)
  private val javaSerializerClass = classOf[akka.serialization.JavaSerializer]

  private def wireTypes: Seq[Class[_]] = Seq(
    classOf[SagaTransactionCoordinator.StartSaga],
    classOf[SagaTransactionCoordinator.Started.type],
    classOf[SagaTransactionCoordinator.AlreadyRunning],
    classOf[SagaTransactionCoordinator.AlreadyFinished],
    classOf[SagaTransactionCoordinator.UnknownDefinition.type],
    classOf[SagaTransactionCoordinator.ConflictingArgs.type],
    classOf[SagaTransactionCoordinator.MaterializeFailed.type],
    classOf[SagaTransactionCoordinator.PreCheckFailed],
    classOf[SagaTransactionCoordinator.TransactionResult],
    classOf[SagaTransactionCoordinator.StatusSnapshot],
    classOf[SagaTransactionCoordinator.StepSpecSnapshot],
    classOf[SagaTransactionCoordinator.GetTransactionStatus],
    classOf[SagaTransactionCoordinator.ManualFixStep],
    classOf[SagaTransactionCoordinator.StepManuallyFixed]
  )

  "AC-1.9 serialization bindings" should {
    "bind all cross-node message types to the jackson-cbor serializer" in {
      wireTypes.foreach { clazz =>
        val serializer = serialization.serializerFor(clazz)
        withClue(s"${clazz.getSimpleName} serialized by ${serializer.getClass.getName}: ") {
          serializer.getClass should not be javaSerializerClass
        }
      }
    }
  }
}
