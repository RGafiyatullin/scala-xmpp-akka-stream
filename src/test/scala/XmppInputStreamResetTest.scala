
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class XmppInputStreamResetTest extends TestBase {
  type XMPPInputStreamMat = (XmlEventCodec.DecoderMat, StreamEventCodec.DecoderMat)
  val xmppInputStreamFlow: Flow[ByteString, StreamEvent, XMPPInputStreamMat] =
    Flow.fromGraph(Utf8Codec.decode)
      .viaMat(XmlEventCodec.decode)(Keep.right)
      .viaMat(StreamEventCodec.decode)(Keep.both)

  "XMPP Input Stream" should "fail on doulbe stream open with no reset" in
    withMaterializer { mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext

        val (srcQ, xedApiFut, sedApiFut, snkQ) =
          Source.queue[ByteString](1, OverflowStrategy.fail)
            .viaMat(xmppInputStreamFlow)(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .mapMaterializedValue { t => (t._1._1, t._1._2._1, t._1._2._2, t._2) }
            .run()(mat)

        for {
          xedApi <- xedApiFut
          sedApi <- sedApiFut
          _ <- srcQ
            .offer(ByteString("<s:stream xmlns:s='http://etherx.jabber.org/streams'>"))
            .map(_ should be (QueueOfferResult.Enqueued))
          _ <- snkQ
            .pull()
            .map(_ should matchPattern { case Some(StreamEvent.StreamOpen(_)) => })

          _ <- srcQ
            .offer(ByteString("<s:stream xmlns:s='http://etherx.jabber.org/streams'>"))
            .map(_ should be (QueueOfferResult.Enqueued))
          _ <- snkQ
            .pull()
            .map(_ should matchPattern { case Some(StreamEvent.StreamError(_: XmppStreamError.InvalidXml)) => })
        }
          yield ()
      }
    }

  "XMPP Input Stream" should "not fail on doulbe stream open with reset" in
    withMaterializer { mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext

        val (srcQ, xedApiFut, sedApiFut, snkQ) =
          Source.queue[ByteString](1, OverflowStrategy.fail)
            .viaMat(xmppInputStreamFlow)(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .mapMaterializedValue { t => (t._1._1, t._1._2._1, t._1._2._2, t._2) }
            .run()(mat)

        for {
          xedApi <- xedApiFut
          sedApi <- sedApiFut
          _ <- srcQ
            .offer(ByteString("<s:stream xmlns:s='http://etherx.jabber.org/streams'>"))
            .map(_ should be (QueueOfferResult.Enqueued))
          _ <- snkQ
            .pull()
            .map(_ should matchPattern { case Some(StreamEvent.StreamOpen(_)) => })
          _ <- xedApi.reset()(100.millis)
          _ <- sedApi.reset()(100.millis)
          _ <- srcQ
            .offer(ByteString("<s:stream xmlns:s='http://etherx.jabber.org/streams'>"))
            .map(_ should be (QueueOfferResult.Enqueued))
          _ <- snkQ
            .pull()
            .map(_ should matchPattern { case Some(StreamEvent.StreamOpen(_)) => })
        }
          yield ()
      }
    }
}
