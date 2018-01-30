package com.github.rgafiyatullin.xmpp_akka_stream.stages.mutable

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.stream._
import akka.stream.stage._
import akka.util.Timeout
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_protocol.streams.{OutputStream, StreamEvent}

import scala.concurrent.{Future, Promise}

object StreamEventEncode {
  type StageShape = FlowShape[StreamEvent, HighLevelEvent]

  final class Api(actorRef: ActorRef) {
    import akka.pattern.ask

    def reset()(implicit timeout: Timeout): Future[Done] =
      actorRef.ask(Api.Reset()).mapTo[Done]
  }

  private object Api {
    final case class Reset()
  }

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[StreamEvent] = stage.shape.in
    val outlet: Outlet[HighLevelEvent] = stage.shape.out

    val emptyOutputStream: OutputStream = OutputStream.empty
    var outputStream: OutputStream = emptyOutputStream

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

    def receive(sender: ActorRef, message: Any): Unit =
      message match {
        case Api.Reset() =>
          outputStream = emptyOutputStream
          sender ! Status.Success(Done)
      }

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
  val inlet: Inlet[StreamEvent] = Inlet("StreamEventEncode.In")
  val outlet: Outlet[HighLevelEvent] = Outlet("StreamEventEncode.Out")

  override def shape: FlowShape[StreamEvent, HighLevelEvent] =
    FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[StreamEventEncode.Api]) = {
    val apiPromise = Promise[StreamEventEncode.Api]()
    val logic = new StreamEventEncode.Logic(this, apiPromise)

    (logic, apiPromise.future)
  }
}
