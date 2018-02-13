package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.NotUsed
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext}
import com.github.rgafiyatullin.xml.stream_writer.high_level_writer.HighLevelWriter
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.XmlEventCodec
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.XmlEventEncode.MaterializedValue

object XmlEventEncode {
  type XmlEvent = XmlEventCodec.XmlEvent
  type MaterializedValue = NotUsed
  type Shape = FlowShape[XmlEvent, String]

  val inlet: Inlet[XmlEvent] = Inlet("XmlEventEncode.In")
  val outlet: Outlet[String] = Outlet("XmlEventEncode.Out")

  object State {
    def empty: State = State()
  }

  final case class State(hlw: HighLevelWriter = HighLevelWriter.empty) extends Stage.State[XmlEventEncode] {
    override def outletOnPull(ctx: OutletPulledContext[XmlEventEncode]): OutletPulledContext[XmlEventEncode] = {
      assert(ctx.outlet == outlet)
      ctx.pull(inlet)
    }

    private def withHLW(hlwNext: HighLevelWriter): State =
      copy(hlw = hlwNext)

    override def inletOnPush(ctx: InletPushedContext[XmlEventEncode]): InletPushedContext[XmlEventEncode] = {
      assert(ctx.inlet == inlet)
      val (outStrings, hlwNext) = hlw.in(ctx.peek(inlet)).out
      ctx
        .drop(inlet)
        .push(outlet, outStrings.mkString)
        .withState(withHLW(hlwNext))
    }
  }
}

final case class XmlEventEncode() extends Stage[XmlEventEncode] {
  override type Shape = XmlEventEncode.Shape
  override type State = XmlEventEncode.State
  override type MatValue = XmlEventEncode.MaterializedValue

  override def shape: Shape = FlowShape.of(XmlEventEncode.inlet, XmlEventEncode.outlet)

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic, inheritedAttributes: Attributes)
  : (XmlEventEncode.State, MaterializedValue) =
    (XmlEventEncode.State.empty, NotUsed)
}
