package com.github.rgafiyatullin.xmpp_akka_stream.stages.mutable

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.stream._
import akka.stream.stage._
import akka.util.Timeout
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_protocol.streams.{InputStream, StreamEvent}

import scala.concurrent.{Future, Promise}

object StreamEventDecode {
  type StageShape = FlowShape[HighLevelEvent, StreamEvent]

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
    val outlet: Outlet[StreamEvent] = stage.shape.out

    var inputStream: InputStream = InputStream.empty
    var upstreamFinished: Boolean = false

    def feedInputStream(hle: HighLevelEvent): Unit =
      inputStream = inputStream.in(hle)

    def maybePush(): Boolean =
      if (isAvailable(outlet)) {
        val (seOption, inputStreamNext) = inputStream.out
        inputStream = inputStreamNext
        (seOption, upstreamFinished) match {
          case (None, true) =>
            completeStage()
            false
          case (None, false) =>
            true
          case (Some(se), _) =>
            push(outlet, se)
            false
        }
      } else false

    def maybePull(): Unit =
      if (!hasBeenPulled(inlet))
        pull(inlet)

    def receive(sender: ActorRef, message: Any): Unit =
      message match {
        case Api.Reset() =>
          inputStream = InputStream.empty
          sender ! Status.Success(Done)
      }

    override def preStart(): Unit = {
      super.preStart()
      apiPromise.success(new Api(getStageActor((receive _).tupled).ref))
      ()
    }

    setHandler(inlet, new InHandler {
      override def onPush(): Unit = {
        feedInputStream(grab(inlet))
        if (maybePush()) maybePull()
      }

      override def onUpstreamFinish(): Unit = {
        upstreamFinished = true
        if (maybePush()) maybePull()
      }
    })
    setHandler(outlet, new OutHandler {
      override def onPull(): Unit = {
        if (maybePush()) maybePull()
      }
    })
  }
}

final case class StreamEventDecode() extends GraphStageWithMaterializedValue[StreamEventDecode.StageShape, Future[StreamEventDecode.Api]] {
  val inlet: Inlet[HighLevelEvent] = Inlet("StreamEventDecode.In")
  val outlet: Outlet[StreamEvent] = Outlet("StreamEventDecode.Out")

  override def shape: FlowShape[HighLevelEvent, StreamEvent] = FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[StreamEventDecode.Api]) = {
    val apiPromise = Promise[StreamEventDecode.Api]()
    val logic = new StreamEventDecode.Logic(this, apiPromise)

    (logic, apiPromise.future)
  }
}
