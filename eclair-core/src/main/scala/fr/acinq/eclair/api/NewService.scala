package fr.acinq.eclair.api

import akka.http.scaladsl.server._
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, Satoshi}
import fr.acinq.eclair.{Kit, ShortChannelId}
import FormParamExtractors._
import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.{ContentTypes, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.{`Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Cache-Control`}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.directives.{Credentials, LoggingMagnet}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import fr.acinq.eclair.payment.{PaymentLifecycle, PaymentReceived, PaymentRequest}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait NewService extends Directives with Logging with MetaService {

  import JsonSupport.formats
  import JsonSupport.serialization
  // important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541
  import JsonSupport.marshaller

  def password: String

  def eclairApi: EclairApi

  implicit val actorSystem: ActorSystem
  implicit lazy val ec = actorSystem.dispatcher
  implicit val mat: ActorMaterializer

  // a named and typed URL parameter used across several routes, 32-bytes hex-encoded
  val channelIdNamedParameter = "channelId".as[ByteVector32](sha256HashUnmarshaller)
  val shortChannelIdNamedParameter = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)

  val apiExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}")
      complete(StatusCodes.InternalServerError, s"Error: $t")
  }

  val apiRejectionHandler = RejectionHandler.newBuilder()
    .handle {
      case UnknownMethodRejection => complete(StatusCodes.BadRequest, "Wrong method or params combination")
      case UnknownParamsRejection => complete(StatusCodes.BadRequest, "Wrong params combination")
    }
    .result()

  val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(POST) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

  lazy val makeSocketHandler: Flow[Message, TextMessage.Strict, NotUsed] = {

    // create a flow transforming a queue of string -> string
    val (flowInput, flowOutput) = Source.queue[String](10, OverflowStrategy.dropTail).toMat(BroadcastHub.sink[String])(Keep.both).run()

    // register an actor that feeds the queue when a payment is received
    actorSystem.actorOf(Props(new Actor {
      override def preStart: Unit = context.system.eventStream.subscribe(self, classOf[PaymentReceived])

      def receive: Receive = {
        case received: PaymentReceived => flowInput.offer(received.paymentHash.toString)
      }
    }))

    Flow[Message]
      .mapConcat(_ => Nil) // Ignore heartbeats and other data from the client
      .merge(flowOutput) // Stream the data we want to the client
      .map(TextMessage.apply)
  }

  val timeoutResponse: HttpRequest => HttpResponse = { r =>
    HttpResponse(StatusCodes.RequestTimeout).withEntity(ContentTypes.`application/json`, """{ "result": null, "error": { "code": 408, "message": "request timed out"} } """)
  }

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = actorSystem.scheduler)(Future.successful(None)) // force a 1 sec pause to deter brute force
  }

  case object UnknownMethodRejection extends Rejection
  case object UnknownParamsRejection extends Rejection


  val route: Route = {
    respondWithDefaultHeaders(customHeaders) {
      handleExceptions(apiExceptionHandler) {
        handleRejections(apiRejectionHandler){
          withRequestTimeoutResponse(timeoutResponse){
            authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
              post {
                path("getinfo") {
                  complete(eclairApi.getInfoResponse())
                } ~
                  path("help") {
                    complete(eclairApi.help())
                  } ~
                  path("connect") {
                    formFields("uri".as[String]) { uri =>
                      complete(eclairApi.connect(uri))
                    } ~ formFields("nodeId".as[PublicKey], "host".as[String], "port".as[Int]) { (nodeId, host, port) =>
                      complete(eclairApi.connect(s"$nodeId@$host:$port"))
                    }
                  } ~
                  path("open") {
                    formFields("nodeId".as[PublicKey], "fundingSatoshis".as[Long], "pushMsat".as[Long].?, "fundingFeerateSatByte".as[Long].?, "channelFlags".as[Int].?) {
                      (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags) =>
                        complete(eclairApi.open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags))
                    }
                  } ~
                  path("close") {
                    formFields(channelIdNamedParameter, "scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { (channelId, scriptPubKey_opt) =>
                      complete(eclairApi.close(Left(channelId), scriptPubKey_opt))
                    } ~ formFields(shortChannelIdNamedParameter, "scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { (shortChannelId, scriptPubKey_opt) =>
                      complete(eclairApi.close(Right(shortChannelId), scriptPubKey_opt))
                    }
                  } ~
                  path("forceclose") {
                    formFields(channelIdNamedParameter) { channelId =>
                      complete(eclairApi.forceClose(Left(channelId)))
                    } ~ formFields(shortChannelIdNamedParameter) { shortChannelId =>
                        complete(eclairApi.forceClose(Right(shortChannelId)))
                    }
                  } ~
                  path("updaterelayfee") {
                    formFields(channelIdNamedParameter, "feeBaseMsat".as[Long], "feeProportionalMillionths".as[Long]) { (channelId, feeBase, feeProportional) =>
                      complete(eclairApi.updateRelayFee(channelId.toString, feeBase, feeProportional))
                    }
                  } ~
                  path("peers") {
                    complete(eclairApi.peersInfo())
                  } ~
                  path("channels") {
                    formFields("toRemoteNodeId".as[PublicKey].?) { toRemoteNodeId_opt =>
                      complete(eclairApi.channelsInfo(toRemoteNodeId_opt))
                    }
                  } ~
                  path("channel") {
                    formFields(channelIdNamedParameter) { channelId =>
                      complete(eclairApi.channelInfo(channelId))
                    }
                  } ~
                  path("allnodes") {
                    complete(eclairApi.allnodes())
                  } ~
                  path("allchannels") {
                    complete(eclairApi.allchannels())
                  } ~
                  path("allupdates") {
                    formFields("nodeId".as[PublicKey].?) { nodeId_opt =>
                      complete(eclairApi.allupdates(nodeId_opt))
                    }
                  } ~
                  path("receive") {
                    formFields("description".as[String], "amountMsat".as[Long].?, "expireIn".as[Long].?) { (desc, amountMsat, expire) =>
                      complete(eclairApi.receive(desc, amountMsat, expire))
                    }
                  } ~
                  path("parseinvoice") {
                    formFields("invoice".as[PaymentRequest]) { invoice =>
                      complete(invoice)
                    }
                  } ~
                  path("findroute") {
                    formFields("invoice".as[PaymentRequest], "amountMsat".as[Long].?) {
                      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) => complete(eclairApi.findRoute(nodeId, amount.toLong, invoice.routingInfo))
                      case (invoice, Some(overrideAmount)) => complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, invoice.routingInfo))
                      case _ => reject(UnknownParamsRejection)
                    } ~ formFields("nodeId".as[PublicKey], "amountMsat".as[Long]) { (nodeId, amount) =>
                      complete(eclairApi.findRoute(nodeId, amount))
                    }
                  } ~
                  path("send") {
                    formFields("invoice".as[PaymentRequest], "amountMsat".as[Long].?) {
                      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) =>
                        complete(eclairApi.send(nodeId, amount.toLong, invoice.paymentHash, invoice.routingInfo))
                      case (invoice, Some(overrideAmount)) =>
                        complete(eclairApi.send(invoice.nodeId, overrideAmount, invoice.paymentHash, invoice.routingInfo))
                      case _ => reject(UnknownParamsRejection)
                    } ~ formFields("amountMsat".as[Long], "paymentHash".as[ByteVector32](sha256HashUnmarshaller), "nodeId".as[PublicKey]) { (amountMsat, paymentHash, nodeId) =>
                      complete(eclairApi.send(nodeId, amountMsat, paymentHash))
                    }
                  } ~
                  path("checkpayment") {
                    formFields("paymentHash".as[ByteVector32](sha256HashUnmarshaller)) { paymentHash =>
                      complete(eclairApi.checkpayment(paymentHash))
                    } ~ formFields("invoice".as[PaymentRequest]) { invoice =>
                      complete(eclairApi.checkpayment(invoice.paymentHash))
                    }
                  } ~
                  path("audit") {
                    formFields("from".as[Long].?, "to".as[Long].?) { (from, to) =>
                      complete(eclairApi.audit(from, to))
                    }
                  } ~
                  path("networkfees") {
                    formFields("from".as[Long].?, "to".as[Long].?) { (from, to) =>
                      complete(eclairApi.networkFees(from, to))
                    }
                  } ~
                  path("channelstats") {
                    complete(eclairApi.channelStats())
                  } ~
                  path("ws") {
                    handleWebSocketMessages(makeSocketHandler)
                  } ~
                  path(Segment) { _ => reject(UnknownMethodRejection) }
              }
            }
          }
        }
      }
    }
  }

}