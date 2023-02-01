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

package org.alephium.protocol.vm

import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.io.{IOResult, RocksDBSource, StorageFixture}
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm.event.LogStorage
import org.alephium.util.{AlephiumSpec, AVector}

class WorldStateSpec extends AlephiumSpec with NoIndexModelGenerators with StorageFixture {
  def generateAsset: Gen[(TxOutputRef, TxOutput)] = {
    for {
      groupIndex     <- groupIndexGen
      assetOutputRef <- assetOutputRefGen(groupIndex)
      assetOutput    <- assetOutputGen(groupIndex)()
    } yield (assetOutputRef, assetOutput)
  }

  def newLogStorage(dbSource: RocksDBSource): LogStorage = {
    LogStorage(
      newDB(dbSource, RocksDBSource.ColumnFamily.Log),
      newDB(dbSource, RocksDBSource.ColumnFamily.Log),
      newDB(dbSource, RocksDBSource.ColumnFamily.LogCounter)
    )
  }

  // scalastyle:off method.length
  def test[T, R1, R2, R3](initialWorldState: WorldState[T, R1, R2, R3], isLemanFork: Boolean) = {
    val (assetOutputRef, assetOutput) = generateAsset.sample.get
    val (contractId, code, _, mutFields, contractOutputRef, contractOutput) =
      generateContract().sample.get
    val (contractId1, _, _, _, contractOutputRef1, contractOutput1) = generateContract().sample.get

    val contractObj = code.toObjectUnsafe(contractId, AVector.empty, mutFields)
    var worldState  = initialWorldState

    def update(f: => IOResult[T]) = f.rightValue match {
      case _: Unit => ()
      case newWorldState: WorldState[_, _, _, _] =>
        worldState = newWorldState.asInstanceOf[WorldState[T, R1, R2, R3]]
      case _ => ???
    }

    worldState.getOutput(assetOutputRef).isLeft is true
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractObj(contractId).isLeft is true
    worldState.contractExists(contractId) isE false
    worldState.removeAsset(assetOutputRef).isLeft is true
    worldState.removeAsset(contractOutputRef).isLeft is true

    update(worldState.addAsset(assetOutputRef, assetOutput))
    worldState.getOutput(assetOutputRef) isE assetOutput

    update {
      worldState.createContractUnsafe(
        contractId,
        code,
        AVector.empty,
        mutFields,
        contractOutputRef,
        contractOutput,
        isLemanFork
      )
    }
    worldState.getContractObj(contractId) isE contractObj
    worldState.contractExists(contractId) isE true
    worldState.getContractCode(code.hash) isE WorldState.CodeRecord(code, 1)
    worldState.getOutput(contractOutputRef) isE contractOutput

    update(worldState.removeAsset(assetOutputRef))
    worldState.getOutput(assetOutputRef).isLeft is true

    val newState = AVector[Val](Val.Bool(false))
    assume(newState != mutFields)
    update(
      worldState.createContractUnsafe(
        contractId1,
        code,
        AVector.empty,
        newState,
        contractOutputRef1,
        contractOutput1,
        isLemanFork
      )
    )
    worldState.getContractCode(code.hash) isE WorldState.CodeRecord(code, 2)
    worldState
      .getContractObj(contractId1) isE code.toObjectUnsafe(contractId1, AVector.empty, newState)

    update(worldState.removeContract(contractId))
    worldState.getContractObj(contractId).isLeft is true
    worldState.contractExists(contractId) isE false
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractState(contractId).isLeft is true
    worldState.getContractCode(code.hash) isE WorldState.CodeRecord(code, 1)
    worldState.removeContract(contractId).isLeft is true

    update(worldState.removeContract(contractId1))
    worldState.getContractObj(contractId1).isLeft is true
    worldState.contractExists(contractId1) isE false
    worldState.getOutput(contractOutputRef1).isLeft is true
    worldState.getContractState(contractId).isLeft is true
    worldState.getContractCode(code.hash).isLeft is true
    worldState.removeContract(contractId1).isLeft is true
  }

  trait Fixture {
    val storage = newDBStorage()
    val persisted = WorldState.emptyPersisted(
      newDB(storage, RocksDBSource.ColumnFamily.All),
      newDB(storage, RocksDBSource.ColumnFamily.All),
      newLogStorage(storage)
    )
    val cached = persisted.cached()
  }

  it should "test mutable world state for Genesis fork" in new Fixture {
    test(cached, isLemanFork = false)
  }

  it should "test mutable world state for Leman fork" in new Fixture {
    test(cached, isLemanFork = true)
  }

  it should "test immutable world state for Genesis fork" in new Fixture {
    test(persisted, isLemanFork = false)
  }

  it should "test immutable world state for Leman fork" in new Fixture {
    test(persisted, isLemanFork = true)
  }

  it should "test the event key of contract creation and destruction" in {
    createContractEventId.bytes is Hash.zero.bytes.init ++ ByteString(-1)
    destroyContractEventId.bytes is Hash.zero.bytes.init ++ ByteString(-2)
  }

  trait StagingFixture {
    val isLemanFork: Boolean = scala.util.Random.nextBoolean()
    val storage              = newDBStorage()
    val worldState = WorldState.emptyCached(
      newDB(storage, RocksDBSource.ColumnFamily.All),
      newDB(storage, RocksDBSource.ColumnFamily.All),
      newLogStorage(storage)
    )
    val staging = worldState.staging()

    val (contractId, code, _, mutFields, contractOutputRef, contractOutput) =
      generateContract().sample.get
    val contractObj = code.toObjectUnsafe(contractId, AVector.empty, mutFields)
    staging.createContractUnsafe(
      contractId,
      code,
      AVector.empty,
      mutFields,
      contractOutputRef,
      contractOutput,
      isLemanFork
    ) isE ()
    staging.getContractObj(contractId) isE contractObj
    worldState.getContractObj(contractId).isLeft is true
  }

  it should "commit staged changes" in new StagingFixture {
    staging.commit()
    staging.getContractObj(contractId) isE contractObj
    staging.contractExists(contractId) isE true
    worldState.getContractObj(contractId) isE contractObj
    worldState.contractExists(contractId) isE true
  }

  it should "rollback staged changes" in new StagingFixture {
    staging.rollback()
    staging.getContractObj(contractId).isLeft is true
    staging.getContractState(contractId).isLeft is true
    staging.contractExists(contractId) isE false
    staging.getContractCode(code.hash).isLeft is true
    staging.getContractAsset(contractId).isLeft is true
    worldState.getContractObj(contractId).isLeft is true
    worldState.getContractState(contractId).isLeft is true
    worldState.contractExists(contractId) isE false
    worldState.getContractCode(code.hash).isLeft is true
    worldState.getContractAsset(contractId).isLeft is true
  }
}
