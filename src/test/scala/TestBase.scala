import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.github.rgafiyatullin.xml.common.Attribute
import com.github.rgafiyatullin.xml.dom.Node
import com.github.rgafiyatullin.xmpp_protocol.streams.StreamEvent
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Milliseconds, Seconds, Span}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

trait TestBase extends FlatSpec with Matchers with ScalaFutures {
  def removeNsImportsFromSE(se: StreamEvent): StreamEvent =
    se match {
      case StreamEvent.StreamOpen(attrs) => StreamEvent.StreamOpen(attrs.filter(!_.isInstanceOf[Attribute.NsImport]))
      case StreamEvent.Stanza(stanza) => StreamEvent.Stanza(removeNsImportsFromNode(stanza))
      case asIs => asIs
    }

  def removeNsImportsFromNode(node: Node): Node =
    node
      .withAttributes(node.attributes.filter(!_.isInstanceOf[Attribute.NsImport]))
      .withChildren(node.children.map(removeNsImportsFromNode))


  def futureAwaitDuration: FiniteDuration = 5.seconds

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(Span(100, Milliseconds), Span(10, Milliseconds))

  def withActorSystem[T](f: ActorSystem => Future[T]): Future[T] = {
    val actorSystem = ActorSystem()
    try f(actorSystem)
    finally {
      actorSystem.terminate()
      ()
    }
  }

  def withMaterializer[T](f: ActorMaterializer => Future[T]): Future[T] =
    withActorSystem { actorSystem =>
      val mat = ActorMaterializer()(actorSystem)
      f(mat)
    }

  def futureOk[T](f: Future[T]): Future[T] = {
    val _ = Await.result(f, futureAwaitDuration)
    f
  }

  def unit(anything: Any): Unit = ()
}
