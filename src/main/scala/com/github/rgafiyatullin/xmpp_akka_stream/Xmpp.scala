package com.github.rgafiyatullin.xmpp_akka_stream

import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_akka_stream.stages.{StreamEventDecode, Utf8Decode, XmlEventDecode}
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.{ExecutionContext, Future}

object Xmpp {
  final case class UpstreamApi(
    utf8Decoder: Utf8Decode.Api,
    xmlEventDecoder: XmlEventDecode.Api,
    streamEventDecoder: StreamEventDecode.Api)

  def combineIntoUpstreamApi: ((Future[Utf8Decode.Api], Future[XmlEventDecode.Api]), Future[StreamEventDecode.Api]) => Future[UpstreamApi] =
    {
      case ((utf8DecodeFuture, xmlEventDecodeFuture), streamEventDecodeFuture) =>
        implicit val ec: ExecutionContext = ExecutionContext.fromExecutor { command: Runnable => command.run() }

        for {
          utf8Decode <- utf8DecodeFuture
          xmlEventDecode <- xmlEventDecodeFuture
          streamEventDecode <- streamEventDecodeFuture
        }
          yield UpstreamApi(utf8Decode, xmlEventDecode, streamEventDecode)
    }

  def upstreamFlow: Flow[ByteString, StreamEvent, Future[UpstreamApi]] =
    Flow.fromGraph(Utf8Codec.decode)
      .viaMat(XmlEventCodec.decode)(Keep.both)
        .viaMat(StreamEventCodec.decode)(Keep.both)
        .mapMaterializedValue(combineIntoUpstreamApi.tupled)

}
