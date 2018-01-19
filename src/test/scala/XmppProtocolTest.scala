import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import com.github.rgafiyatullin.xmpp_akka_stream.stages._
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.ExecutionContext

final class XmppProtocolTest extends TestBase {
  val upstreamFlow: Flow[ByteString, StreamEvent, _] =
    Flow.fromGraph(Utf8Decode).via(XmlEventDecode).via(StreamEventDecode).named("XMPP-upstream")

  val downstreamFlow: Flow[StreamEvent, ByteString, _] =
    Flow.fromGraph(StreamEventEncode).via(XmlEventEncode).via(Utf8Encode).named("XMPP-downstream")

  val protocol: BidiFlow[ByteString, StreamEvent, StreamEvent, ByteString, _] =
    BidiFlow.fromFlows(upstreamFlow, downstreamFlow).named("XMPP-bidi-stream")

  "protocol" should "work" in
    withMaterializer { implicit mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext
        val serverFlow: Flow[ByteString, ByteString, _] = protocol.join(Flow.fromFunction(identity))

        val clientFlow =
          protocol
            .join(
              Flow.fromSinkAndSource(
                Sink.queue[StreamEvent],
                Source.queue[StreamEvent](100, OverflowStrategy.backpressure)))


        val serverDone = Tcp(mat.system).bindAndHandle(serverFlow, "0.0.0.0", 5222)

        val smth = Tcp(mat.system).outgoingConnection("127.0.0.1", 5222).join(clientFlow)

        ???
      }
    }
}
