package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import akka.stream.stage.GraphStageWithMaterializedValue
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.{XmlEventDecode, XmlEventEncode}

object XmlEventCodec {
  type EncoderShape = XmlEventEncode.Shape
  type DecoderShape = XmlEventDecode.Shape

  type EncoderMat = XmlEventEncode.MaterializedValue
  type DecoderMat = XmlEventDecode.MaterializedValue

  val encode: GraphStageWithMaterializedValue[EncoderShape, EncoderMat] = XmlEventEncode().toGraph
  val decode: GraphStageWithMaterializedValue[DecoderShape, DecoderMat] = XmlEventDecode().toGraph
}
