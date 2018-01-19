package com.github.rgafiyatullin.xmpp_akka_stream.stages

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xml.stream_parser.high_level_parser.{HighLevelParser, HighLevelParserError}
import com.github.rgafiyatullin.xml.stream_parser.low_level_parser.LowLevelParserError
import com.github.rgafiyatullin.xml.stream_parser.tokenizer.TokenizerError

case object XmlEventDecode extends XmlEventDecode

abstract class XmlEventDecode extends GraphStage[FlowShape[String, HighLevelEvent]] {
  val inlet: Inlet[String] = Inlet("In:String")
  val outlet: Outlet[HighLevelEvent] = Outlet("Out:HighLevelEvent")
  override def shape: FlowShape[String, HighLevelEvent] = FlowShape.of(inlet, outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var parser: HighLevelParser = HighLevelParser.empty.withoutPosition
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
