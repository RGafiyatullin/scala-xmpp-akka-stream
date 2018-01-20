package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import com.github.rgafiyatullin.xml.utf8.Utf8InputStream

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}

object Utf8Decode {
  type StageShape = FlowShape[ByteString, String]

  final class Api(actorRef: ActorRef)

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[ByteString] = stage.shape.in
    val outlet: Outlet[String] = stage.shape.out

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

    def receive(sender: ActorRef, message: Any): Unit = ()

    override def preStart(): Unit = {
      super.preStart()
      apiPromise.success(new Api(getStageActor((receive _).tupled).ref))
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

final case class Utf8Decode() extends GraphStageWithMaterializedValue[Utf8Decode.StageShape, Future[Utf8Decode.Api]] {
  val inlet: Inlet[ByteString] = Inlet("In:ByteString")
  val outlet: Outlet[String] = Outlet("Out:String")
  override def shape: FlowShape[ByteString, String] = FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Utf8Decode.Api]) = {
    val apiPromise = Promise[Utf8Decode.Api]()
    val logic = new Utf8Decode.Logic(this, apiPromise)
    (logic, apiPromise.future)
  }
}
