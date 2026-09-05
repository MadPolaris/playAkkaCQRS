package net.imadz.monarch

import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** Engine semantics ported 1:1 from the Fab-hardened FabPipelineProcessor — these specs
  * are the acceptance contract for every host that adopts Monarch. */
class MonarchEngineSpec extends AsyncWordSpec with Matchers {

  implicit override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

  case class S(log: List[String] = Nil, n: Int = 0)

  sealed trait Stage
  case object Alpha extends Stage
  case object Beta extends Stage
  case object Boom extends Stage

  private def nameOf(s: Stage): String = s match {
    case Alpha => "Alpha"
    case Beta  => "Beta"
    case Boom  => "Boom"
  }

  private def makeMonarch(
      step: (Stage, S) => Future[S] = (s, st) => Future.successful(st.copy(log = st.log :+ nameOf(s), n = st.n + 1)),
      interceptor: Option[(String, StageError, S) => Future[S]] = None,
      runToken: () => Boolean = () => true
  ): (Monarch[Stage, S], mutable.ListBuffer[String]) = {
    val events = mutable.ListBuffer.empty[String]
    val hooks = new LifecycleHooks[Stage, S] {
      override def stageName(stage: Stage): String = nameOf(stage)
      override def onStageStart(cursor: String): Unit = events += s"start:$cursor"
      override def onStageComplete(cursor: String, state: S, metadata: Map[String, String]): Unit =
        events += s"done:$cursor(state.n=${state.n})"
      override def onStageFailed(cursor: String, error: StageError): Unit = events += s"failed:$cursor"
      override def onStageResolved(cursor: String, error: StageError, state: S): Unit =
        events += s"resolved:$cursor(state.n=${state.n})"
    }
    val interpreter = new StageInterpreter[Stage, S] {
      override def run(stage: Stage, state: S)(implicit ec: ExecutionContext): Future[S] = step(stage, state)
    }
    val failureInterceptor = interceptor.map { fi =>
      new FailureInterceptor[Stage, S] {
        override def intercept(cursor: String, error: StageError, state: S)(implicit ec: ExecutionContext): Future[S] =
          fi(cursor, error, state)
      }
    }
    (new Monarch[Stage, S](interpreter, hooks, failureInterceptor, runToken), events)
  }

  "A Monarch engine" should {

    "run all stages in order, threading state through" in {
      val (m, events) = makeMonarch()
      m.initialize(Seq(Alpha, Beta, Alpha))
      m.process(S()).map { result =>
        result.log should be(List("Alpha", "Beta", "Alpha"))
        result.n should be(3)
        events.toList should be(List(
          "start:Alpha#0", "done:Alpha#0(state.n=1)",
          "start:Beta#1", "done:Beta#1(state.n=2)",
          "start:Alpha#2", "done:Alpha#2(state.n=3)"))
      }
    }

    "give repeated stage variants unique position-suffixed cursors" in {
      val (m, _) = makeMonarch()
      m.initialize(Seq(Alpha, Beta, Alpha))
      m.cursorOf(Alpha, 0) should be("Alpha#0")
      m.cursorOf(Beta, 1) should be("Beta#1")
      m.cursorOf(Alpha, 2) should be("Alpha#2")
    }

    "support injectHead / appendTail / pendingCount" in {
      val (m, events) = makeMonarch()
      m.initialize(Seq(Alpha))
      m.injectHead(Seq(Beta))
      m.appendTail(Seq(Boom, Alpha))
      m.pendingCount should be(4)
      m.process(S()).map { result =>
        result.log should be(List("Beta", "Alpha", "Boom", "Alpha"))
        events.head should be("start:Beta#1") // injected head takes the next position
      }
    }

    "resumeFromIndex skips the completed prefix" in {
      val (m, events) = makeMonarch()
      m.initialize(Seq(Alpha, Beta, Alpha))
      m.resumeFromIndex(S(log = List("pre")), completedCount = 2).map { result =>
        result.log should be(List("pre", "Alpha")) // only entry #2 ran
        events.toList should be(List("start:Alpha#2", "done:Alpha#2(state.n=1)"))
      }
    }

    "resume by cursor set skips the matching prefix" in {
      val (m, events) = makeMonarch()
      m.initialize(Seq(Alpha, Beta, Alpha))
      m.resume(S(), Set("Alpha#0", "Beta#1")).map { result =>
        result.log should be(List("Alpha"))
        events.toList should be(List("start:Alpha#2", "done:Alpha#2(state.n=1)"))
      }
    }

    "route a StageFailedException through the interceptor and continue from its state" in {
      val boom = StageError("Boom#1", Some("SENSOR_ANOMALY"), "SENSOR_ANOMALY", "simulated")
      val (m, events) = makeMonarch(
        step = (stage, st) =>
          if (stage == Boom) Future.failed(StageFailedException(boom))
          else Future.successful(st.copy(log = st.log :+ nameOf(stage), n = st.n + 1)),
        interceptor = Some((cursor, err, st) =>
          Future.successful(st.copy(log = st.log :+ s"OCAP(${err.errorCode}@$cursor)", n = st.n + 1))))
      m.initialize(Seq(Alpha, Boom, Beta))
      m.process(S()).map { result =>
        result.log should be(List("Alpha", "OCAP(SENSOR_ANOMALY@Boom#1)", "Beta"))
        events.toList should be(List(
          "start:Alpha#0", "done:Alpha#0(state.n=1)",
          "start:Boom#1", "failed:Boom#1",
          "resolved:Boom#1(state.n=2)",
          "start:Beta#2", "done:Beta#2(state.n=3)"))
      }
    }

    "wrap unexpected NonFatal failures as UNEXPECTED for the interceptor" in {
      var received: Option[StageError] = None
      val (m, _) = makeMonarch(
        step = (stage, st) =>
          if (stage == Boom) Future.failed(new NullPointerException) // null message, like the original bug
          else Future.successful(st.copy(log = st.log :+ nameOf(stage))),
        interceptor = Some((cursor, err, st) => {
          received = Some(err)
          Future.successful(st)
        }))
      m.initialize(Seq(Boom))
      m.process(S()).map { _ =>
        received.get.errorCode should be("UNEXPECTED")
        received.get.detail should be("java.lang.NullPointerException") // non-null even for null-message NPEs
        received.get.stage should be("Boom#0")
      }
    }

    "fail the run when no interceptor is configured" in {
      val (m, events) = makeMonarch(
        step = (stage, st) =>
          if (stage == Boom) Future.failed(StageFailedException(StageError("x", None, "BOOM", "hard stop")))
          else Future.successful(st))
      m.initialize(Seq(Alpha, Boom))
      recoverToSucceededIf[StageFailedException](m.process(S())).map { _ =>
        events.toList should be(List("start:Alpha#0", "done:Alpha#0(state.n=0)", "start:Boom#1", "failed:Boom#1"))
      }
    }

    "terminate silently with StaleRun when the generation token went stale" in {
      val (m, events) = makeMonarch(runToken = () => false)
      m.initialize(Seq(Alpha, Beta))
      recoverToSucceededIf[StaleRun.type](m.process(S())).map { _ =>
        events.toList should be(Nil) // no stage started, no hook fired
      }
    }

    "staleness at recovery wins over a failing stage (guard-first)" in {
      val (m, _) = makeMonarch(
        step = (_, st) => Future.failed(new RuntimeException("late failure from a dying run")),
        interceptor = Some((_, _, st) => Future.successful(st)),
        runToken = () => false)
      m.initialize(Seq(Boom))
      recoverToSucceededIf[StaleRun.type](m.process(S()))
    }

    "complete immediately on an empty queue" in {
      val (m, _) = makeMonarch()
      m.process(S(n = 7)).map(_.n should be(7))
    }
  }
}
