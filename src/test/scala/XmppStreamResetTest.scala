
import akka.{Done, stream}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.github.rgafiyatullin.xml.common.Attribute
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class XmppStreamResetTest extends TestBase {
  type XmppInputStreamMat = (XmlEventCodec.DecoderMat, StreamEventCodec.DecoderMat)
  val xmppInputStreamFlow: Flow[ByteString, StreamEvent, XmppInputStreamMat] =
    Flow.fromGraph(Utf8Codec.decode)
      .viaMat(XmlEventCodec.decode)(Keep.right)
      .viaMat(StreamEventCodec.decode)(Keep.both)

  type XmppOutputStreamMat = StreamEventCodec.EncoderMat
  val xmppOutputStreamFlow: Flow[StreamEvent, ByteString, XmppOutputStreamMat] =
    Flow.fromGraph(StreamEventCodec.encode)
      .viaMat(XmlEventCodec.encode)(Keep.left)
      .viaMat(Utf8Codec.encode)(Keep.left)


  "XMPP Output Stream" should "just reopen the stream upon double stream open" in
    withMaterializer { mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext

        val (srcQ, seeApiFut, snkQ) =
          Source.queue[StreamEvent](1, OverflowStrategy.fail)
            .viaMat(xmppOutputStreamFlow)(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .mapMaterializedValue { t => (t._1._1, t._1._2, t._2) }
            .run()(mat)

        for {
          _ <- srcQ
            .offer(StreamEvent.StreamOpen(Seq(
              Attribute.NsImport("s", "http://etherx.jabber.org/streams"))))
            .map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ
            .pull()
            .map(_.map(_.utf8String))
            .map(_ should contain("<s:stream xmlns:s='http://etherx.jabber.org/streams'>"))
          _ <- srcQ
            .offer(StreamEvent.StreamOpen(Seq.empty))
            .map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ
            .pull()
            .map(_.map(_.utf8String))
            .map(_ should contain("<stream xmlns='http://etherx.jabber.org/streams'>"))
        }
          yield ()
      }
    }

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
            .pull().failed
            .map(_ should matchPattern { case _: XmppStreamError.InvalidXml => })
        }
          yield ()
      }
    }

  it should "not fail on doulbe stream open with reset" in
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
          Done <- xedApi.reset()(100.millis)
          Done <- sedApi.reset()(100.millis)
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
