package com.github.rgafiyatullin.xmpp_akka_stream.codecs

import com.github.rgafiyatullin.xmpp_akka_stream.stages.{Utf8Decode, Utf8Encode}

object Utf8Codec {
  val encode: Utf8Encode = Utf8Encode()
  val decode: Utf8Decode = Utf8Decode()
}
