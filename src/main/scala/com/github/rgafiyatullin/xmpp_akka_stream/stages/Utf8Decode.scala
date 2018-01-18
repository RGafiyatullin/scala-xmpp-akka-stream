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
      var itemRequested: Boolean = false
      var decoder: Utf8InputStream = Utf8InputStream.empty

      def flushOutput(): Unit = {
        require(itemRequested)
        if (outputBuffer.nonEmpty) {

          push(outlet, outputBuffer.mkString)

          itemRequested = false
          outputBuffer = StringBuilder.newBuilder
        }
      }

      def requestItem(): Unit = {
        if (!hasBeenPulled(inlet))
          pull(inlet)
        itemRequested = true
      }

      def bytesAvailable(bs: ByteString): Unit =
        bytesAvailableLoop(bs)

      @tailrec
      def bytesAvailableLoop(bs: ByteString): Unit =
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
          case Some(tail) => bytesAvailableLoop(tail)
        }

      setHandler(inlet, new InHandler {
        override def onPush(): Unit = {
          bytesAvailable(grab(inlet))
          if (itemRequested)
            flushOutput()
        }
      })
      setHandler(outlet, new OutHandler {
        override def onPull(): Unit = {
          requestItem()
          flushOutput()
        }
      })
    }
}
