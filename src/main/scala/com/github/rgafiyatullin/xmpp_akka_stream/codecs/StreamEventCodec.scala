package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import akka.stream.stage.GraphStageWithMaterializedValue
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.{StreamEventEncode, StreamEventDecode}

object StreamEventCodec {
  type EncoderShape = StreamEventEncode.Shape
  type DecoderShape = StreamEventDecode.Shape

  type EncoderMat = StreamEventEncode.MaterializedValue
  type DecoderMat = StreamEventDecode.MaterializedValue

  /**
    * Shape: Flow[StreamEvent, HighLevelEvent]
    * Mat-Value: NotUsed
    */
  val encode: GraphStageWithMaterializedValue[EncoderShape, EncoderMat] = StreamEventEncode().toGraph

  /**
    * Shape: Flow[HighLevelEvent, StreamEvent]
    * Mat-Value: Future[StreamEventDecode.Api]
    */
  val decode: GraphStageWithMaterializedValue[DecoderShape, DecoderMat] = StreamEventDecode().toGraph
}
