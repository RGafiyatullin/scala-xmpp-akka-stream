import akka.stream.{FlowShape, Graph, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import akka.util.ByteString
import com.github.rgafiyatullin.xml.common.{HighLevelEvent, QName}
import com.github.rgafiyatullin.xml.dom.Node
import com.github.rgafiyatullin.xmpp_akka_stream.Xmpp
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.{StreamEventCodec, Utf8Codec, XmlEventCodec}
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

final class ThroughputTest extends TestBase {
  override def futureAwaitDuration: FiniteDuration = 20.seconds

  implicit class FutureWithTimeIt[T](f: Future[T]) {
    type NanoTime = Long
    private def nanoTime(): NanoTime = System.nanoTime()

    def timeIt()(implicit ec: ExecutionContext): Future[FiniteDuration] = {
      val t0 = nanoTime()
      f.map(_ => (nanoTime() - t0).nanos)
    }
  }

  private final class Data {
    val testSize: Int = 100000

    private def runFlow[In, Out](flow: Graph[FlowShape[In, Out], _])(input: Seq[In]): Seq[Out] =
      Await.result(
        withMaterializer { implicit mat => futureOk {
          Source(input.toList)
            .via(flow).log("run-flow")
            .toMat(Sink.seq)(Keep.right).run()
        } }, futureAwaitDuration)


    lazy val stanzas: Seq[Node] =
      for (i <- 1 to testSize)
        yield Node(QName("ns", s"local-name-$i"))

    lazy val streamEvents: Seq[StreamEvent] =
      StreamEvent.StreamOpen(Seq.empty) +: stanzas.map(StreamEvent.Stanza) :+ StreamEvent.StreamClose()

    lazy val xmlEvents: Seq[HighLevelEvent] =
      runFlow(StreamEventCodec.encode(dumpStreamErrorCause = true))(streamEvents)

    lazy val strings: Seq[String] =
      runFlow(XmlEventCodec.encode)(xmlEvents)

    lazy val byteStrings: Seq[ByteString] =
      runFlow(Utf8Codec.encode)(strings)
  }

  private lazy val data: Data = new Data

  def runTimed(name: String)(runnableGraph: RunnableGraph[Future[_]]): Unit =
    unit(
      withMaterializer { implicit mat =>
        implicit val ec: ExecutionContext = mat.executionContext
        futureOk {
          val durationFut =
            runnableGraph.run().timeIt()

          durationFut.foreach(fd =>
            mat.system.log.warning("[{}] DURATION: {}/{} => {}hz",
              name, data.testSize, fd.toMillis,
              data.testSize.toDouble / fd.toMillis.toDouble * 1000.0))

          durationFut
        }
      })

  "upstream" should "measure StreamEventCodec.encode" in
    runTimed("U:StreamEventCodec.encode") {
      Source(data.streamEvents.toList)
        .via(StreamEventCodec.encode)
        .toMat(Sink.ignore)(Keep.right)
    }

  it should "measure XmlEventCodec.encode" in
    runTimed("U:XmlEventCodec.encode") {
      Source(data.xmlEvents.toList)
        .via(XmlEventCodec.encode)
        .toMat(Sink.ignore)(Keep.right)
    }

  it should "measure Utf8Codec.encode" in
    runTimed("U:Utf8Codec.encode") {
      Source(data.strings.toList)
        .via(Utf8Codec.encode)
        .toMat(Sink.ignore)(Keep.right)
    }

  "downstream" should "measure Utf8Codec.decode" in
    runTimed("D:Utf8Codec.decode") {
      Source(data.byteStrings.toList)
        .via(Utf8Codec.decode)
        .toMat(Sink.ignore)(Keep.right)
    }

  it should "measure Utf8Codec.decode (coalesced)" in
    runTimed("D:Utf8Codec.decode") {
      Source(List(data.byteStrings.reduce(_ ++ _)))
        .via(Utf8Codec.decode)
        .toMat(Sink.ignore)(Keep.right)
    }

  it should "measure XmlEventCodec.decode" in
    runTimed("D:XmlEventCodec.decode") {
      Source(data.strings.toList)
        .via(XmlEventCodec.decode)
        .toMat(Sink.ignore)(Keep.right)
    }

  it should "measure StreamEventCodec.decode" in
    runTimed("D:StreamEventCodec.decode") {
      Source(data.xmlEvents.toList)
        .via(StreamEventCodec.decode)
        .toMat(Sink.ignore)(Keep.right)
    }

  "xmpp-protocol" should "measure downstream" in
    runTimed("Downstream") {
      Source(data.streamEvents.toList)
        .via(Xmpp.plaintextXml.downstream).log("downstream")
        .toMat(Sink.ignore)(Keep.right)
    }

  it should "measure upstream" in
    runTimed("Upstream") {
      Source(data.byteStrings.toList)
        .via(Xmpp.plaintextXml.upstream).log("upstream")
        .toMat(Sink.ignore)(Keep.right)
    }
}
