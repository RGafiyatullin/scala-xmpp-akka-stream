package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_protocol.streams.{OutputStream, StreamEvent}

case object StreamEventEncode extends StreamEventEncode {}

sealed abstract class StreamEventEncode extends GraphStage[FlowShape[StreamEvent, HighLevelEvent]] {
  val inlet: Inlet[StreamEvent] = Inlet("In:StreamEvent")
  val outlet: Outlet[HighLevelEvent] = Outlet("Out:String")

  override def shape: FlowShape[StreamEvent, HighLevelEvent] =
    FlowShape.of(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var outputStream: OutputStream = OutputStream.empty

    private def maybePull(): Unit =
      if (!hasBeenPulled(inlet))
        pull(inlet)

    private def maybePush(): Boolean =
      outputStream.outSingleOption match {
        case None => true
        case Some((hle, outputStreamNext)) =>
          outputStream = outputStreamNext
          push(outlet, hle)
          false
      }

    private def feedOutputStream(se: StreamEvent): Unit =
      outputStream = outputStream.in(se)

    setHandler(inlet, new InHandler {
      override def onPush(): Unit = {
        feedOutputStream(grab(inlet))
        if (maybePush()) maybePull()
      }
    })

    setHandler(outlet, new OutHandler {
      override def onPull(): Unit =
        if (maybePush()) maybePull()

    })
  }
}
