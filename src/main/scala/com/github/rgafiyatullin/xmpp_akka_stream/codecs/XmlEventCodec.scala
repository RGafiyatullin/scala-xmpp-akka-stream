package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import akka.stream.stage.GraphStageWithMaterializedValue
import com.github.rgafiyatullin.xml.common.HighLevelEvent
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.{XmlEventDecode, XmlEventEncode}

object XmlEventCodec {
  type XmlEvent = HighLevelEvent
  type EncoderShape = XmlEventEncode.Shape
  type DecoderShape = XmlEventDecode.Shape

  type EncoderMat = XmlEventEncode.MaterializedValue
  type DecoderMat = XmlEventDecode.MaterializedValue

  /**
    * Shape: `FlowShape[HighLevelEvent, String]`
    * Mat-value: `NotUsed`
    *
    * Graph stage encoding a stream of `HighLevelEvent` (similar to XML SAX-event) into a string-stream.
    * Input items are not guaranteed to match 1:1 to the output items.
    *
    * The stage is completed when
    * a) both conditions are met:
    *  - inlet finished (e.g. upstream-stage is completed);
    *  - all output has flushed into outlet.
    * b) outlet is finished (e.g. downstream-stage is completed).
    *
    * The stage is failed when upstream has failed.
    */
  val encode: GraphStageWithMaterializedValue[EncoderShape, EncoderMat] = XmlEventEncode().toGraph

  /**
    * Shape: `FlowShape[String, HighLevelEvent]`
    * Mat-value: `NotUsed`
    *
    * Graph stage decoding a string-stream into a stream of `HighLevelEvent` (similar to XML SAX-event).
    *
    * The stage is completed when
    * a) both conditions are met:
    *  - inlet finished (e.g. upstream-stage is completed);
    *  - all output has flushed into outlet.
    * b) outlet is finished (e.g. downstream-stage is completed).
    *
    * The stage is failed when
    * a) upstream has failed;
    * b) input cannot be parsed as XML.
    */
  val decode: GraphStageWithMaterializedValue[DecoderShape, DecoderMat] = XmlEventDecode().toGraph
}
