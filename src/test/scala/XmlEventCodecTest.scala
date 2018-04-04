import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.github.rgafiyatullin.xml.common.{Attribute, HighLevelEvent, Position}
import com.github.rgafiyatullin.xml.stream_parser.high_level_parser.HighLevelParserError
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.XmlEventCodec

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class XmlEventCodecTest extends TestBase {
  val ep: Position = Position.withoutPosition
  val parsed: List[HighLevelEvent] =
    List(
      HighLevelEvent.Comment(ep, "text"),
      HighLevelEvent.ProcessingInstrutcion(ep, "target", "content"),
      HighLevelEvent.ElementOpen(ep, "streams", "stream", "streams-namespace", Seq(
        Attribute.NsImport("streams", "streams-namespace"),
        Attribute.NsImport("", "jabber:client"),
        Attribute.Unprefixed("to", "im.&localhost"),
        Attribute.Prefixed("streams", "local-name", "value&")
      )),
      HighLevelEvent.ElementSelfClosing(ep, "streams", "features", "streams-namespace", Seq()),
      HighLevelEvent.ElementClose(ep, "streams", "stream", "streams-namespace"))

  val parsedWithXmlLangAttr: List[HighLevelEvent] =
    List(
      HighLevelEvent.Comment(ep, "text"),
      HighLevelEvent.ProcessingInstrutcion(ep, "target", "content"),
      HighLevelEvent.ElementOpen(ep, "streams", "stream", "streams-namespace", Seq(
        Attribute.NsImport("streams", "streams-namespace"),
        Attribute.NsImport("", "jabber:client"),
        Attribute.Unprefixed("to", "im.&localhost"),
        Attribute.Prefixed("streams", "local-name", "value&"),
        Attribute.Prefixed("xml", "lang", "en")
      )),
      HighLevelEvent.ElementSelfClosing(ep, "streams", "features", "streams-namespace", Seq()),
      HighLevelEvent.ElementClose(ep, "streams", "stream", "streams-namespace"))

  val rendered: String =
    "<!--text-->" +
    "<?target content?>" +
    "<streams:stream" +
    " xmlns:streams='streams-namespace'" +
    " xmlns='jabber:client'" +
    " to='im.&amp;localhost'" +
    " streams:local-name='value&amp;'" +
    ">" +
    "<streams:features" +
    "/>" +
    "</streams:stream>"

  val renderedWithXmlLangAttr: String =
    "<!--text-->" +
      "<?target content?>" +
      "<streams:stream" +
      " xmlns:streams='streams-namespace'" +
      " xmlns='jabber:client'" +
      " to='im.&amp;localhost'" +
      " streams:local-name='value&amp;'" +
      " xml:lang='en'" +
      ">" +
      "<streams:features" +
      "/>" +
      "</streams:stream>"


  "XmlEventEncode" should "work" in
    unit(withMaterializer { mat =>
      futureOk {
        Source(parsed)
          .via(XmlEventCodec.encode)
          .runReduce(_ ++ _)(mat)
          .map(_ should be (rendered))(mat.executionContext) } })

  "XmlEventDecode" should "work #1" in
    unit(withMaterializer { mat =>
      futureOk {
        val futureEvents =
          Source(List(rendered))
            .via(XmlEventCodec.decode)
            .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)
        futureEvents.map(_.toList should be (parsed))(mat.executionContext)
      }
    })

  it should "not fail on using previously imported prefix" in
    unit(withMaterializer { mat =>
      futureOk {
        Source(List("<prefix:local-name xmlns:prefix='namespace'><prefix:local-name>"))
          .via(XmlEventCodec.decode)
          .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)
          .map(_ should be (Seq(
            HighLevelEvent.ElementOpen(ep, "prefix", "local-name", "namespace", Seq(Attribute.NsImport("prefix", "namespace"))),
            HighLevelEvent.ElementOpen(ep, "prefix", "local-name", "namespace", Seq.empty)
          )))(mat.executionContext)
      }
    })

  it should "fail on using previously imported prefix when reset in between" in
    unit(withMaterializer { mat =>
      implicit val ec: ExecutionContext = mat.executionContext
      futureOk {
        val ((srcQ, xedApiFut), snkQ) =
          Source.queue[String](1, OverflowStrategy.fail)
            .viaMat(XmlEventCodec.decode)(Keep.both)
            .toMat(Sink.queue[HighLevelEvent]())(Keep.both)
            .run()(mat)

        for {
          xedApi <- xedApiFut
          _ <- srcQ.offer("<prefix:local-name xmlns:prefix='namespace'>").map(_ should be (QueueOfferResult.Enqueued))
          _ <- snkQ.pull()
            .map(_ should contain ( HighLevelEvent.ElementOpen(
              ep, "prefix", "local-name", "namespace",
              Seq(Attribute.NsImport("prefix", "namespace"))) ))
          _ <- xedApi.reset()(100.millis)
          _ <- srcQ.offer("<prefix:local-name>")
          _ <- snkQ.pull().failed.map(_ shouldBe an[HighLevelParserError])
        }
          yield ()
      }
    })

  it should "work #2" in
    unit(withMaterializer { mat =>
      futureOk {
        val futureEvents =
          Source(
            rendered
              .toCharArray
              .map { ch => new String(Array(ch)) }
              .toList)
            .via(XmlEventCodec.decode)
            .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)
        futureEvents.map(_.toList should be (parsed))(mat.executionContext)
      }
    })

  it should "not fail upon coming accross xml:lang='en' attribute" in
    unit(withMaterializer { mat =>
      futureOk {
        val futureEvents =
          Source(List(renderedWithXmlLangAttr))
            .via(XmlEventCodec.decode)
            .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)
        futureEvents.map(_.toList should be (parsedWithXmlLangAttr))(mat.executionContext)
      }
    })

}
