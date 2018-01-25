package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.stream._
import akka.stream.stage._
import akka.util.Timeout
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xml.stream_parser.high_level_parser.{HighLevelParser, HighLevelParserError}
import com.github.rgafiyatullin.xml.stream_parser.low_level_parser.LowLevelParserError
import com.github.rgafiyatullin.xml.stream_parser.tokenizer.TokenizerError

import scala.concurrent.{Future, Promise}

object XmlEventDecode {
  type StageShape = FlowShape[String, HighLevelEvent]

  final class Api(actorRef: ActorRef) {
    import akka.pattern.ask

    def reset()(implicit timeout: Timeout): Future[Done] =
      actorRef.ask(Api.Reset()).mapTo[Done]
  }

  private object Api {
    final case class Reset()
  }

  private final class Logic(stage: Graph[StageShape, _], apiPromise: Promise[Api]) extends GraphStageLogic(stage.shape) {
    val inlet: Inlet[String] = stage.shape.in
    val outlet: Outlet[HighLevelEvent] = stage.shape.out

    val emptyParser: HighLevelParser =
      HighLevelParser.empty.withoutPosition

    var parser: HighLevelParser = emptyParser
    var upstreamFinished: Boolean = false

    def feedParser(s: String): Unit =
      parser = parser.in(s)

    def maybePush(): Boolean =
      if (isAvailable(outlet)) {
        try {
          val (hle, parserNext) = parser.out
          parser = parserNext
          push(outlet, hle)
          false
        } catch {
          case HighLevelParserError.LowLevel(
          _, LowLevelParserError.TokError(
          _, TokenizerError.InputBufferUnderrun(_)))
          =>
            if (upstreamFinished) {
              completeStage()
              false
            } else true
        }
      } else false


    def maybePull(): Unit =
      if (!hasBeenPulled(inlet))
        pull(inlet)

    def receive(sender: ActorRef, message: Any): Unit =
      message match {
        case Api.Reset() =>
          parser = emptyParser
          sender ! Status.Success(Done)
      }

    override def preStart(): Unit = {
      super.preStart()
      apiPromise.success(new Api(getStageActor((receive _).tupled).ref))
    }

    setHandler(inlet, new InHandler {
      override def onPush(): Unit = {
        feedParser(grab(inlet))
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

final case class XmlEventDecode() extends GraphStageWithMaterializedValue[XmlEventDecode.StageShape, Future[XmlEventDecode.Api]] {
  val inlet: Inlet[String] = Inlet("XmlEventDecode.In")
  val outlet: Outlet[HighLevelEvent] = Outlet("XmlEventDecode.Out")
  override def shape: XmlEventDecode.StageShape = FlowShape.of(inlet, outlet)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[XmlEventDecode.Api]) = {
    val apiPromise = Promise[XmlEventDecode.Api]()
    val logic = new XmlEventDecode.Logic(this, apiPromise)

    (logic, apiPromise.future)
  }
}
