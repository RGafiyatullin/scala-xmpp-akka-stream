package com.github.rgafiyatullin.xmpp_akka_stream

import akka.NotUsed
import akka.stream.scaladsl.{BidiFlow, Flow, Keep}
import akka.stream.{BidiShape, FlowShape, Graph}
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_akka_stream.stages.aaltoxml.AaltoXmlEventDecode
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

object Xmpp {
  type UpstreamTransportShape = FlowShape[ByteString, StreamEvent]
  type UpstreamTransport[+MatValue] = Graph[UpstreamTransportShape, MatValue]
  type DownstreamTransportShape = FlowShape[StreamEvent, ByteString]
  type DownstreamTransport[+MatValue] = Graph[DownstreamTransportShape, MatValue]
  type ProtocolShape = BidiShape[StreamEvent, ByteString, ByteString, StreamEvent]
  type Protocol[+MatValue] = Graph[ProtocolShape, MatValue]

  object plaintextXml {
    def upstreamHandmade: UpstreamTransport[NotUsed] =
      Flow[ByteString]
        .via(Utf8Codec.decode)
        .via(XmlEventCodec.decode)
        .via(StreamEventCodec.decode)
        .named("Xmpp.plaintextXml.upstream")

    def upstreamAalto: UpstreamTransport[AaltoXmlEventDecode.MaterializedValue] =
      Flow[ByteString]
        .viaMat(AaltoXmlEventDecode().toGraph)(Keep.right)
        .via(StreamEventCodec.decode)
        .named("Xmpp.plaintextXml.upstream")

    def upstream: UpstreamTransport[AaltoXmlEventDecode.MaterializedValue] =
      upstreamAalto

    def downstream: DownstreamTransport[NotUsed] = downstream(dumpStreamErrorCause = false)

    def downstream(dumpStreamErrorCause: Boolean): DownstreamTransport[NotUsed] =
      Flow[StreamEvent]
        .via(StreamEventCodec.encode(dumpStreamErrorCause))
        .via(XmlEventCodec.encode)
        .via(Utf8Codec.encode)
        .named("Xmpp.plaintextXml.downstream")

    def protocol: Protocol[NotUsed] =
      BidiFlow.fromFlows(downstream, upstream)
  }

}
