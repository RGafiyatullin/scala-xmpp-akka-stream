import akka.Done
import akka.event.Logging
import akka.stream.{Attributes, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.ExecutionContext

final class XmppProtocolTest extends TestBase {
  withMaterializer { implicit mat =>
    futureOk {
      implicit val ec: ExecutionContext = mat.executionContext

      val loggingAttrs = Attributes.logLevels(onElement = Logging.WarningLevel)

      val (src, snk) =
        Source.queue[ByteString](1, OverflowStrategy.fail)
          .via(Utf8Codec.decode).log("utf8-decode").withAttributes(loggingAttrs)
          .via(XmlEventCodec.decode).log("xml-decode").withAttributes(loggingAttrs)
          .via(StreamEventCodec.decode).log("xmpp-stream-decode").withAttributes(loggingAttrs)
          .toMat(Sink.queue())(Keep.both)
          .run()

      for {
        _ <- src
          .offer(ByteString(
            "<?xml version=\"1.0\"?>\n" +
              "<stream:stream xmlns:stream=\"http://etherx.jabber.org/streams\" \n" +
              "version=\"1.0\" xmlns=\"jabber:client\" \n" +
              "to=\"c2s.xmppcs02.loc\" xml:lang=\"en\" \n" +
              "xmlns:xml=\"http://www.w3.org/XML/1998/namespace\">\n"))
          .map(_ should be (QueueOfferResult.Enqueued))
        _ <- snk.pull().map(_.exists(_.isInstanceOf[StreamEvent.StreamOpen]) should be (true))
      }
        yield Done
    }
  }
}
