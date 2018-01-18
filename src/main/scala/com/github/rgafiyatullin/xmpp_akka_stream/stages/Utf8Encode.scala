package com.github.rgafiyatullin.xmpp_akka_stream.stages

import java.nio.charset.Charset

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString

case object Utf8Encode extends Utf8Encode

sealed abstract class Utf8Encode extends GraphStage[FlowShape[String, ByteString]] {
  val csUtf8: Charset = Charset.forName("UTF-8")
  val inlet: Inlet[String] = Inlet("In:String")
  val outlet: Outlet[ByteString] = Outlet("Out:ByteString")

  override def shape: FlowShape[String, ByteString] = FlowShape.of(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var buffer: ByteString = ByteString.empty

      def feedBuffer(s: String): Unit = {
        val byteString = ByteString(s, csUtf8)
        buffer = buffer ++ byteString
      }

      def maybePush(): Boolean =
        if (isAvailable(outlet)) {
          if (buffer.nonEmpty) {
            push(outlet, buffer)
            buffer = ByteString.empty
            false
          } else true
        }
        else false

      def maybePull(): Unit =
        if (!hasBeenPulled(inlet))
          pull(inlet)

      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          feedBuffer(grab(inlet))
          if (maybePush()) maybePull()
        }
      })
      setHandler(outlet, new OutHandler {
        override def onPull(): Unit =
          if (maybePush()) maybePull()
      })
    }
}
