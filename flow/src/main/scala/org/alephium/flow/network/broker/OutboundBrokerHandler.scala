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

package org.alephium.flow.network.broker

import akka.actor.Props
import akka.io.Tcp
import java.net.InetSocketAddress

import org.alephium.flow.network.CliqueManager
import org.alephium.flow.network.TcpController
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, Duration, EventStream, TimeStamp}

object OutboundBrokerHandler {
  case object Retry
}

trait OutboundBrokerHandler extends BrokerHandler with EventStream.Publisher {
  def selfCliqueInfo: CliqueInfo

  implicit def networkSetting: NetworkSetting

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def preStart(): Unit = {
    super.preStart()
    publishEvent(TcpController.ConnectTo(remoteAddress, ActorRefT(self)))
  }

  val until: TimeStamp = TimeStamp.now() + networkSetting.retryTimeout

  var connection: ActorRefT[Tcp.Command]                            = _
  var brokerConnectionHandler: ActorRefT[ConnectionHandler.Command] = _

  override def receive: Receive = connecting

  protected def connectionHandler(
      remoteAddress: InetSocketAddress,
      connection: ActorRefT[Tcp.Command])(implicit networkSetting: NetworkSetting): Props =
    ConnectionHandler.clique(remoteAddress, connection, ActorRefT(self))

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      publishEvent(TcpController.ConnectTo(remoteAddress, ActorRefT(self)))

    case _: Tcp.Connected =>
      connection = ActorRefT[Tcp.Command](sender())
      brokerConnectionHandler = {
        val ref = context.actorOf(connectionHandler(remoteAddress, connection))
        context watch ref
        ActorRefT(ref)
      }
      context become handShaking

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, OutboundBrokerHandler.Retry, Duration.ofSecondsUnsafe(1))
        ()
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        context stop self
      }
  }

  override def handShakeDuration: Duration = networkSetting.handshakeTimeout

  override def handShakeMessage: Payload = Hello.unsafe(selfCliqueInfo.selfInterBrokerInfo)

  override def pingFrequency: Duration = networkSetting.pingFrequency
}
