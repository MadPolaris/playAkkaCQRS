package net.imadz.m25.component

import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Terminated}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

/** ChainExecutionActor（Monarch 驱动重建后）的恢复语义验收：
  *   - 正常批次：Accepted → 六阶段完成 → completed 回调（含终态快照）
  *   - 崩溃恢复：跑到第 3 道停实体 → 重建 → resumeFromIndex 只重跑第 4~6 道
  *   - StopPipeline：抛异常停实体（自愈演示的注入原语）
  *   - 二次 Start：实体非 Idle 时拒绝（幂等）
  *
  * 流水线用可控闸门（Promise）卡在 poll 阶段，精确制造"断点在第 3/4 道之间"的场景。 */
class ChainExecutionActorSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "akka.persistence.testkit.journal"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.no-snapshot-store"
      |akka.persistence.testkit.events.serialize = off
      |akka.actor.testkit.typed.single-expect-default = 10s
      |akka.actor.testkit.typed.filter-leeway = 30s
    """.stripMargin).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with org.scalatest.concurrent.Eventually {

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val items: List[Any] = (1 to 4).map(i => s"item-$i").toList
  private val response = ResponseFile("/tmp/r.dat", "r.dat", 8L, Array.emptyByteArray)

  /** 可控流水线：poll 阶段可被闸门卡住，用于精确制造断点。 */
  private def pipeline(pollGate: Option[Promise[ResponseFile]]): SubBatchPipeline[Any, Any] = {
    def after[T](ms: Int)(f: => T): Future[T] = Future { Thread.sleep(ms); f }
    SubBatchPipeline[Any, Any](
      fileGen = (its, _) => after(5)(GeneratedFile(s"/tmp/b${its.size}.dat", "b.dat", 8L, "dat")),
      upload = (f, _) => after(5)(UploadReceipt("/remote/b.dat", f.byteSize, 1L)),
      waitAck = (_, _) => after(5)(AckReceived),
      pollResp = _ => pollGate match {
        case Some(gate) => gate.future.map(ResponseReady(_))
        case None       => after(5)(ResponseReady(response))
      },
      parse = (_, _) => after(5)(Seq("raw-1", "raw-2")),
      classify = (_, its) => after(5)(its.map(i => net.imadz.m25.component.Success[Any](i, "OK")))
    )
  }

  /** 对齐 ChainExecutionActor.ChainExecutionObserver 的真实签名。 */
  private class RecordingObserver extends ChainExecutionActor.ChainExecutionObserver {
    val events: mutable.ListBuffer[String] = mutable.ListBuffer.empty[String]
    val completed = Promise[Option[BankChainState[Any, Any]]]()
    override def onStageStart(cursor: String): Unit = {
      println(s"[SPEC-OBS] start:$cursor")
      events.synchronized(events += s"start:$cursor")
    }
    override def onStageComplete(cursor: String, metadata: Map[String, String],
                                 snapshot: Option[BankChainState[Any, Any]]): Unit =
      events.synchronized(events += s"done:$cursor")
    override def onStageFailed(cursor: String, detail: String): Unit =
      events.synchronized(events += s"failed:$cursor")
    override def onCompleted(batchId: String, snapshot: Option[BankChainState[Any, Any]]): Unit = {
      println(s"[SPEC-OBS] completed $batchId")
      events.synchronized(events += "completed")
      completed.trySuccess(snapshot)
      ()
    }
    def doneCount: Int = events.synchronized(events.count(_.startsWith("done:")))
  }

  private def spawn(chainId: String, pipeline: SubBatchPipeline[Any, Any],
                    observer: ChainExecutionActor.ChainExecutionObserver): ActorRef[ChainExecutionActor.Command] = {
    println(s"[SPEC] spawning $chainId")
    val ref = testKit.spawn(ChainExecutionActor(chainId, pipeline, itemLoader, observer), chainId)
    println(s"[SPEC] spawned ${ref.path}")
    ref
  }

  private val itemLoader: String => Future[Seq[Any]] = _ => Future.successful(items)

  "A ChainExecutionActor" should {

    "run the six-stage chain to completion and hand the final snapshot to the observer" in {
      val observer = new RecordingObserver
      val ref = spawn("recharge-happy", pipeline(None), observer)
      val accepted = testKit.createTestProbe[ChainExecutionActor.ExecutionReply]()
      ref ! ChainExecutionActor.StartExecution("b-happy", items, accepted.ref)
      println("[SPEC] sent StartExecution, waiting Accepted")
      accepted.expectMessage(10.seconds, ChainExecutionActor.Accepted("recharge-happy-b-happy"))
      println("[SPEC] got Accepted")

      val snapshot = Await.result(observer.completed.future, 10.seconds).get
      snapshot.classifications.map(_.size) should be(Some(items.size))
      observer.events.toList should be(List(
        "start:file-gen#0", "done:file-gen#0",
        "start:upload#1", "done:upload#1",
        "start:wait-ack#2", "done:wait-ack#2",
        "start:poll#3", "done:poll#3",
        "start:parse#4", "done:parse#4",
        "start:classify#5", "done:classify#5",
        "completed"))
      testKit.stop(ref)
    }

    "resume from the breakpoint after a crash without re-running completed stages" in {
      val gate = Promise[ResponseFile]()
      val observer1 = new RecordingObserver
      val ref1 = spawn("recharge-resume", pipeline(Some(gate)), observer1)
      val accepted1 = testKit.createTestProbe[ChainExecutionActor.ExecutionReply]()
      ref1 ! ChainExecutionActor.StartExecution("b-crash", items, accepted1.ref)
      accepted1.expectMessage(10.seconds, ChainExecutionActor.Accepted("recharge-resume-b-crash"))
      eventually { observer1.doneCount should be(3) } // file-gen/upload/wait-ack 完成，poll 闸门卡住

      // ⚡ 崩溃：停实体（账本已持久化）
      testKit.stop(ref1)

      // 恢复：放行闸门 → 重建实体 → resumeFromIndex(3) 只重跑 poll/parse/classify
      gate.trySuccess(response)
      val observer2 = new RecordingObserver
      val ref2 = spawn("recharge-resume", pipeline(Some(gate)), observer2)
      eventually {
        observer2.doneCount should be(3)
        observer2.completed.isCompleted should be(true)
      }
      val snapshot = Await.result(observer2.completed.future, 10.seconds).get
      snapshot.classifications.map(_.size) should be(Some(items.size))
      observer2.events.toList.count(_.startsWith("start:file-gen")) should be(0) // 已完成阶段绝不重跑
      testKit.stop(ref2)
    }

    "StopPipeline stops the entity (crash-injection primitive)" in {
      val observer = new RecordingObserver
      val ref = spawn("recharge-stop", pipeline(None), observer)
      val accepted = testKit.createTestProbe[ChainExecutionActor.ExecutionReply]()
      ref ! ChainExecutionActor.StartExecution("b-stop", items, accepted.ref)
      accepted.expectMessage(10.seconds, ChainExecutionActor.Accepted("recharge-stop-b-stop"))

      // 注入原语的契约：StopPipeline 抛异常停实体（supervisor 记 ERROR 后终止）
      import akka.actor.testkit.typed.scaladsl.LoggingTestKit
      LoggingTestKit.error("Chain crash injected").expect {
        ref ! ChainExecutionActor.StopPipeline("spec")
      }
      testKit.stop(ref)
    }

    "reject a second StartExecution while a batch is running" in {
      val gate = Promise[ResponseFile]()
      val observer = new RecordingObserver
      val ref = spawn("recharge-second", pipeline(Some(gate)), observer)
      val first = testKit.createTestProbe[ChainExecutionActor.ExecutionReply]()
      ref ! ChainExecutionActor.StartExecution("b-first", items, first.ref)
      first.expectMessage(10.seconds, ChainExecutionActor.Accepted("recharge-second-b-first"))

      val second = testKit.createTestProbe[ChainExecutionActor.ExecutionReply]()
      ref ! ChainExecutionActor.StartExecution("b-second", items, second.ref)
      second.expectMessageType[ChainExecutionActor.ExecutionRejected](10.seconds)

      gate.trySuccess(response)
      testKit.stop(ref)
    }
  }
}
