package net.imadz.m25.component

import net.imadz.monarch.{LifecycleHooks, Monarch, StageError, StageFailedException, StageInterpreter}

import scala.concurrent.{ExecutionContext, Future}

/** The six mechanical stages of an external-system batch chain. */
sealed trait BankStage
object BankStage {

  case object FileGen extends BankStage
  case object Upload extends BankStage
  case object WaitAck extends BankStage
  case object Poll extends BankStage
  case object Parse extends BankStage
  case object Classify extends BankStage

  /** The standard chain order. */
  val chain: Seq[BankStage] = Seq(FileGen, Upload, WaitAck, Poll, Parse, Classify)

  /** Cursor vocabulary — byte-identical to the pre-Monarch processor's phase names
    * ("file-gen", "upload", ...) — journaled PhaseDone events replay unchanged. */
  def stageName(stage: BankStage): String = stage match {
    case FileGen  => "file-gen"
    case Upload   => "upload"
    case WaitAck  => "wait-ack"
    case Poll     => "poll"
    case Parse    => "parse"
    case Classify => "classify"
  }
}

/** The heterogeneous intermediate values of the old SubBatchProcessor for-comprehension,
  * unified into one threaded-through state. Every stage reads its input slot and writes
  * its output slot; `lastStage` records which stage completed last (metadata derivation). */
final case class BankChainState[Item, Raw](
    batchId: String,
    chainId: String,
    items: Seq[Item],
    context: Map[String, Any] = Map.empty,
    generatedFile: Option[GeneratedFile] = None,
    receipt: Option[UploadReceipt] = None,
    ack: Option[AckResult] = None,
    responseFile: Option[ResponseFile] = None,
    rawResults: Option[Seq[Raw]] = None,
    classifications: Option[Seq[Classification[Item]]] = None,
    lastStage: Option[BankStage] = None
)

/**
 * BankChain — the six-stage external-system chain expressed as a Monarch stage queue.
 *
 * This is the M2.5+ recharge/purchase chain migrated onto the Monarch engine: the same
 * six mechanical stages the SubBatchProcessor for-comprehension hard-wired, now an open
 * queue (`BankStage.chain`) so hosts gain dynamic weaving, cursor resume and generation
 * guards for free.
 */
object BankChain {

  /** Per-stage metadata maps — same keys as the old onPhaseComplete calls. */
  def metadataOf[Item, Raw](state: BankChainState[Item, Raw]): Map[String, String] =
    state.lastStage match {
      case Some(BankStage.FileGen) =>
        state.generatedFile.map(f => Map(
          "localPath" -> f.localPath, "fileName" -> f.fileName,
          "byteSize" -> f.byteSize.toString, "encoding" -> f.encoding)).getOrElse(Map.empty)
      case Some(BankStage.Upload) =>
        state.receipt.map(r => Map(
          "remotePath" -> r.remotePath, "bytesTransferred" -> r.bytesTransferred.toString,
          "timestamp" -> r.timestamp.toString)).getOrElse(Map.empty)
      case Some(BankStage.WaitAck) =>
        state.ack.map(a => Map("ackResult" -> a.getClass.getSimpleName)).getOrElse(Map.empty)
      case Some(BankStage.Poll) =>
        state.responseFile.map(f => Map(
          "localPath" -> f.localPath, "fileName" -> f.fileName,
          "byteSize" -> f.byteSize.toString)).getOrElse(Map.empty)
      case Some(BankStage.Parse) =>
        state.rawResults.map(r => Map("resultCount" -> r.size.toString)).getOrElse(Map.empty)
      case Some(BankStage.Classify) =>
        state.classifications.map { cs =>
          def count[C <: Classification[Item]](c: Class[C]) = cs.collect { case x if c.isInstance(x) => x }.size
          Map(
            "successCount" -> count(classOf[Success[Item]]).toString,
            "failureCount" -> count(classOf[Failure[Item]]).toString,
            "suspiciousCount" -> count(classOf[Suspicious[Item]]).toString)
        }.getOrElse(Map.empty)
      case None => Map.empty
    }

  /** Build a Monarch engine executing the six-stage chain against `pipeline`.
    *
    * Failure semantics mirror the old SubBatchProcessor: AckTimeout/AckRejected/
    * PollTimeout/PollError throw a classified [[StageFailedException]] (code ACK_TIMEOUT /
    * ACK_REJECTED / POLL_TIMEOUT / POLL_ERROR). With no failure interceptor configured the
    * run fails — byte-identical to the old behavior; hosts may add an interceptor to
    * resolve-and-continue.
    */
  def monarch[Item, Raw](
      pipeline: SubBatchPipeline[Item, Raw],
      hooks: LifecycleHooks[BankStage, BankChainState[Item, Raw]],
      runToken: () => Boolean = () => true
  ): Monarch[BankStage, BankChainState[Item, Raw]] =
    new Monarch[BankStage, BankChainState[Item, Raw]](
      interpreter = new StageInterpreter[BankStage, BankChainState[Item, Raw]] {
        override def run(stage: BankStage, state: BankChainState[Item, Raw])(implicit ec: ExecutionContext): Future[BankChainState[Item, Raw]] =
          runStage(stage, state, pipeline)
      },
      hooks = hooks,
      failureInterceptor = None,
      runToken = runToken
    )

  private def missing(stage: String, slot: String): Exception =
    new IllegalStateException(s"[$stage] state slot '$slot' missing — stages must run in chain order")

  /** Stage interpreter: each branch returns a Future; ack/poll ADT failures are thrown as
    * classified StageFailedException so the engine's failure policy applies. */
  def runStage[Item, Raw](stage: BankStage, state: BankChainState[Item, Raw], pipeline: SubBatchPipeline[Item, Raw])(implicit ec: ExecutionContext): Future[BankChainState[Item, Raw]] = {
    def fail(code: String, detail: String): Nothing =
      throw StageFailedException(StageError(BankStage.stageName(stage), Some(code), code, detail))
    stage match {
      case BankStage.FileGen =>
        pipeline.fileGen.generate(state.items, state.context)
          .map(f => state.copy(generatedFile = Some(f), lastStage = Some(stage)))
      case BankStage.Upload =>
        state.generatedFile.fold(Future.failed[BankChainState[Item, Raw]](missing("upload", "generatedFile"))) { f =>
          pipeline.upload.upload(f, state.context)
            .map(r => state.copy(receipt = Some(r), lastStage = Some(stage)))
        }
      case BankStage.WaitAck =>
        state.receipt.fold(Future.failed[BankChainState[Item, Raw]](missing("wait-ack", "receipt"))) { r =>
          pipeline.waitAck.waitForAck(r, state.context).map {
            case AckReceived         => state.copy(ack = Some(AckReceived), lastStage = Some(stage))
            case AckTimeout(ms)      => fail("ACK_TIMEOUT", s"External system ack timeout after ${ms}ms")
            case AckRejected(reason) => fail("ACK_REJECTED", s"External system rejected: $reason")
          }
        }
      case BankStage.Poll =>
        pipeline.pollResp.poll(state.context).map {
          case ResponseReady(file)       => state.copy(responseFile = Some(file), lastStage = Some(stage))
          case PollTimeout(attempts, ms) => fail("POLL_TIMEOUT", s"Response poll timeout after $attempts attempts (${ms}ms)")
          case PollError(cause)          => fail("POLL_ERROR", s"Response poll error: ${Option(cause.getMessage).getOrElse(cause.toString)}")
        }
      case BankStage.Parse =>
        state.responseFile.fold(Future.failed[BankChainState[Item, Raw]](missing("parse", "responseFile"))) { f =>
          pipeline.parse.parse(f, state.context)
            .map(raw => state.copy(rawResults = Some(raw), lastStage = Some(stage)))
        }
      case BankStage.Classify =>
        state.rawResults.fold(Future.failed[BankChainState[Item, Raw]](missing("classify", "rawResults"))) { raw =>
          pipeline.classify.classify(raw, state.items)
            .map(c => state.copy(classifications = Some(c), lastStage = Some(stage)))
        }
    }
  }
}
