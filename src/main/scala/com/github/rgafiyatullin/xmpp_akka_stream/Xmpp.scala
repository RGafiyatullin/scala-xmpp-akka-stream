package com.github.rgafiyatullin.xmpp_akka_stream

import akka.Done
import akka.stream.scaladsl.{Flow, Keep}
import akka.util.{ByteString, Timeout}
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_akka_stream.stages._
import com.github.rgafiyatullin.xmpp_akka_stream.util.SameThreadExecutionContext
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.{ExecutionContext, Future}

object Xmpp {
  final case class UpstreamApi(
    utf8Decoder: Utf8Decode.Api,
    xmlEventDecoder: XmlEventDecode.Api,
    streamEventDecoder: StreamEventDecode.Api)
  {
    def reset()(implicit timeout: Timeout, ec: ExecutionContext): Future[Done] =
      for {
        Done <- xmlEventDecoder.reset()
        Done <- streamEventDecoder.reset()
      }
        yield Done
  }

  final case class DownstreamApi(
    utf8Encoder: Utf8Encode.Api,
    xmlEventEncoder: XmlEventEncode.Api,
    streamEventEncoder: StreamEventEncode.Api)
  {
    def reset()(implicit timeout: Timeout, ec: ExecutionContext): Future[Done] =
      for {
        Done <- streamEventEncoder.reset()
        Done <- xmlEventEncoder.reset()
      }
        yield Done
  }

  def combineIntoUpstreamApi: ((Future[Utf8Decode.Api], Future[XmlEventDecode.Api]), Future[StreamEventDecode.Api]) => Future[UpstreamApi] =
    {
      case ((utf8DecodeFuture, xmlEventDecodeFuture), streamEventDecodeFuture) =>
        implicit val ec: ExecutionContext = SameThreadExecutionContext

        for {
          utf8Decode <- utf8DecodeFuture
          xmlEventDecode <- xmlEventDecodeFuture
          streamEventDecode <- streamEventDecodeFuture
        }
          yield UpstreamApi(utf8Decode, xmlEventDecode, streamEventDecode)
    }

  def combineIntoDownstreamApi: ((Future[StreamEventEncode.Api], Future[XmlEventEncode.Api]), Future[Utf8Encode.Api]) => Future[DownstreamApi] =
    {
      case ((streamEventEncodeFuture, xmlEventEncodeFuture), utf8EncodeFuture) =>
        implicit val ec: ExecutionContext = SameThreadExecutionContext

        for {
          utf8Encode <- utf8EncodeFuture
          xmlEventEncode <- xmlEventEncodeFuture
          streamEventEncode <- streamEventEncodeFuture
        }
          yield DownstreamApi(utf8Encode, xmlEventEncode, streamEventEncode)
    }

  def upstreamFlow: Flow[ByteString, StreamEvent, Future[UpstreamApi]] =
    Flow.fromGraph(Utf8Codec.decode)
      .viaMat(XmlEventCodec.decode)(Keep.both)
        .viaMat(StreamEventCodec.decode)(Keep.both)
        .mapMaterializedValue(combineIntoUpstreamApi.tupled)

  def downstreamFlow: Flow[StreamEvent, ByteString, Future[DownstreamApi]] =
    Flow.fromGraph(StreamEventCodec.encode)
      .viaMat(XmlEventCodec.encode)(Keep.both)
      .viaMat(Utf8Codec.encode)(Keep.both)
      .mapMaterializedValue(combineIntoDownstreamApi.tupled)
}
