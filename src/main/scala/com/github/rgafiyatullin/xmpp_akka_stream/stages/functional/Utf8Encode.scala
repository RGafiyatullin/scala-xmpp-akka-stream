package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import java.nio.charset.Charset

import akka.NotUsed
import akka.stream.stage.GraphStageLogic
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext}

object Utf8Encode {
  type Shape = FlowShape[String, ByteString]
  type MaterializedValue = NotUsed

  private val csUtf8: Charset = Charset.forName("UTF-8")

  val inlet: Inlet[String] = Inlet("Utf8Encode.In")
  val outlet: Outlet[ByteString] = Outlet("Utf8Encode.Out")

  object State {
    def empty: State =
      State()
  }
  final case class State() extends Stage.State[Utf8Encode] {
    override def outletOnPull(ctx: OutletPulledContext[Utf8Encode]): OutletPulledContext[Utf8Encode] = {
      assert(ctx.outlet == outlet)
      ctx.pull(inlet)
    }

    override def inletOnPush(ctx: InletPushedContext[Utf8Encode]): InletPushedContext[Utf8Encode] = {
      assert(ctx.inlet == inlet)
      ctx
        .push(outlet, ByteString(ctx.peek(inlet).getBytes(csUtf8)))
        .drop(inlet)
    }
  }


}

final case class Utf8Encode() extends Stage[Utf8Encode] {
  override type Shape = Utf8Encode.Shape
  override type State = Utf8Encode.State
  override type MatValue = Utf8Encode.MaterializedValue

  override def shape: FlowShape[String, ByteString] =
    FlowShape.of(Utf8Encode.inlet, Utf8Encode.outlet)

  override def initialStateAndMatValue(
    logic: GraphStageLogic,
    inheritedAttributes: Attributes)
  : (Utf8Encode.State, Utf8Encode.MaterializedValue) =
    (Utf8Encode.State.empty, NotUsed)
}
