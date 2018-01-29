import akka.NotUsed
import akka.event.Logging
import akka.stream.{Attributes, OverflowStrategy}
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source, Tcp}
import akka.util.{ByteString, Timeout}
import com.github.rgafiyatullin.xmpp_akka_stream.Xmpp
import com.github.rgafiyatullin.xmpp_akka_stream.codecs._
import com.github.rgafiyatullin.xmpp_akka_stream.util.SameThreadExecutionContext
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

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

  it should "work #1" in
    withMaterializer { implicit mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext
        implicit val timeout: Timeout = 5.seconds

        val echoBehaviour: Flow[StreamEvent, StreamEvent, NotUsed] = Flow.fromFunction(identity[StreamEvent])
        val xmppProtocolStack: BidiFlow[ByteString, StreamEvent, StreamEvent, ByteString, Future[(Xmpp.UpstreamApi, Xmpp.DownstreamApi)]] =
          BidiFlow.fromFlowsMat(
            Xmpp.upstreamFlow
              .log("xmpp-upstream")
              .withAttributes(
                Attributes.logLevels(onElement = Logging.WarningLevel)),
            Xmpp.downstreamFlow
              .log("xmpp-downstream")
              .withAttributes(
                Attributes.logLevels(onElement = Logging.WarningLevel))
          ) { case (upFut, dnFut) =>
            implicit val ec: ExecutionContext = SameThreadExecutionContext
            for {
              up <- upFut
              dn <- dnFut
            }
              yield (up, dn)
          }
        val serverFlow: Flow[ByteString, ByteString, NotUsed] =
          xmppProtocolStack.joinMat(echoBehaviour)(Keep.none)
            .named("server")

        val tcpBountFut = Tcp(mat.system).bindAndHandle(serverFlow, "127.0.0.1", 5223)

        whenReady(tcpBountFut)(identity)

        val (outConnFut, _, sink, source) =
          Tcp(mat.system)
            .outgoingConnection("127.0.0.1", 5223)
            .joinMat(xmppProtocolStack)(Keep.both)
            .joinMat(
              Flow.fromSinkAndSourceCoupledMat(
                Sink.queue[StreamEvent](),
                Source.queue[StreamEvent](10, OverflowStrategy.fail))(Keep.both)
            )(Keep.both)
            .mapMaterializedValue {
              case ((tcpOutConnFut, upAndDownStreamsApiFut), (sinkQueue, sourceQueue)) =>
                (tcpOutConnFut, upAndDownStreamsApiFut, sinkQueue, sourceQueue)
            }.named("client").run()

        for {
          outConn <- outConnFut
          _ <- source.offer(StreamEvent.StreamOpen(Seq.empty))
          Some(StreamEvent.StreamOpen(_)) <- sink.pull()
          _ <- source.offer(StreamEvent.StreamClose())
          Some(StreamEvent.StreamClose()) <- sink.pull()
          _ = source.complete()
          None <- sink.pull()
        }
          yield ()
      }
    }
}
