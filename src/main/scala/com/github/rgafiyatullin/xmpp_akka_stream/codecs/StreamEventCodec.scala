package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import com.github.rgafiyatullin.xmpp_akka_stream.stages.{StreamEventDecode, StreamEventEncode}

object StreamEventCodec {
  val encode: StreamEventEncode = StreamEventEncode()
  val decode: StreamEventDecode = StreamEventDecode()
}
