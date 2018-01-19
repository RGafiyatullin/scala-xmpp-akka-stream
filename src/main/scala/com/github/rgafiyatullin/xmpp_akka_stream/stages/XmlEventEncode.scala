package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xml.stream_writer.high_level_writer.HighLevelWriter

case object XmlEventEncode extends XmlEventEncode

sealed abstract class XmlEventEncode extends GraphStage[FlowShape[HighLevelEvent, String]] {
  val inlet: Inlet[HighLevelEvent] = Inlet("In:HighLevelEvent")
  val outlet: Outlet[String] = Outlet("Out:HighLevelEvent")
  override def shape: FlowShape[HighLevelEvent, String] = FlowShape.of(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var writer: HighLevelWriter = HighLevelWriter.empty

      def feedWriter(hle: HighLevelEvent): Unit =
        writer = writer.in(hle)

      def maybePush(): Boolean =
        if (isAvailable(outlet)) {
          val (strings, writerNext) = writer.out
          if (strings.isEmpty) true
          else {
            push(outlet, strings.mkString)
            writer = writerNext
            false
          }
        } else false

      def maybePull(): Unit =
        if (!hasBeenPulled(inlet))
          pull(inlet)

      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          feedWriter(grab(inlet))
          if (maybePush()) maybePull()
        }
      })
      setHandler(outlet, new OutHandler {
        override def onPull(): Unit =
          if (maybePush()) maybePull()
      })
    }
}
