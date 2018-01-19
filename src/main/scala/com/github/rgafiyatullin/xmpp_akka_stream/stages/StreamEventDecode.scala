package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage._
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_protocol.streams.{InputStream, StreamEvent}

case object StreamEventDecode extends StreamEventDecode

abstract class StreamEventDecode extends GraphStage[FlowShape[HighLevelEvent, StreamEvent]] {
  val inlet: Inlet[HighLevelEvent] = Inlet("In:HighLevelEvent")
  val outlet: Outlet[StreamEvent] = Outlet("Out:StreamEvent")
  override def shape: FlowShape[HighLevelEvent, StreamEvent] = FlowShape.of(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape){
      var inputStream: InputStream = InputStream.empty
      var upstreamFinished: Boolean = false

      def feedInputStream(hle: HighLevelEvent): Unit =
        inputStream = inputStream.in(hle)

      def maybePush(): Boolean =
        if (isAvailable(outlet)) {
          val (seOption, inputStreamNext) = inputStream.out
          inputStream = inputStreamNext
          (seOption, upstreamFinished) match {
            case (None, true) =>
              completeStage()
              false
            case (None, false) =>
              true
            case (Some(se), _) =>
              push(outlet, se)
              false
          }
        } else false

      def maybePull(): Unit =
        if (!hasBeenPulled(inlet))
          pull(inlet)

      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          feedInputStream(grab(inlet))
          if (maybePush()) maybePull()
        }

        override def onUpstreamFinish(): Unit = {
          upstreamFinished = true
          if (maybePush()) maybePull()
        }
      })
      setHandler(outlet, new OutHandler {
        override def onPull(): Unit = {
          if (maybePush()) maybePull()
        }
      })
    }
}
