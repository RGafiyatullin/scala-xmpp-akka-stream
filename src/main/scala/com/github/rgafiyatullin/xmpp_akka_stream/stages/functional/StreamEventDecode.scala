package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.NotUsed
import akka.stream.stage.GraphStageLogic
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.StreamEventDecode.MaterializedValue
import com.github.rgafiyatullin.xmpp_protocol.streams.{InputStream, StreamEvent}

object StreamEventDecode {
  val inlet: Inlet[HighLevelEvent] = Inlet("StreamEventDecode.In")
  val outlet: Outlet[StreamEvent] = Outlet("StreamEventDecode.Out")

  type MaterializedValue = NotUsed
  type Shape = FlowShape[HighLevelEvent, StreamEvent]

  sealed trait State extends Stage.State[StreamEventDecode]


  object State {
    def empty: State = StateNormal()
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

        case (Some(se), isNext) =>
          ctx.push(outlet, se).withState(withIS(isNext))
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
  : (StreamEventDecode.State, MaterializedValue) =
    (StreamEventDecode.State.empty, NotUsed)
}
