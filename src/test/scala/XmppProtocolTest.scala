import akka.event.Logging
import akka.stream.{Attributes, OverflowStrategy}
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source, Tcp}
import akka.util.{ByteString, Timeout}
import com.github.rgafiyatullin.xmpp_akka_stream.codecs._
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

final class XmppProtocolTest extends TestBase {
  private val upstreamFlow =
    Flow.fromGraph(Utf8Codec.decode)
        .log("upstream")
        .withAttributes(
          Attributes.logLevels(onElement = Logging.WarningLevel))
      .viaMat(XmlEventCodec.decode)(Keep.both)
      .viaMat(StreamEventCodec.decode)(Keep.both)
      .named("XMPP-upstream")

  private val downstreamFlow =
    Flow.fromGraph(StreamEventCodec.encode)
      .viaMat(XmlEventCodec.encode)(Keep.both)
        .log("downstream")
        .withAttributes(
          Attributes.logLevels(onElement = Logging.WarningLevel))
      .viaMat(Utf8Codec.encode)(Keep.both)
      .named("XMPP-downstream")

  private val xmppStreamApiFlow =
    Flow.fromSinkAndSourceMat(
      Sink.queue[StreamEvent],
      Source.queue[StreamEvent](100, OverflowStrategy.backpressure)
    )(Keep.both)

  private val xmppProtocolStack =
    BidiFlow.fromFlowsMat(upstreamFlow, downstreamFlow)(Keep.both).named("XMPP-bidi-stream")

  "protocol" should "work" in
    withMaterializer { implicit mat =>
      futureOk {
        implicit val timeout: Timeout = 5.seconds
        implicit val ec: ExecutionContext = mat.executionContext
        val serverFlow: Flow[ByteString, ByteString, _] = xmppProtocolStack.join(Flow.fromFunction(identity))

        val clientFlow =
          xmppProtocolStack
            .joinMat(xmppStreamApiFlow)(Keep.both)

        val serverDone = Tcp(mat.system).bindAndHandle(serverFlow, "0.0.0.0", 5222)

        val clientFut = Tcp(mat.system)
          .outgoingConnection("127.0.0.1", 5222)
          .joinMat(clientFlow)({
            case (outConnFut, ((((u8dFut, xedFut), sedFut), ((seeFut, xeeFut), u8e)), (downstreamSinkQueue, upstreamSourceQueue))) =>
              for {
                outConn <- outConnFut
                u8d <- u8dFut
                xed <- xedFut
                sed <- sedFut
                see <- seeFut
                xee <- xeeFut
              }
                yield (outConn, u8d, u8e, xed, xee, sed, see, downstreamSinkQueue, upstreamSourceQueue)
          })
          .run()(mat)

        for {
          client <- clientFut
          _ <- client._9.offer(StreamEvent.StreamOpen(Seq.empty))
          Some(StreamEvent.StreamOpen(_)) <- client._8.pull()
        }
          yield ()
      }
    }
}
