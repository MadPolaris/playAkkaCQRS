import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors

object TestEventStream extends App {
  val classic = ActorSystem("Test")
  val typed = classic.toTyped

  sealed trait MyEvent
  case class MyConcreteEvent(msg: String) extends MyEvent

  val subscriber = typed.systemActorOf(Behaviors.setup[Any] { ctx =>
    println("Subscriber started")
    ctx.system.toClassic.eventStream.subscribe(ctx.self.toClassic, classOf[MyEvent])
    Behaviors.receiveMessage { msg =>
      println(s"Received: $msg")
      Behaviors.same
    }
  }, "sub")

  Thread.sleep(1000)
  println("Publishing from Classic...")
  classic.eventStream.publish(MyConcreteEvent("hello classic"))
  
  Thread.sleep(1000)
  println("Publishing from Typed...")
  typed.eventStream ! akka.actor.typed.eventstream.EventStream.Publish(MyConcreteEvent("hello typed"))

  Thread.sleep(1000)
  classic.terminate()
}
