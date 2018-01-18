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
      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          val string = grab(inlet)
          val byteString = ByteString(string, csUtf8)
          push(outlet, byteString)
        }
      })
      setHandler(outlet, new OutHandler {
        override def onPull(): Unit =
          pull(inlet)
      })
    }
}
