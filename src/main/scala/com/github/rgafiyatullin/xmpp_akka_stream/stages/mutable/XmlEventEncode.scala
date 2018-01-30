package com.github.rgafiyatullin.xmpp_akka_stream.stages.mutable

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.stream._
import akka.stream.stage._
import akka.util.Timeout
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xml.stream_writer.high_level_writer.HighLevelWriter

import scala.concurrent.{Future, Promise}

object XmlEventEncode {
  type StageShape = FlowShape[HighLevelEvent, String]

  final class Api(actorRef: ActorRef) {
    import akka.pattern.ask
    def reset()(implicit timeout: Timeout): Future[Done] =
      actorRef.ask(Api.Reset()).mapTo[Done]
  }
  private object Api {
    final case class Reset()
  }

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[HighLevelEvent] = stage.shape.in
    val outlet: Outlet[String] = stage.shape.out

    val emptyWriter: HighLevelWriter =
      HighLevelWriter.empty

    var writer: HighLevelWriter = emptyWriter

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

    def receive(sender: ActorRef, message: Any): Unit =
      message match {
        case Api.Reset =>
          writer = emptyWriter
          sender ! Status.Success(Done)
      }

    override def preStart(): Unit = {
      super.preStart()
      apiPromise.success(new Api(getStageActor((receive _).tupled).ref))
    }

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

final case class XmlEventEncode() extends GraphStageWithMaterializedValue[XmlEventEncode.StageShape, Future[XmlEventEncode.Api]] {
  val inlet: Inlet[HighLevelEvent] = Inlet("XmlEventEncode.In")
  val outlet: Outlet[String] = Outlet("XmlEventEncode.Out")

  override def shape: XmlEventEncode.StageShape = FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[XmlEventEncode.Api]) = {
    val apiPromise = Promise[XmlEventEncode.Api]()
    val logic = new XmlEventEncode.Logic(this, apiPromise)

    (logic, apiPromise.future)
  }
}
