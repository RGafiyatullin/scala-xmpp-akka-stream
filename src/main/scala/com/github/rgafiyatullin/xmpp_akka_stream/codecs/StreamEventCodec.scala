package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import akka.stream.Graph
import akka.stream.stage.GraphStageWithMaterializedValue
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.{StreamEventDecode, StreamEventEncode}

object StreamEventCodec {
  type EncoderShape = StreamEventEncode.Shape
  type DecoderShape = StreamEventDecode.Shape

  type EncoderMat = StreamEventEncode.MaterializedValue
  type DecoderMat = StreamEventDecode.MaterializedValue

  val encode: Graph[EncoderShape, EncoderMat] = encode(dumpStreamErrorCause = false)
  /**
    * Shape: Flow[StreamEvent, HighLevelEvent]
    * Mat-Value: NotUsed
    */
  def encode(dumpStreamErrorCause: Boolean): GraphStageWithMaterializedValue[EncoderShape, EncoderMat] = StreamEventEncode(dumpStreamErrorCause).toGraph

  /**
    * Shape: Flow[HighLevelEvent, StreamEvent]
    * Mat-Value: Future[StreamEventDecode.Api]
    */
  val decode: Graph[DecoderShape, DecoderMat] = StreamEventDecode().toGraph
}
