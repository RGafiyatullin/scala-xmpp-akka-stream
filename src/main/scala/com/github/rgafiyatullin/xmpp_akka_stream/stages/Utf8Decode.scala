package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.github.rgafiyatullin.xml.utf8.Utf8InputStream

import scala.annotation.tailrec

case object Utf8Decode extends Utf8Decode

sealed abstract class Utf8Decode extends GraphStage[FlowShape[ByteString, String]] {
  val inlet: Inlet[ByteString] = Inlet("In:ByteString")
  val outlet: Outlet[String] = Outlet("Out:String")
  override def shape: FlowShape[ByteString, String] = FlowShape.of(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var outputBuffer: StringBuilder = StringBuilder.newBuilder
      var decoder: Utf8InputStream = Utf8InputStream.empty

      def maybePush(): Boolean =
        if (isAvailable(outlet)) {
          if (outputBuffer.nonEmpty) {
            push(outlet, outputBuffer.mkString)
            outputBuffer = StringBuilder.newBuilder
            false
          }
          else true
        } else false

      def maybePull(): Unit =
        if (!hasBeenPulled(inlet))
          pull(inlet)

      def feedDecoder(bs: ByteString): Unit =
        feedDecoderLoop(bs)

      @tailrec
      private def feedDecoderLoop(bs: ByteString): Unit =
        bs.headOption.flatMap(
          decoder.in(_).map(_.out) match {
            case Left(utf8Error) =>
              fail(outlet, utf8Error)

              None

            case Right((charOption, decoderNext)) =>
              decoder = decoderNext
              charOption.foreach { ch => outputBuffer = outputBuffer.append(ch) }

              Some(bs.tail)
          }
        ) match {
          case None => ()
          case Some(tail) => feedDecoderLoop(tail)
        }

      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          feedDecoder(grab(inlet))
          if (maybePush()) maybePull()
        }
      })
      setHandler(outlet, new OutHandler {
        override def onPull(): Unit =
          if (maybePush()) maybePull()
      })
    }
}
