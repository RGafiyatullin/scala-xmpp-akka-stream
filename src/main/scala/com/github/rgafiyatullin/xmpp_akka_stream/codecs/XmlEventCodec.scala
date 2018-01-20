package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import com.github.rgafiyatullin.xmpp_akka_stream.stages.{XmlEventDecode, XmlEventEncode}

object XmlEventCodec {
  val encode: XmlEventEncode = XmlEventEncode()
  val decode: XmlEventDecode = XmlEventDecode()
}
