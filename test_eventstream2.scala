import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors

object TestEventStream2 extends App {
  val classic = ActorSystem("Test")
  val typed = classic.toTyped

  trait Event
  case class ConcreteEvent(id: String) extends Event

  val bridge = typed.systemActorOf(Behaviors.setup[Any] { ctx =>
    println("Bridge started")
    ctx.system.toClassic.eventStream.subscribe(ctx.self.toClassic, classOf[Event])
    Behaviors.receiveMessage { msg =>
      println(s"Received: $msg")
      Behaviors.same
    }
  }, "bridge")

  Thread.sleep(500)
  println("Publishing Event...")
  typed.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(ConcreteEvent("123"))

  Thread.sleep(1000)
  classic.terminate()
}
