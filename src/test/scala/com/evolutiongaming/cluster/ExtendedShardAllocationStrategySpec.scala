package com.evolutiongaming.cluster

import akka.actor.{ActorRef, Address}
import akka.cluster.sharding.ShardRegion.ShardId

import scala.collection.immutable
import scala.concurrent.Future

class ExtendedShardAllocationStrategySpec extends AllocationStrategySpec {

  "ExtendedShardAllocationStrategy" should "pass through requests if no nodesToDeallocate, limit result size" in new Scope {

    val strategy = new TestExtendedShardAllocationStrategy() {
      override val nodesToDeallocate = () => Set.empty[Address]
      override val result = ShardIds.toSet -- RebalanceInProgress
    }

    strategy.rebalance(CurrentShardAllocations, RebalanceInProgress).futureValue shouldBe
      ((ShardIds.toSet -- RebalanceInProgress) take strategy.maxSimultaneousRebalance - RebalanceInProgress.size)

    strategy.passedCurrentShardAllocations shouldBe Some(CurrentShardAllocations)
    strategy.passedRebalanceInProgress shouldBe Some(RebalanceInProgress)
  }

  it should "rebalance shards from nodesToDeallocate first, limit result size" in new Scope {

    val strategy = new TestExtendedShardAllocationStrategy() {
      override val maxSimultaneousRebalance = 15
      override val nodesToDeallocate = () => Set(testAddress("Address2"), testAddress("Address4"))
      override val result = ShardIds.slice(24, 29).toSet -- RebalanceInProgress
    }

    val mandatoryRebalance = (ShardIds.slice(10, 20) ++ ShardIds.slice(30, 40)).toSet

    strategy.rebalance(CurrentShardAllocations, RebalanceInProgress).futureValue shouldBe
      (((ShardIds.slice(24, 29).toSet --
        RebalanceInProgress) take
        strategy.maxSimultaneousRebalance - RebalanceInProgress.size - mandatoryRebalance.size) ++
        mandatoryRebalance -- RebalanceInProgress)


    strategy.passedCurrentShardAllocations shouldBe Some(CurrentShardAllocations)
    strategy.passedRebalanceInProgress shouldBe Some(RebalanceInProgress)
  }

  it should "filter out nodesToDeallocate from nodes passed to doAllocate()" in new Scope {

    val ignoredNodes = Set(testAddress("Address1"), testAddress("Address4"))

    val strategy = new TestExtendedShardAllocationStrategy() {

      override val nodesToDeallocate = () => ignoredNodes
      override val result = Set.empty
    }

    strategy.allocateShard(mockedHostRef("Address2"), ShardIds(0), CurrentShardAllocations).futureValue shouldBe
      mockedHostRef("Address2")

    val filteredAllocation = CurrentShardAllocations filterKeys { k =>
      !(ignoredNodes contains k.path.address)
    }

    strategy.passedCurrentShardAllocations shouldBe Some(filteredAllocation)
  }

  abstract class Scope extends AllocationStrategyScope {

    val ShardIds = for {
      i <- 1 to 50
    } yield s"Shard$i"

    val CurrentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]] = Map(
      mockedHostRef("Address1") -> ShardIds.slice(0, 10),
      mockedHostRef("Address2") -> ShardIds.slice(10, 20),
      mockedHostRef("Address3") -> ShardIds.slice(20, 30),
      mockedHostRef("Address4") -> ShardIds.slice(30, 40),
      mockedHostRef("Address5") -> ShardIds.slice(40, 50))

    val RebalanceInProgress: Set[ShardId] = Set(
      ShardIds(0),
      ShardIds(10),
      ShardIds(20),
      ShardIds(30),
      ShardIds(40))

    abstract class TestExtendedShardAllocationStrategy extends ExtendedShardAllocationStrategy {

      def result: Set[ShardId]
      var passedCurrentShardAllocations: Option[Map[ActorRef, immutable.IndexedSeq[ShardId]]] = None
      var passedRebalanceInProgress: Option[Set[ShardId]] = None

      override val maxSimultaneousRebalance = 10

      protected def doRebalance(
        currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
        rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {

        passedCurrentShardAllocations = Some(currentShardAllocations)
        passedRebalanceInProgress = Some(rebalanceInProgress)
        Future successful result
      }

      protected def doAllocate(
        requester: ActorRef,
        shardId: ShardId,
        currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {

        passedCurrentShardAllocations = Some(currentShardAllocations)
        Future successful requester
      }
    }
  }
}

