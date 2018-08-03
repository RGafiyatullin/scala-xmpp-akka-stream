package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import akka.stream.Graph
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.Utf8Decode
import com.github.rgafiyatullin.xmpp_akka_stream.stages.functional.Utf8Encode

object Utf8Codec {
  type EncoderMat = Utf8Encode.MaterializedValue
  type DecoderMat = Utf8Decode.MaterializedValue

  type EncoderShape = Utf8Encode.Shape
  type DecoderShape = Utf8Decode.Shape

  private val bytesPerChunk: Int = 256

  /**
    * Shape: `FlowShape[String, ByteString]`
    * Mat-value: `NotUsed`
    *
    * Graph stage encoding string-stream into byte-stream using UTF-8 encoding.
    * Input items are not guaranteed to match 1:1 to the output items.
    * Guaranteed: `ByteString(allInputItems.mkString.getBytes("utf-8")) == allOutputItems.reduce(_ ++ _)` .
    *
    * The stage is completed when
    * a) both conditions are met:
    *  - inlet finished (e.g. upstream-stage is completed);
    *  - all output has flushed into outlet.
    * b) outlet is finished (e.g. downstream-stage is completed).
    *
    * The stage is failed when upstream has failed.
    *
    */
  val encode: Graph[EncoderShape, EncoderMat] = Utf8Encode().toGraph

  /**
    * Shape: `FlowShape[ByteString, String]`
    * Mat-value: `NotUsed`
    *
    * Graph stage decoding byte-stream into string-stream using UTF-8 encoding.
    * Input items are not guaranteed to match 1:1 to the output items.
    * Guaranteed: `allInputItems.reduce(_ ++ _) == ByteString(allOutputItems.mkString.getBytes("utf-8"))`.
    *
    * The stage is completed when
    * a) both conditions are met:
    *  - inlet finished (e.g. upstream-stage is completed);
    *  - all output has flushed into outlet.
    * b) outlet is finished (e.g. downstream-stage is completed).
    *
    * The stage is failed when
    * a) upstream has failed;
    * b) input bytes sequence cannot be decoded using UTF-8 encoding.
    *
    */
  val decode: Graph[DecoderShape, DecoderMat] =
    Flow[ByteString]
      .map(_.grouped(bytesPerChunk))
      .mapConcat(_.toList)
      .viaMat(Utf8Decode().toGraph)(Keep.right)
}
