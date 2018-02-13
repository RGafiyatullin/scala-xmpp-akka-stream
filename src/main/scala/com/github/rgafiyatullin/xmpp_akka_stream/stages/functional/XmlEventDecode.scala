package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.Timeout
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._
import com.github.rgafiyatullin.xml.stream_parser.high_level_parser.{HighLevelParser, HighLevelParserError}
import com.github.rgafiyatullin.xml.stream_parser.low_level_parser.LowLevelParserError
import com.github.rgafiyatullin.xml.stream_parser.tokenizer.TokenizerError
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.XmlEventCodec
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.XmlEventDecode.MaterializedValue

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object XmlEventDecode {
  type XmlEvent = XmlEventCodec.XmlEvent
  val inlet: Inlet[String] = Inlet("XmlEventDecode.In")
  val outlet: Outlet[XmlEvent] = Outlet("XmlEventDecode.Out")

  type Shape = FlowShape[String, XmlEvent]
  type MaterializedValue = Future[Api]

  private object messages {
    case object Reset
  }

  final class Api(actorRef: ActorRef, executionContext: ExecutionContext) {
    import akka.pattern.ask
    def reset()(implicit timeout: Timeout): Future[Done] =
      actorRef.ask(messages.Reset).mapTo[Done]
  }

  object State {
    def emptyHLP: HighLevelParser =
      HighLevelParser.empty.withoutPosition

    def create(apiPromise: Promise[Api]): State = StateInitial(apiPromise)
  }

  sealed trait State extends Stage.State[XmlEventDecode] {
    def hlp: HighLevelParser

    def hlpOut: Option[(XmlEvent, HighLevelParser)] =
      Try(hlp.out)
        .map(Some(_))
        .recover {
          // Here we are matching a buffer underrun as thrown by the HighLevelParser
          // TODO: custom matcher in order to continue parsing from the state it's been thrown
          case HighLevelParserError.LowLevel(
                _, LowLevelParserError.TokError(
                _, TokenizerError.InputBufferUnderrun(_)))
          =>
            None
        }
        .get
  }

  final case class StateInitial(apiPromise: Promise[Api]) extends State {
    override def hlp: HighLevelParser = throw new IllegalStateException("Stage not yet initialized")

    override def receiveEnabled: Boolean = true

    override def preStart(ctx: PreStartContext[XmlEventDecode]): PreStartContext[XmlEventDecode] = {
      apiPromise.success(new Api(ctx.stageActorRef, ctx.executionContext))
      ctx.withState(StateNormal())
    }

    override def postStop(ctx: PostStopContext[XmlEventDecode]): PostStopContext[XmlEventDecode] = {
      apiPromise.tryFailure(new UninitializedError())
      ctx
    }
  }


  final case class StateInletClosed(hlp: HighLevelParser, failureOption: Option[Throwable]) extends State {
    def withHLP(hlpNext: HighLevelParser): StateInletClosed =
      copy(hlp = hlpNext)

    override def outletOnPull(ctx: OutletPulledContext[XmlEventDecode]): OutletPulledContext[XmlEventDecode] =
      (hlpOut, failureOption) match {
        case (None, None) =>
          ctx.completeStage()

        case (None, Some(reason)) =>
          ctx.failStage(reason)

        case (Some((hle, hlpNext)), _) =>
          ctx
            .withState(withHLP(hlpNext))
            .push(outlet, hle)
      }
  }

  final case class StateNormal(hlp: HighLevelParser = State.emptyHLP) extends State {
    def reset: StateNormal =
      withHLP(State.emptyHLP)

    def withHLP(hlpNext: HighLevelParser): StateNormal =
      copy(hlp = hlpNext)

    def feedHLP(input: String): StateNormal =
      withHLP(hlp.in(input))

    def withHLPOutOption: Option[(XmlEvent, StateNormal)] =
      hlpOut
        .map { case (hle, hlpNext) =>
          (hle, withHLP(hlpNext))
        }

    def hasPendingHLEs: Boolean =
      hlpOut.isDefined

    override def receive(ctx: ReceiveContext.NotReplied[XmlEventDecode]): ReceiveContext[XmlEventDecode] =
      ctx.handleWith {
        case messages.Reset =>
          ctx
            .withState(reset)
            .reply(Status.Success(Done))
      }

    override def inletOnUpstreamFinish(ctx: InletFinishedContext[XmlEventDecode]): InletFinishedContext[XmlEventDecode] =
      if (hasPendingHLEs) ctx.withState(StateInletClosed(hlp, None))
      else ctx.completeStage()


    override def inletOnUpstreamFailure(ctx: InletFailedContext[XmlEventDecode]): InletFailedContext[XmlEventDecode] =
      if (hasPendingHLEs) ctx.withState(StateInletClosed(hlp, Some(ctx.reason)))
      else ctx.failStage(ctx.reason)

    override def inletOnPush(ctx: InletPushedContext[XmlEventDecode]): InletPushedContext[XmlEventDecode] = {
      assert(ctx.inlet == inlet)

      val stateHlpFed = feedHLP(ctx.peek(inlet))
      val ctxDropped = ctx.drop(inlet).withState(stateHlpFed)

      stateHlpFed.withHLPOutOption match {
        case None =>
          ctxDropped
            .pull(inlet)

        case Some((hle, stateNext)) =>
          ctxDropped
            .withState(stateNext)
            .push(outlet, hle)
      }
    }

    override def outletOnPull(ctx: OutletPulledContext[XmlEventDecode]): OutletPulledContext[XmlEventDecode] = {
      assert(ctx.outlet == outlet)

      withHLPOutOption match {
        case None =>
          ctx.pull(inlet)

        case Some((hle, stateNext)) =>
          ctx
            .withState(stateNext)
            .push(outlet, hle)
      }
    }
  }
}

final case class XmlEventDecode() extends Stage[XmlEventDecode] {
  override type Shape = XmlEventDecode.Shape
  override type State = XmlEventDecode.State
  override type MatValue = XmlEventDecode.MaterializedValue

  override def shape: Shape =
    FlowShape.of(XmlEventDecode.inlet, XmlEventDecode.outlet)

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes)
  : (XmlEventDecode.State, MaterializedValue) = {
    val apiPromise = Promise[XmlEventDecode.Api]()
    val state = XmlEventDecode.State.create(apiPromise)
    (state, apiPromise.future)
  }
}
