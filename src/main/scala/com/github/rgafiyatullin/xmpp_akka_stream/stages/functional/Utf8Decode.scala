package com.github.rgafiyatullin.xmpp_akka_stream.stages.functional

import akka.NotUsed
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.GraphStageLogic
import akka.util.ByteString
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext}
import com.github.rgafiyatullin.xml.utf8.{Utf8Error, Utf8InputStream}

import scala.collection.immutable.Queue

object Utf8Decode {
  type Shape = FlowShape[ByteString, String]
  type MaterializedValue = NotUsed

  val inlet: Inlet[ByteString] = Inlet("Utf8Decode.In")
  val outlet: Outlet[String] = Outlet("Utf8Decode.Out")

  object State {
    def empty: State = State()
  }
  final case class State(decoder: Utf8InputStream = Utf8InputStream.empty)
    extends Stage.State[Utf8Decode]
  {
    def withDecoder(d: Utf8InputStream): State =
      copy(decoder = d)

    override def outletOnPull(ctx: OutletPulledContext[Utf8Decode]): OutletPulledContext[Utf8Decode] = {
      assert(ctx.outlet == outlet)
      if (!ctx.isPulled(inlet)) ctx.pull(inlet)
      else ctx
    }

    override def inletOnPush(ctx: InletPushedContext[Utf8Decode]): InletPushedContext[Utf8Decode] = {
      assert(ctx.inlet == inlet)

      ctx.peek(inlet)
        .foldLeft[Either[Utf8Error, (Queue[Char], Utf8InputStream)]](Right(Queue.empty, decoder)) {
          case (acc, byte) => // fold through each byte
            acc.flatMap {
              case (q, dIn) => // if Right
                dIn
                  .in(byte) // feed another byte
                  .map(_.out) // if Right: fetching another char (if available)
                  .map { // if there was a char to fetch — append it into the queue
                    case (charOption, dOut) =>
                      (q ++ charOption, dOut)
                  }
            } // if Left — leave as is
        }
        .fold({ utf8error => // if Left — it's an UTF8 error
          ctx.failStage(utf8error)
        }, {
          case (outputBuffer, decoderNext) => // if Right — we've processed all the bytes available
            (outputBuffer.isEmpty, ctx.isAvailable(outlet)) match {
              case (false, true) =>
                ctx
                  .push(outlet, outputBuffer.mkString)
                  .withState(withDecoder(decoderNext))

              case (true, true) =>
                ctx
                  .pull(inlet)
                  .withState(withDecoder(decoderNext))

              case (_, false) =>
                throw new AssertionError("Got data on inlet while outlet is not available")
            }
        })
        .drop(inlet)

    }

  }
}

final case class Utf8Decode() extends Stage[Utf8Decode] {
  override type Shape = Utf8Decode.Shape
  override type State = Utf8Decode.State
  override type MatValue = Utf8Decode.MaterializedValue

  override def shape: FlowShape[ByteString, String] =
    FlowShape.of(Utf8Decode.inlet, Utf8Decode.outlet)

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes)
  : (Utf8Decode.State, Utf8Decode.MaterializedValue) =
    (Utf8Decode.State.empty, NotUsed)
}
