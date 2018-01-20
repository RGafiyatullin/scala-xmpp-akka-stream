import akka.stream.scaladsl.Source
import com.github.rgafiyatullin.xml.common.{Attribute, HighLevelEvent, Position}
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.XmlEventCodec

import scala.collection.immutable.Queue

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

  "XmlEventEncode" should "work" in
    withMaterializer { mat =>
      futureOk {
        Source(parsed)
          .via(XmlEventCodec.encode)
          .runReduce(_ ++ _)(mat)
          .map(_ should be (rendered))(mat.executionContext) } }



  "XmlEventDecode" should "work #1" in
    withMaterializer { mat =>
      futureOk {
        val futureEvents =
          Source(List(rendered))
            .via(XmlEventCodec.decode)
            .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)
        futureEvents.map(_.toList should be (parsed))(mat.executionContext)
      }
    }

  it should "work #2" in
    withMaterializer { mat =>
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
    }

}
