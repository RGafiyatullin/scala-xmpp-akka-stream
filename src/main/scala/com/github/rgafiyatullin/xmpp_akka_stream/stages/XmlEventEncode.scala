package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xml.stream_writer.high_level_writer.HighLevelWriter

import scala.concurrent.{Future, Promise}

object XmlEventEncode {
  type StageShape = FlowShape[HighLevelEvent, String]

  final class Api(actorRef: ActorRef)

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[HighLevelEvent] = stage.shape.in
    val outlet: Outlet[String] = stage.shape.out

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

    def receive(sender: ActorRef, message: Any): Unit = ()

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
  val inlet: Inlet[HighLevelEvent] = Inlet("In:HighLevelEvent")
  val outlet: Outlet[String] = Outlet("Out:HighLevelEvent")

  override def shape: XmlEventEncode.StageShape = FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[XmlEventEncode.Api]) = {
    val apiPromise = Promise[XmlEventEncode.Api]()
    val logic = new XmlEventEncode.Logic(this, apiPromise)

    (logic, apiPromise.future)
  }
}
