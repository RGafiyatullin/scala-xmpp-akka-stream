import akka.stream.scaladsl.Source
import com.github.rgafiyatullin.xml.common.{Attribute, HighLevelEvent, Position}
import com.github.rgafiyatullin.xmpp_akka_stream.stages.XmlEventEncode

final class XmlEventCodecTest extends TestBase {
  "XmlEventEncode" should "work" in
  withMaterializer { mat =>
    futureOk {
      val ep = Position.withoutPosition
      val hles = List(
        HighLevelEvent.Comment(ep, "text"),
        HighLevelEvent.ProcessingInstrutcion(ep, "target", "content"),
        HighLevelEvent.ElementOpen(ep, "streams", "stream", "streams-namespace", Seq(
          Attribute.NsImport("streams", "streams-namespace"),
          Attribute.NsImport("", "jabber:client"),
          Attribute.Unprefixed("to", "im.&localhost"),
          Attribute.Prefixed("streams", "local-name", "value&")
        )),
        HighLevelEvent.ElementSelfClosing(ep, "streams", "features", "streams-namespace", Seq()),
        HighLevelEvent.ElementClose(ep, "streams", "stream", "streams-namespace")
      )
      Source(hles)
        .via(XmlEventEncode)
        .runReduce(_ ++ _)(mat)
        .map(_ should be (
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
          ))(mat.executionContext)
    }
  }
}
