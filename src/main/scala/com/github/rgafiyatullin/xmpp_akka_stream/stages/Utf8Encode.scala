package com.github.rgafiyatullin.xmpp_akka_stream.stages

import java.nio.charset.Charset

import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.{Future, Promise}

object Utf8Encode {
  type StageShape = FlowShape[String, ByteString]

  private val csUtf8: Charset = Charset.forName("UTF-8")

  final class Api(actorRef: ActorRef)

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[String] = stage.shape.in
    val outlet: Outlet[ByteString] = stage.shape.out
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

    def receive(sender: ActorRef, message: Any): Unit = ()

    override def preStart(): Unit = {
      super.preStart()
      apiPromise.success(new Api(getStageActor((receive _).tupled).ref))
    }
  }
}

final case class Utf8Encode() extends GraphStageWithMaterializedValue[Utf8Encode.StageShape, Future[Utf8Encode.Api]] {
  val inlet: Inlet[String] = Inlet("In:String")
  val outlet: Outlet[ByteString] = Outlet("Out:ByteString")

  override def shape: Utf8Encode.StageShape= FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Utf8Encode.Api]) = {
    val apiPromise: Promise[Utf8Encode.Api] = Promise()
    val logic = new Utf8Encode.Logic(this, apiPromise)
    (logic, apiPromise.future)
  }
}
