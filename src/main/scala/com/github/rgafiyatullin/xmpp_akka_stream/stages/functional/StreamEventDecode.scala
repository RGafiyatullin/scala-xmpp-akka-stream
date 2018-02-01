package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, Status}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.Timeout
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.StreamEventDecode.MaterializedValue
import com.github.rgafiyatullin.xmpp_protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.xmpp_protocol.streams.{InputStream, StreamEvent}

import scala.concurrent.{ExecutionContext, Future, Promise}

object StreamEventDecode {
  val inlet: Inlet[HighLevelEvent] = Inlet("StreamEventDecode.In")
  val outlet: Outlet[StreamEvent] = Outlet("StreamEventDecode.Out")

  type MaterializedValue = Future[Api]
  type Shape = FlowShape[HighLevelEvent, StreamEvent]

  object messages {
    case object Reset
  }

  final class Api(
    actorRef: ActorRef,
    val executionContext: ExecutionContext)
  {
    import akka.pattern.ask

    def reset()(implicit timeout: Timeout): Future[Done] =
      actorRef.ask(messages.Reset).mapTo[Done]
  }

  sealed trait State extends Stage.State[StreamEventDecode]

  object State {
    def create(apiPromise: Promise[Api]): State =
      StateInitial(apiPromise)
  }

  final case class StateInitial(apiPromise: Promise[Api]) extends State {
    override def receiveEnabled: Boolean = true

    override def preStart(ctx: PreStartContext[StreamEventDecode]): PreStartContext[StreamEventDecode] = {
      apiPromise.success(new Api(ctx.stageActorRef, ctx.executionContext))
      ctx.withState(StateNormal())
    }

    override def postStop(ctx: PostStopContext[StreamEventDecode]): PostStopContext[StreamEventDecode] = {
      apiPromise.tryFailure(new UninitializedError())
      ctx
    }
  }

  final case class StateInletClosed(is: InputStream, failureOption: Option[Throwable]) extends State {
    def withIS(isNext: InputStream): StateInletClosed =
      copy(is = isNext)

    override def outletOnPull(ctx: OutletPulledContext[StreamEventDecode]): OutletPulledContext[StreamEventDecode] =
      (is.out, failureOption) match {
        case ((None, isNext), None) =>
          ctx
            .withState(withIS(isNext))
            .completeStage()

        case ((None, isNext), Some(reason)) =>
          ctx
            .withState(withIS(isNext))
            .failStage(reason)

        case ((Some(se), isNext), _) =>
          ctx
            .push(outlet, se)
            .withState(withIS(isNext))
      }
  }

  final case class StateNormal(is: InputStream = InputStream.empty) extends State {
    def withIS(isNext: InputStream): StateNormal =
      copy(is = isNext)

    def isToOutput[Ctx <: Context[Ctx, StreamEventDecode]](ctx: Ctx): Ctx =
      is.out match {
        case (None, isNext) =>
          ctx.pull(inlet).withState(withIS(isNext))

        case (Some(seXSE: StreamEvent.StreamError), isNext) =>
          ctx
            .withState(withIS(isNext))
            .push(outlet, seXSE)
            .failStage(seXSE.xmppStreamError)

        case (Some(seSC: StreamEvent.StreamClose), isNext) =>
          ctx
            .withState(withIS(isNext))
            .push(outlet, seSC)
            .completeStage()

        case (Some(se), isNext) =>
          ctx.push(outlet, se).withState(withIS(isNext))
      }

    def reset: StateNormal =
      withIS(InputStream.empty)

    override def receive(ctx: ReceiveContext.NotReplied[StreamEventDecode]): ReceiveContext[StreamEventDecode] =
      ctx.handleWith {
        case messages.Reset =>
          ctx
            .reply(Status.Success(Done))
            .withState(reset)
      }

    override def inletOnUpstreamFinish(ctx: InletFinishedContext[StreamEventDecode]): InletFinishedContext[StreamEventDecode] =
      if (is.out._1.isEmpty) ctx.completeStage()
      else ctx.withState(StateInletClosed(is, None))

    override def inletOnUpstreamFailure(ctx: InletFailedContext[StreamEventDecode]): InletFailedContext[StreamEventDecode] =
      if (is.out._1.isEmpty) ctx.failStage(ctx.reason)
      else ctx.withState(StateInletClosed(is, Some(ctx.reason)))

    override def outletOnPull(ctx: OutletPulledContext[StreamEventDecode]): OutletPulledContext[StreamEventDecode] = {
      assert(ctx.outlet == outlet)
      isToOutput(ctx)
    }

    override def inletOnPush(ctx: InletPushedContext[StreamEventDecode]): InletPushedContext[StreamEventDecode] = {
      assert(ctx.inlet == inlet)

      withIS(is.in(ctx.peek(inlet)))
        .isToOutput(ctx.drop(inlet))
    }
  }
}

final case class StreamEventDecode() extends Stage[StreamEventDecode] {
  override type Shape = StreamEventDecode.Shape
  override type State = StreamEventDecode.State
  override type MatValue = StreamEventDecode.MaterializedValue

  override def shape: Shape =
    FlowShape.of(StreamEventDecode.inlet, StreamEventDecode.outlet)

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes)
  : (StreamEventDecode.State, MaterializedValue) = {
    val apiPromise = Promise[StreamEventDecode.Api]()
    val state = StreamEventDecode.State.create(apiPromise)
    (state, apiPromise.future)
  }
}
