// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus, Message, MessageConsumer}
import io.vertx.core.http.{HttpServer, HttpServerOptions, ServerWebSocket}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.app.WebSocketServer.WsSubscription
import org.alephium.flow.client.Node
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.protocol.config.NetworkConfig
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, BaseActor, ConcurrentHashMap, EventBus}

trait HttpServerLike {
  def underlying: HttpServer
}

final case class SimpleHttpServer(underlying: HttpServer) extends HttpServerLike

object SimpleHttpServer {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(httpOptions: HttpServerOptions = new HttpServerOptions): SimpleHttpServer =
    SimpleHttpServer(Vertx.vertx().createHttpServer(httpOptions))
}

final case class WebSocketServer(
    underlying: HttpServer,
    eventHandler: ActorRef,
    subscribers: ConcurrentHashMap[WsSubscription.SubscriberId, WsSubscription.SubscriptionTime]
) extends HttpServerLike

object WebSocketServer extends StrictLogging {

  def apply(
      flowSystem: ActorSystem,
      node: Node,
      maxConnections: Int,
      httpOptions: HttpServerOptions
  )(implicit networkConfig: NetworkConfig): WebSocketServer = {
    val vertx = Vertx.vertx()
    val eventHandlerRef =
      EventHandler.getSubscribedEventHandlerRef(vertx.eventBus(), node.eventBus, flowSystem)
    val subscribers =
      ConcurrentHashMap.empty[WsSubscription.SubscriberId, WsSubscription.SubscriptionTime]
    val server = vertx.createHttpServer(httpOptions)
    server.webSocketHandler { webSocket =>
      if (subscribers.size >= maxConnections) {
        logger.warn(s"WebSocket connections reached max limit ${subscribers.size}")
        webSocket.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
      } else if (webSocket.path().equals("/ws")) {
        webSocket.closeHandler { _ =>
          val _ = subscribers.remove(webSocket.textHandlerID())
        }

        // Receive subscription messages from the client
        webSocket.textMessageHandler { message =>
          WsSubscription.parseSubscription(message) match {
            case Some(subscription) =>
              EventHandler.subscribeToEvents(vertx, webSocket, subscription)
            case None =>
              webSocket.reject(HttpResponseStatus.BAD_REQUEST.code())
          }
          ()
        }

        subscribers.put(webSocket.textHandlerID(), System.currentTimeMillis())
      } else {
        webSocket.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    }
    WebSocketServer(server, eventHandlerRef, subscribers)
  }

  sealed trait WsSubscription {
    def method: String
  }

  object WsSubscription {
    private val SubscribeCmd = "subscribe"
    type SubscriberId     = String
    type SubscriptionTime = Long

    case object Block extends WsSubscription { val method = "block" }
    case object Tx    extends WsSubscription { val method = "tx"    }

    def fromString(name: String): Option[WsSubscription] = name match {
      case Block.`method` => Some(Block)
      case Tx.`method`    => Some(Tx)
      case _              => None
    }

    def buildSubscribeMsg(eventType: WsSubscription): String = s"$SubscribeCmd:${eventType.method}"

    def parseSubscription(message: String): Option[WsSubscription] = {
      message.split(":").toList match {
        case SubscribeCmd :: event :: Nil =>
          WsSubscription.fromString(event)
        case _ => None
      }
    }
  }

  object EventHandler extends ApiModelCodec {

    // scalastyle:off null
    def subscribeToEvents(
        vertx: Vertx,
        ws: ServerWebSocket,
        subscription: WsSubscription
    ): MessageConsumer[String] = {
      vertx
        .eventBus()
        .consumer[String](
          subscription.method,
          new io.vertx.core.Handler[Message[String]] {
            override def handle(message: Message[String]): Unit = {
              if (!ws.isClosed) {
                val _ = ws.writeTextMessage(message.body(), null)
              }
            }
          }
        )
    }
    // scalastyle:off null

    def buildNotification(
        event: EventBus.Event
    )(implicit networkConfig: NetworkConfig): Either[String, Notification] = {
      event match {
        case BlockNotify(block, height) =>
          BlockEntry.from(block, height).map { blockEntry =>
            Notification(WsSubscription.Block.method, writeJs(blockEntry))
          }
      }
    }

    def getSubscribedEventHandlerRef(
        vertxEventBus: VertxEventBus,
        eventBusRef: ActorRefT[EventBus.Message],
        flowSystem: ActorSystem
    )(implicit
        networkConfig: NetworkConfig
    ): ActorRef = {
      val eventHandlerRef = flowSystem.actorOf(Props(new EventHandler(vertxEventBus)))
      eventBusRef.tell(EventBus.Subscribe, eventHandlerRef)
      eventHandlerRef
    }
  }

  class EventHandler(vertxEventBus: VertxEventBus)(implicit val networkConfig: NetworkConfig)
      extends BaseActor
      with ApiModelCodec {

    def receive: Receive = { case event: EventBus.Event =>
      EventHandler.buildNotification(event) match {
        case Right(notification) =>
          val _ = vertxEventBus.publish(notification.method, write(notification))
        case Left(error) =>
          log.error(error)
      }
    }
  }
}
