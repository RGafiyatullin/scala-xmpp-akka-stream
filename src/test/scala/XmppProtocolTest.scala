import akka.Done
import akka.event.Logging
import akka.stream.{Attributes, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Flow, Keep, Sink, SinkQueue, Source, SourceQueue}
import akka.util.ByteString
import com.github.rgafiyatullin.xml.common.{Attribute, QName}
import com.github.rgafiyatullin.xml.dom.Node
import com.github.rgafiyatullin.xmpp_akka_stream.Xmpp
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

final class XmppProtocolTest extends TestBase {
  override def futureAwaitDuration: FiniteDuration = 15.seconds

  "xmpp-protocol" should "do #1" in
    unit {
      withMaterializer { implicit mat =>
        futureOk {
          implicit val ec: ExecutionContext = mat.executionContext

          val loggingAttrs = Attributes.logLevels(onElement = Logging.InfoLevel)

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
              .map(_ should be(QueueOfferResult.Enqueued))
            _ <- snk.pull().map(_.exists(_.isInstanceOf[StreamEvent.StreamOpen]) should be(true))
          }
            yield Done
        }
      }
    }

  it should "do #2" in
    unit {
      withMaterializer { implicit mat =>
        futureOk {
          implicit val ec: ExecutionContext = mat.executionContext

          val batchSizes = Seq(1, 10, 100, 1000, 10000, 100000)

          val maxPortionSize = batchSizes.max

          val portions =
            for {i <- batchSizes} yield
              StreamEvent.StreamOpen(Seq.empty) +: (for {j <- 1 to i} yield
                StreamEvent.Stanza(
                  Node(QName("a-namespace", s"local-name-$j"))))


          type XmppInputStreamMat = (XmlEventCodec.DecoderMat, StreamEventCodec.DecoderMat)
          val xmppUpstreamFlow: Flow[ByteString, StreamEvent, XmppInputStreamMat] =
            Flow.fromGraph(Utf8Codec.decode)
              .viaMat(XmlEventCodec.decode)(Keep.right)
              .viaMat(StreamEventCodec.decode)(Keep.both)

          val ((srcQ, (xedApiFuture, sedApiFuture)), snkQ) =
            Source
              .queue[StreamEvent](maxPortionSize, OverflowStrategy.fail).log("src-q")
              .via(Xmpp.plaintextXml.downstream).log("downstream")
              .viaMat(xmppUpstreamFlow)(Keep.both).log("upstream")
              .map(removeNsImportsFromSE).log("map:remove-ns-import")
              .toMat(Sink.queue())(Keep.both)
              .run()

          def runPortion(src: SourceQueue[StreamEvent], snk: SinkQueue[StreamEvent])(portionIn: Seq[StreamEvent]): Future[Seq[StreamEvent]] =
            for {
              Done <- portionIn.foldLeft(Future successful Done) { case (accFut, seIn) =>
                for {
                  Done <- accFut
                  QueueOfferResult.Enqueued <- src.offer(seIn)
                }
                  yield Done
              }

              portionOut <- portionIn
                .foldLeft(Future successful Queue.empty[StreamEvent]) {
                  case (accFut, _) =>
                    for {
                      acc <- accFut
                      Some(seOut) <- snk.pull()
                    }
                      yield acc :+ seOut
                }
            }
              yield portionOut

          portions.foldLeft(Future successful Done) { case (accFut, portionIn) =>
            for {
              Done <- accFut
              xedApi <- xedApiFuture
              sedApi <- sedApiFuture
              portionOut <- runPortion(srcQ, snkQ)(portionIn)

              _ = portionIn should be(portionOut)

              _ <- xedApi.reset()(1000.millis)
              _ <- sedApi.reset()(1000.millis)
            }
              yield Done
          }
        }
      }
    }
}
