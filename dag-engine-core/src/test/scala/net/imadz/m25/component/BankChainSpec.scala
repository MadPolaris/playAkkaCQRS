package net.imadz.m25.component

import net.imadz.monarch.{LifecycleHooks, Monarch, StageError, StageFailedException, StaleRun}

import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/** BankChain — the six-stage chain on Monarch. These specs pin the journal-compat
  * cursor vocabulary ("file-gen"...), the ack/poll failure classification, and the
  * resume-from-index mechanics that ChainExecutionActor relies on. */
class BankChainSpec extends AsyncWordSpec with Matchers {

  implicit override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

  private def stubPipeline(
      ack: AckResult = AckReceived,
      poll: PollResult = ResponseReady(ResponseFile("/tmp/r.xml", "r.xml", 10L, "<r/>".getBytes))
  ): SubBatchPipeline[String, String] =
    SubBatchPipeline[String, String](
      fileGen = (_, _) => Future.successful(GeneratedFile("/tmp/b.xml", "b.xml", 42L, "xml")),
      upload = (_, _) => Future.successful(UploadReceipt("/remote/b.xml", 42L, 1L)),
      waitAck = (_, _) => Future.successful(ack),
      pollResp = _ => Future.successful(poll),
      parse = (_, _) => Future.successful(Seq("OK-item-1", "OK-item-2")),
      classify = (raw, items) => Future.successful(raw.map(r => Success[String](items.head, r)))
    )

  private def recordingMonarch(
      pipeline: SubBatchPipeline[String, String],
      runToken: () => Boolean = () => true
  ): (Monarch[BankStage, BankChainState[String, String]], mutable.ListBuffer[String]) = {
    val events = mutable.ListBuffer.empty[String]
    val hooks = new LifecycleHooks[BankStage, BankChainState[String, String]] {
      override def stageName(stage: BankStage): String = BankStage.stageName(stage)
      override def onStageStart(cursor: String): Unit = events += s"start:$cursor"
      override def onStageComplete(cursor: String, state: BankChainState[String, String], metadata: Map[String, String]): Unit =
        events += s"done:$cursor"
      override def onStageFailed(cursor: String, error: StageError): Unit = events += s"failed:$cursor"
    }
    (BankChain.monarch(pipeline, hooks, runToken), events)
  }

  private def items = Seq("item-1", "item-2")

  private def freshState = BankChainState[String, String](batchId = "b-1", chainId = "recharge", items = items)

  "BankChain" should {

    "run the six-stage chain in order with journal-compatible cursor names" in {
      val (m, events) = recordingMonarch(stubPipeline())
      m.initialize(BankStage.chain)
      m.process(freshState).map { result =>
        result.classifications.get should have size 2
        events.toList should be(List(
          "start:file-gen#0", "done:file-gen#0",
          "start:upload#1", "done:upload#1",
          "start:wait-ack#2", "done:wait-ack#2",
          "start:poll#3", "done:poll#3",
          "start:parse#4", "done:parse#4",
          "start:classify#5", "done:classify#5"))
      }
    }

    "derive the same metadata keys as the old processor" in {
      val (m, _) = recordingMonarch(stubPipeline())
      m.initialize(BankStage.chain)
      m.process(freshState).map { result =>
        val fileMeta = BankChain.metadataOf(result.copy(lastStage = Some(BankStage.FileGen)))
        fileMeta should contain("fileName" -> "b.xml")
        val clsMeta = BankChain.metadataOf(result.copy(lastStage = Some(BankStage.Classify)))
        clsMeta should contain("successCount" -> "2")
        clsMeta should contain("failureCount" -> "0")
        clsMeta should contain("suspiciousCount" -> "0")
      }
    }

    "classify an AckTimeout as a classified ACK_TIMEOUT failure" in {
      val (m, events) = recordingMonarch(stubPipeline(ack = AckTimeout(5000L)))
      m.initialize(BankStage.chain)
      recoverToExceptionIf[StageFailedException](m.process(freshState)).map { e =>
        e.error.errorCode should be("ACK_TIMEOUT")
        events.toList should be(List(
          "start:file-gen#0", "done:file-gen#0",
          "start:upload#1", "done:upload#1",
          "start:wait-ack#2", "failed:wait-ack#2"))
      }
    }

    "classify a PollTimeout as POLL_TIMEOUT" in {
      val (m, _) = recordingMonarch(stubPipeline(poll = PollTimeout(20, 600000L)))
      m.initialize(BankStage.chain)
      recoverToExceptionIf[StageFailedException](m.process(freshState)).map { e =>
        e.error.errorCode should be("POLL_TIMEOUT")
        e.error.stage should be("poll")
      }
    }

    "resumeFromIndex after completed prefix, carrying intermediate values" in {
      val (m, events) = recordingMonarch(stubPipeline())
      m.initialize(BankStage.chain)
      // Simulate a crash after wait-ack: receipt present in the snapshot, skip 3 stages.
      val snapshot = freshState.copy(
        generatedFile = Some(GeneratedFile("/tmp/b.xml", "b.xml", 42L, "xml")),
        receipt = Some(UploadReceipt("/remote/b.xml", 42L, 1L)),
        lastStage = Some(BankStage.WaitAck))
      m.resumeFromIndex(snapshot, completedCount = 3).map { result =>
        result.receipt should be(defined) // intermediate value carried into resumed stages
        result.classifications.get should have size 2
        events.toList should be(List(
          "start:poll#3", "done:poll#3",
          "start:parse#4", "done:parse#4",
          "start:classify#5", "done:classify#5"))
      }
    }

    "terminate silently with StaleRun when superseded" in {
      val (m, events) = recordingMonarch(stubPipeline(), runToken = () => false)
      m.initialize(BankStage.chain)
      recoverToSucceededIf[StaleRun.type](m.process(freshState)).map { _ =>
        events.toList should be(Nil)
      }
    }
  }
}
