package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.NotUsed
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.GraphStageLogic
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{Context, InletFinishedContext, InletPushedContext, OutletPulledContext}
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.StreamEventEncode.MaterializedValue
import com.github.rgafiyatullin.xmpp_protocol.streams.{OutputStream, StreamEvent}

object StreamEventEncode {
  val inlet: Inlet[StreamEvent] = Inlet("StreamEventEncode.In")
  val outlet: Outlet[HighLevelEvent] = Outlet("StreamEventEncode.Out")

  type MaterializedValue = NotUsed
  type Shape = FlowShape[StreamEvent, HighLevelEvent]
  sealed trait State extends Stage.State[StreamEventEncode]

  object State extends Stage.State[StreamEventEncode] {
    def empty: State = StateNormal()
  }

  final case class StateInletClosed(os: OutputStream, failureOption: Option[Throwable]) extends State {
    def withOS(osNext: OutputStream): StateInletClosed =
      copy(os = osNext)

    override def outletOnPull(ctx: OutletPulledContext[StreamEventEncode]): OutletPulledContext[StreamEventEncode] =
      (os.outSingleOption, failureOption) match {
        case (None, Some(reason)) =>
          ctx.failStage(reason)

        case (None, None) =>
          ctx.completeStage()

        case (Some((hle, osNext)), _) =>
          ctx.push(outlet, hle).withState(withOS(osNext))
      }
  }

  final case class StateNormal(os: OutputStream = OutputStream.empty) extends State {
    def withOS(osNext: OutputStream): StateNormal =
      copy(os = osNext)

    def osToOutput[Ctx <: Context[Ctx, StreamEventEncode]](ctx: Ctx): Ctx =
      os.outSingleOption match {
        case None =>
          ctx
            .pull(inlet)

        case Some((hle, osNext)) =>
          ctx
            .push(outlet, hle)
            .withState(withOS(osNext))
      }


    override def inletOnUpstreamFinish(ctx: InletFinishedContext[StreamEventEncode]): InletFinishedContext[StreamEventEncode] =
      if (os.outSingleOption.isEmpty) ctx.completeStage()
      else ctx.withState(StateInletClosed(os, None))

    override def inletOnPush(ctx: InletPushedContext[StreamEventEncode]): InletPushedContext[StreamEventEncode] = {
      assert(ctx.inlet == inlet)

      withOS(os.in(ctx.peek(inlet)))
        .osToOutput(ctx.drop(inlet))
    }

    override def outletOnPull(ctx: OutletPulledContext[StreamEventEncode]): OutletPulledContext[StreamEventEncode] = {
      assert(ctx.outlet == outlet)

      osToOutput(ctx)
    }
  }
}

final case class StreamEventEncode() extends Stage[StreamEventEncode] {
  override type Shape = StreamEventEncode.Shape
  override type State = StreamEventEncode.State
  override type MatValue = StreamEventEncode.MaterializedValue

  override def shape: Shape =
    FlowShape.of(StreamEventEncode.inlet, StreamEventEncode.outlet)

  override def initialStateAndMatValue
    (logic: GraphStageLogic, inheritedAttributes: Attributes)
  : (StreamEventEncode.State, MaterializedValue) =
    (StreamEventEncode.State.empty, NotUsed)
}
