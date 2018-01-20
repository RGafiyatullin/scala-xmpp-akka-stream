package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.actor.ActorRef
import akka.stream._
import akka.stream.stage._
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_protocol.streams.{OutputStream, StreamEvent}

import scala.concurrent.{Future, Promise}

object StreamEventEncode {
  type StageShape = FlowShape[StreamEvent, HighLevelEvent]

  final class Api(actorRef: ActorRef)

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[StreamEvent] = stage.shape.in
    val outlet: Outlet[HighLevelEvent] = stage.shape.out

    var outputStream: OutputStream = OutputStream.empty

    def maybePull(): Unit =
      if (!hasBeenPulled(inlet))
        pull(inlet)

    def maybePush(): Boolean =
      outputStream.outSingleOption match {
        case None => true
        case Some((hle, outputStreamNext)) =>
          outputStream = outputStreamNext
          push(outlet, hle)
          false
      }

    def feedOutputStream(se: StreamEvent): Unit =
      outputStream = outputStream.in(se)

    def receive(sender: ActorRef, message: Any): Unit = ()

    override def preStart(): Unit = {
      super.preStart()
      apiPromise.success(new Api(getStageActor((receive _).tupled).ref))
    }

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

final case class StreamEventEncode() extends GraphStageWithMaterializedValue[StreamEventEncode.StageShape, Future[StreamEventEncode.Api]] {
  val inlet: Inlet[StreamEvent] = Inlet("In:StreamEvent")
  val outlet: Outlet[HighLevelEvent] = Outlet("Out:String")

  override def shape: FlowShape[StreamEvent, HighLevelEvent] =
    FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[StreamEventEncode.Api]) = {
    val apiPromise = Promise[StreamEventEncode.Api]()
    val logic = new StreamEventEncode.Logic(this, apiPromise)

    (logic, apiPromise.future)
  }
}
