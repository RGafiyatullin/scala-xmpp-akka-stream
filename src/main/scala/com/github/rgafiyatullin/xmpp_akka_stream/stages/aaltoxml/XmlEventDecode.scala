package com.github.rgafiyatullin.xmpp_akka_stream.stages.aaltoxml

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.{ByteString, Timeout}
import com.fasterxml.aalto.{AsyncByteArrayFeeder, AsyncXMLInputFactory, AsyncXMLStreamReader}
import com.fasterxml.aalto.stax.InputFactoryImpl
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._
import com.github.rgafiyatullin.xml.common.{Attribute, HighLevelEvent, Position}
import com.github.rgafiyatullin.xml.stream_parser.high_level_parser.HighLevelParserError
import com.github.rgafiyatullin.xmpp_akka_stream.codecs.XmlEventCodec
import javax.xml.stream.XMLStreamConstants

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object XmlEventDecode {
  type XmlEvent = XmlEventCodec.XmlEvent
  val inlet: Inlet[ByteString] = Inlet("XmlEventDecode.In")
  val outlet: Outlet[XmlEvent] = Outlet("XmlEventDecode.Out")

  type Shape = FlowShape[ByteString, XmlEvent]
  type MaterializedValue = Future[Api]

  private val emptyPosition: Position = Position.withoutPosition

  private object messages {
    case object Reset
  }

  final class Api(actorRef: ActorRef, executionContext: ExecutionContext) {
    import akka.pattern.ask
    def reset()(implicit timeout: Timeout): Future[Done] =
      actorRef.ask(messages.Reset).mapTo[Done]
  }

  object State {
    def create(apiPromise: Promise[Api]): State =
      StateInitial(apiPromise)
  }

  sealed trait State extends Stage.State[XmlEventDecode] {

  }

  final case class StateInitial(apiPromise: Promise[Api]) extends State {
    override def receiveEnabled: Boolean = true

    override def preStart(ctx: PreStartContext[XmlEventDecode]): PreStartContext[XmlEventDecode] = {
      apiPromise.success(new Api(ctx.stageActorRef, ctx.executionContext))
      val inputFactory = new InputFactoryImpl()
      val parser = inputFactory.createAsyncForByteArray()
      ctx.withState(StateNormal(inputFactory, parser))
    }

    override def postStop(ctx: PostStopContext[XmlEventDecode]): PostStopContext[XmlEventDecode] = {
      apiPromise.tryFailure(new UninitializedError())
      ctx
    }
  }

  final case class StateNormal(inputFactory: AsyncXMLInputFactory, parser: AsyncXMLStreamReader[AsyncByteArrayFeeder]) extends State {

    override def receive(ctx: ReceiveContext.NotReplied[XmlEventDecode]): ReceiveContext[XmlEventDecode] =
      ctx.handleWith {
        case messages.Reset =>
          parser.closeCompletely()
          val stateNext = copy(parser = inputFactory.createAsyncForByteArray())
          ctx
            .withState(stateNext)
            .reply(Status.Success(Done))
      }


    override def inletOnPush(ctx: InletPushedContext[XmlEventDecode]): InletPushedContext[XmlEventDecode] = {
      val inputByteArray = ctx.peek(inlet).toArray
      parser.getInputFeeder.feedInput(inputByteArray, 0, inputByteArray.length)

      fetchLoop(ctx.drop(inlet))
    }

    override def outletOnPull(ctx: OutletPulledContext[XmlEventDecode]): OutletPulledContext[XmlEventDecode] =
      fetchLoop(ctx)


    @tailrec
    private def fetchLoop[Ctx <: Context[Ctx, XmlEventDecode]](ctx: Ctx): Ctx =
      if (!parser.hasNext)
        ctx.completeStage()
      else
        Try(parser.next()) match {
          case Success(AsyncXMLStreamReader.EVENT_INCOMPLETE) if !ctx.isClosed(inlet) =>
            ctx.pull(inlet)

          case Success(AsyncXMLStreamReader.EVENT_INCOMPLETE) if ctx.isClosed(inlet) =>
            ctx.completeStage()

          case Success(XMLStreamConstants.START_DOCUMENT) =>
            fetchLoop(ctx)

          case Success(XMLStreamConstants.END_DOCUMENT) =>
            ctx.completeStage()

          case Success(XMLStreamConstants.PROCESSING_INSTRUCTION) =>
            ctx.push(
              outlet, HighLevelEvent.ProcessingInstrutcion(
                emptyPosition, parser.getPITarget, parser.getPIData))


          case Success(XMLStreamConstants.START_ELEMENT) =>
            val ns = parser.getNamespaceURI
            val prefix = parser.getNamespaceContext.getPrefix(ns)
            val localName = parser.getLocalName

            val attributes =
              for (idx <- 0 until parser.getAttributeCount) yield {
                val attrPrefix = parser.getAttributePrefix(idx)
                val attrLocalName = parser.getAttributeLocalName(idx)
                val attrValue = parser.getAttributeValue(idx)

                if (attrPrefix.nonEmpty)
                  Attribute.Prefixed(attrPrefix, attrLocalName, attrValue)
                else
                  Attribute.Unprefixed(attrLocalName, attrValue)
              }


            val event = HighLevelEvent.ElementOpen(
              emptyPosition,
              prefix, localName, ns, attributes.toList)
            ctx.push(outlet, event)

          case Success(XMLStreamConstants.END_ELEMENT) =>
            val ns = parser.getNamespaceURI
            val prefix = parser.getNamespaceContext.getPrefix(ns)
            val localName = parser.getLocalName

            val event = HighLevelEvent.ElementClose(emptyPosition, prefix, localName, ns)
            ctx.push(outlet, event)

          case Success(XMLStreamConstants.CHARACTERS) =>
            ctx.push(
              outlet, HighLevelEvent.PCData(emptyPosition, parser.getText))

          case Success(XMLStreamConstants.COMMENT) =>
            ctx.push(
              outlet, HighLevelEvent.Comment(emptyPosition, parser.getText))

          case Success(XMLStreamConstants.SPACE) =>
            ctx.push(
              outlet, HighLevelEvent.Whitespace(emptyPosition, parser.getText))

          case Success(XMLStreamConstants.CDATA) =>
            ctx.push(
              outlet, HighLevelEvent.CData(emptyPosition, parser.getText))

          case Success(unexpected) =>
            ctx.log.warning("Unexpected stax-event type: {}", unexpected)
            fetchLoop(ctx)

          case Failure(reason) =>
            ctx.failStage(reason)
        }

    override def inletOnUpstreamFinish(ctx: InletFinishedContext[XmlEventDecode]): InletFinishedContext[XmlEventDecode] = {
      parser.getInputFeeder.endOfInput()
      if (ctx.isAvailable(outlet))
        fetchLoop(ctx)
      else
        ctx
    }
  }

  def apply(): XmlEventDecode =
    new XmlEventDecode()
}

final class XmlEventDecode private () extends Stage[XmlEventDecode] {
  override type Shape = XmlEventDecode.Shape
  override type State = XmlEventDecode.State
  override type MatValue = XmlEventDecode.MaterializedValue

  override def shape: Shape = FlowShape.of(XmlEventDecode.inlet, XmlEventDecode.outlet)

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes): (State, MatValue) = {
    val apiPromise = Promise[XmlEventDecode.Api]()
    val state = XmlEventDecode.State.create(apiPromise)
    (state, apiPromise.future)
  }
}
