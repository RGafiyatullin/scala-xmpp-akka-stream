package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.NotUsed
import akka.stream.stage.GraphStageLogic
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletFailedContext, InletFinishedContext, InletPushedContext, OutletPulledContext}
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xml.stream_parser.high_level_parser.{HighLevelParser, HighLevelParserError}
import com.github.rgafiyatullin.xml.stream_parser.low_level_parser.LowLevelParserError
import com.github.rgafiyatullin.xml.stream_parser.tokenizer.TokenizerError
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.XmlEventDecode.MaterializedValue

import scala.util.Try

object XmlEventDecode {
  val inlet: Inlet[String] = Inlet("XmlEventDecode.In")
  val outlet: Outlet[HighLevelEvent] = Outlet("XmlEventDecode.Out")

  type Shape = FlowShape[String, HighLevelEvent]
  type MaterializedValue = NotUsed

  object State {
    def emptyHLP: HighLevelParser = HighLevelParser.empty.withoutPosition

    def empty: State = StateNormal()
  }

  sealed trait State extends Stage.State[XmlEventDecode] {
    def hlp: HighLevelParser

    def hlpOut: Option[(HighLevelEvent, HighLevelParser)] =
      Try(hlp.out)
        .map(Some(_))
        .recover {
          // Here we are matching a buffer underrun as thrown by the HighLevelParser
          // TODO: custom matcher in order to continue parsing from the state it's been thrown
          case HighLevelParserError.LowLevel(
          _, LowLevelParserError.TokError(
          _, TokenizerError.InputBufferUnderrun(_)))
          =>
            None
        }
        .get
  }

  final case class StateInletClosed(hlp: HighLevelParser, failureOption: Option[Throwable]) extends State {
    def withHLP(hlpNext: HighLevelParser): StateInletClosed =
      copy(hlp = hlpNext)

    override def outletOnPull(ctx: OutletPulledContext[XmlEventDecode]): OutletPulledContext[XmlEventDecode] =
      (hlpOut, failureOption) match {
        case (None, None) =>
          ctx.completeStage()

        case (None, Some(reason)) =>
          ctx.failStage(reason)

        case (Some((hle, hlpNext)), _) =>
          ctx
            .withState(withHLP(hlpNext))
            .push(outlet, hle)
      }
  }

  final case class StateNormal(hlp: HighLevelParser = State.emptyHLP) extends State {
    def withHLP(hlpNext: HighLevelParser): StateNormal =
      copy(hlp = hlpNext)

    def feedHLP(input: String): StateNormal =
      withHLP(hlp.in(input))

    def withHLPOutOption: Option[(HighLevelEvent, StateNormal)] =
      hlpOut
        .map { case (hle, hlpNext) =>
          (hle, withHLP(hlpNext))
        }

    def hasPendingHLEs: Boolean =
      hlpOut.isDefined

    override def inletOnUpstreamFinish(ctx: InletFinishedContext[XmlEventDecode]): InletFinishedContext[XmlEventDecode] =
      if (hasPendingHLEs) ctx.withState(StateInletClosed(hlp, None))
      else ctx.completeStage()


    override def inletOnUpstreamFailure(ctx: InletFailedContext[XmlEventDecode]): InletFailedContext[XmlEventDecode] =
      if (hasPendingHLEs) ctx.withState(StateInletClosed(hlp, Some(ctx.reason)))
      else ctx.failStage(ctx.reason)

    override def inletOnPush(ctx: InletPushedContext[XmlEventDecode]): InletPushedContext[XmlEventDecode] = {
      assert(ctx.inlet == inlet)

      val stateHlpFed = feedHLP(ctx.peek(inlet))
      val ctxDropped = ctx.drop(inlet).withState(stateHlpFed)

      stateHlpFed.withHLPOutOption match {
        case None =>
          ctxDropped
            .pull(inlet)

        case Some((hle, stateNext)) =>
          ctxDropped
            .withState(stateNext)
            .push(outlet, hle)
      }
    }

    override def outletOnPull(ctx: OutletPulledContext[XmlEventDecode]): OutletPulledContext[XmlEventDecode] = {
      assert(ctx.outlet == outlet)

      withHLPOutOption match {
        case None =>
          ctx.pull(inlet)

        case Some((hle, stateNext)) =>
          ctx
            .withState(stateNext)
            .push(outlet, hle)
      }
    }
  }
}

final case class XmlEventDecode() extends Stage[XmlEventDecode] {
  override type Shape = XmlEventDecode.Shape
  override type State = XmlEventDecode.State
  override type MatValue = XmlEventDecode.MaterializedValue

  override def shape: Shape =
    FlowShape.of(XmlEventDecode.inlet, XmlEventDecode.outlet)

  override def initialStateAndMatValue
    (logic: GraphStageLogic, inheritedAttributes: Attributes)
  : (XmlEventDecode.State, MaterializedValue) =
    (XmlEventDecode.State.empty, NotUsed)
}
