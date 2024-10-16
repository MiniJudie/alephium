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

import org.alephium.api.model.BlockEntry
import org.alephium.app.WebSocketServer.{EventHandler, WsEventType}
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.json.Json._
import org.alephium.util._

class WebSocketEventHandlerSpec extends AlephiumSpec with ServerFixture {
  import ServerFixture._

  behavior of "eventHandler"

  it should "encode BlockEntry" in {
    val blockEntry = BlockEntry.from(dummyBlock, 0).rightValue
    val result     = writeJs(blockEntry)
    show(result) is write(blockEntry)
  }

  it should "parse subscription EventType" in {
    EventHandler.parseSubscription(s"${EventHandler.SubscribePrefix}block").get is WsEventType.Block
    EventHandler.parseSubscription(s"${EventHandler.SubscribePrefix}tx").get is WsEventType.Tx
    EventHandler.parseSubscription(s"${EventHandler.SubscribePrefix}gandalf").isEmpty is true
  }

  it should "build Notification from Event" in {
    val notification = EventHandler.buildNotification(BlockNotify(dummyBlock, 0)).rightValue
    val blockEntry   = BlockEntry.from(dummyBlock, 0).rightValue
    notification.method is WsEventType.Block.name
    show(notification.params) is write(blockEntry)
  }
}
