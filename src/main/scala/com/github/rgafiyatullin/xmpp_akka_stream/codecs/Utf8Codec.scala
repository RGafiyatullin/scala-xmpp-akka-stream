package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import akka.stream.stage.GraphStageWithMaterializedValue
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.Utf8Decode
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.Utf8Encode

object Utf8Codec {
  type EncoderMat = Utf8Encode.MaterializedValue
  type DecoderMat = Utf8Decode.MaterializedValue

  type EncoderShape = Utf8Encode.Shape
  type DecoderShape = Utf8Decode.Shape

  val encode: GraphStageWithMaterializedValue[EncoderShape, EncoderMat] = Utf8Encode().toGraph
  val decode: GraphStageWithMaterializedValue[DecoderShape, DecoderMat] = Utf8Decode().toGraph
}
