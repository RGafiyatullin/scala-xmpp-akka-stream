import akka.stream.scaladsl.Source
import com.github.rgafiyatullin.xml.common.{Attribute, HighLevelEvent, Position, QName}
import com.github.rgafiyatullin.xml.dom.{Element, Node}
import com.github.rgafiyatullin.xmpp_akka_stream.stages.StreamEventEncode
import com.github.rgafiyatullin.xmpp_protocol.XmppConstants
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.collection.immutable.Queue

final class StreamEventCodecTest extends TestBase {
  val ep: Position = Position.withoutPosition

  val qnStream: QName = XmppConstants.names.streams.stream
  val qnIQ: QName = XmppConstants.names.jabber.client.iq
  val attrNsImportJC: Attribute = Attribute.NsImport("", qnIQ.ns)
  val attrNsImportStreams: Attribute = Attribute.NsImport("streams", qnStream.ns)

  val aStanza: Node = Element(qnIQ, Seq(attrNsImportJC), Seq.empty)

  val streamEvents: List[StreamEvent] = List(
    StreamEvent.StreamOpen(Seq(attrNsImportStreams)),
    StreamEvent.Stanza(aStanza))
  val xmlEvents: List[HighLevelEvent] = List(
    HighLevelEvent.ElementOpen(ep, "streams", qnStream.localName, qnStream.ns, Seq( attrNsImportStreams)),
    HighLevelEvent.ElementSelfClosing(ep, "", qnIQ.localName, qnIQ.ns, Seq(attrNsImportJC)))

  "StreamEventEncode" should "work" in
    withMaterializer{ mat =>
      futureOk {
        val futureXmlEvents =
          Source(streamEvents)
            .via(StreamEventEncode)
            .runFold(Queue.empty[HighLevelEvent])(_.enqueue(_))(mat)

        futureXmlEvents.map(_.toList should be (xmlEvents))(mat.executionContext)
      }
    }
}
