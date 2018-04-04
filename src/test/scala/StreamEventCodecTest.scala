
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.github.rgafiyatullin.xml.common.{Attribute, HighLevelEvent, Position, QName}
import com.github.rgafiyatullin.xml.dom.{Element, Node}
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.StreamEventCodec
import com.github.rgafiyatullin.xmpp_protocol.XmppConstants
import com.github.rgafiyatullin.xmpp_protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.duration._
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext

final class StreamEventCodecTest extends TestBase {
  val ep: Position = Position.withoutPosition

  val qnStream: QName = XmppConstants.names.streams.stream
  val qnIQ: QName = XmppConstants.names.jabber.client.iq
  val qnBody: QName = XmppConstants.names.jabber.iq.roster.query
  val attrNsImportJC: Attribute = Attribute.NsImport("", qnIQ.ns)
  val attrNsImportJCR: Attribute = Attribute.NsImport("", qnBody.ns)
  val attrNsImportStreams: Attribute = Attribute.NsImport("streams", qnStream.ns)

  val aStanza: Node = Element(qnIQ, Seq(attrNsImportJC), Seq(
    Element(qnBody, Seq(attrNsImportJCR), Seq.empty)
  ))
  val aStanzaNoImports: Node = Element(qnIQ, Seq(), Seq(
    Element(qnBody, Seq(), Seq.empty)
  ))

  val streamEvents: List[StreamEvent] = List(
    StreamEvent.StreamOpen(Seq(attrNsImportStreams)),
    StreamEvent.Stanza(aStanza))
  val streamEventsNoImports: List[StreamEvent] = List(
    StreamEvent.StreamOpen(Seq(attrNsImportStreams)),
    StreamEvent.Stanza(aStanzaNoImports))
  val xmlEvents: List[HighLevelEvent] = List(
    HighLevelEvent.ElementOpen(ep, "streams", qnStream.localName, qnStream.ns, Seq( attrNsImportStreams)),
    HighLevelEvent.ElementOpen(ep, "", qnIQ.localName, qnIQ.ns, Seq(attrNsImportJC)),
    HighLevelEvent.ElementSelfClosing(ep, "", qnBody.localName, qnBody.ns, Seq(attrNsImportJCR)),
    HighLevelEvent.ElementClose(ep, "", qnIQ.localName, qnIQ.ns))

  "StreamEventEncode" should "work #1" in
    unit(withMaterializer{ mat =>
      futureOk {
        val futureXmlEvents =
          Source(streamEvents)
            .via(StreamEventCodec.encode)
            .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)

        futureXmlEvents.map(_.toList should be (xmlEvents))(mat.executionContext)
      }
    })

  "StreamEventDecode" should "work" in
    unit(withMaterializer { mat =>
      futureOk {
        val futureStreamEvents =
          Source(xmlEvents)
            .via(StreamEventCodec.decode)
            .runFold(Queue.empty[StreamEvent])(_.enqueue(_))(mat)

        futureStreamEvents.map(_.toList should be (streamEventsNoImports))(mat.executionContext)
      }
    })

  it should "fail on stream re-open when hasn't been reset" in
    unit(withMaterializer { implicit mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext

        val streamOpenEvent = HighLevelEvent.ElementOpen(ep, "", qnStream.localName, qnStream.ns, Seq.empty)

        val ((srcQ, sedApiFut), snkQ) =
          Source.queue[HighLevelEvent](1, OverflowStrategy.fail)
            .viaMat(StreamEventCodec.decode)(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .run()

        for {
          sedApi <- sedApiFut
          _ <- srcQ.offer(streamOpenEvent).map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ.pull().map(_ should contain(StreamEvent.StreamOpen(Seq())))
          _ <- srcQ.offer(streamOpenEvent).map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ.pull().failed.map(_ should matchPattern {
            case _: XmppStreamError.InvalidXml => })
        }
          yield ()
      }
    })

  it should "not fail on stream re-open when has been reset" in
    unit(withMaterializer { implicit mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext

        val streamOpenEvent = HighLevelEvent.ElementOpen(ep, "", qnStream.localName, qnStream.ns, Seq.empty)

        val ((srcQ, sedApiFut), snkQ) =
          Source.queue[HighLevelEvent](1, OverflowStrategy.fail)
            .viaMat(StreamEventCodec.decode)(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .run()

        for {
          sedApi <- sedApiFut
          _ <- srcQ.offer(streamOpenEvent).map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ.pull().map(_ should contain(StreamEvent.StreamOpen(Seq())))
          _ <- sedApi.reset()(100.millis)
          _ <- srcQ.offer(streamOpenEvent).map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ.pull().map(_ should contain(StreamEvent.StreamOpen(Seq())))
          _ = srcQ.complete()
          _ <- snkQ.pull().map(_ shouldBe empty)
        }
          yield ()
      }
    })


  it should "complete on stream-close event" in
    unit(withMaterializer { implicit mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext

        val streamOpenEvent = HighLevelEvent.ElementOpen(ep, "", qnStream.localName, qnStream.ns, Seq.empty)
        val streamCloseEvent = HighLevelEvent.ElementClose(ep, "", qnStream.localName, qnStream.ns)

        val ((srcQ, sedApiFut), snkQ) =
          Source.queue[HighLevelEvent](1, OverflowStrategy.fail)
            .viaMat(StreamEventCodec.decode)(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .run()

        for {
          sedApi <- sedApiFut
          _ <- srcQ.offer(streamOpenEvent).map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ.pull().map(_ should contain(StreamEvent.StreamOpen(Seq())))
          _ <- srcQ.offer(streamCloseEvent).map(_ should be(QueueOfferResult.Enqueued))
          _ <- snkQ.pull().map(_ should contain (StreamEvent.StreamClose()))
          _ <- snkQ.pull().map(_ shouldBe empty)
        }
          yield ()
      }
    })
}
