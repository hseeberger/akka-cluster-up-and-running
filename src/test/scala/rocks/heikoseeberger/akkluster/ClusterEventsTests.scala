/*
 * Copyright 2018 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rocks.heikoseeberger.akkluster

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.testkit.typed.FishingOutcome
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.Address
import akka.cluster.{ Member, MemberStatus }
import akka.cluster.typed.{ ClusterStateSubscription, Subscribe }
import akka.cluster.ClusterEvent.{
  MemberDowned,
  MemberEvent,
  MemberExited,
  MemberJoined,
  MemberLeft,
  MemberRemoved,
  MemberUp,
  MemberWeaklyUp,
  ReachabilityEvent,
  ReachableMember,
  UnreachableMember
}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Sink, Source }
import org.mockito.Mockito
import scala.concurrent.duration.DurationInt

final class ClusterEventsTests extends ActorTestKitSuite {
  import ClusterEvents._

  private val config = Config(42, 42.seconds)

  test("subscribe") {
    val subscriptions = TestProbe[ClusterStateSubscription]()
    ClusterEvents(config, subscriptions.ref).runWith(Sink.ignore)
    subscriptions.fishForMessage(3.seconds) {
      case Subscribe(_, c) if c == classOf[MemberEvent]       => FishingOutcome.Continue
      case Subscribe(_, c) if c == classOf[ReachabilityEvent] => FishingOutcome.Complete
      case other                                              => FishingOutcome.Fail(s"Unexpected message: $other")
    }
  }

  test("events") {
    val (memberEventsQueue, memberEvents) =
      Source.queue[MemberEvent](1, OverflowStrategy.fail).preMaterialize()
    val (reachabilityEventsQueue, reachabilityEvents) =
      Source.queue[ReachabilityEvent](1, OverflowStrategy.fail).preMaterialize()
    val subscriptions =
      testKit().spawn(Behaviors.receiveMessage[ClusterStateSubscription] {
        case Subscribe(s: ActorRef[MemberEvent], c) if c == classOf[MemberEvent] =>
          memberEvents.runForeach(s.!)
          Behaviors.same

        case Subscribe(s: ActorRef[ReachabilityEvent], c) if c == classOf[ReachabilityEvent] =>
          reachabilityEvents.runForeach(s.!)
          Behaviors.same

        case other =>
          throw new UnsupportedOperationException(s"Not handling command [$other]!")
      })
    val events = ClusterEvents(config, subscriptions).take(9).runWith(Sink.seq)

    val member = Mockito.mock(classOf[Member])
    Mockito.when(member.address).thenReturn(Address("akka", "test", "127.0.0.1", 25520))
    Mockito.when(member.status).thenReturn(MemberStatus.Joining)
    Mockito.when(member.roles).thenReturn(Set.empty)

    val expected =
      List(
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"joining","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"weakly-up","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"up","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"unreachable","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"reachable","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"leaving","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"exiting","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"removed","roles":[]}"""
        ),
        ServerSentEvent(
          """{"address":"akka://test@127.0.0.1:25520","status":"down","roles":[]}"""
        )
      )

    for {
      _ <- memberEventsQueue.offer(MemberJoined(member))
      _ = Mockito.when(member.status).thenReturn(MemberStatus.WeaklyUp)
      _ <- memberEventsQueue.offer(MemberWeaklyUp(member))
      _ = Mockito.when(member.status).thenReturn(MemberStatus.Up)
      _ <- memberEventsQueue.offer(MemberUp(member))
      _ <- reachabilityEventsQueue.offer(UnreachableMember(member))
      _ <- reachabilityEventsQueue.offer(ReachableMember(member))
      _ = Mockito.when(member.status).thenReturn(MemberStatus.Leaving)
      _ <- memberEventsQueue.offer(MemberLeft(member))
      _ = Mockito.when(member.status).thenReturn(MemberStatus.Exiting)
      _ <- memberEventsQueue.offer(MemberExited(member))
      _ = Mockito.when(member.status).thenReturn(MemberStatus.Removed)
      _ <- memberEventsQueue.offer(MemberRemoved(member, MemberStatus.Exiting))
      _ = Mockito.when(member.status).thenReturn(MemberStatus.Down)
      _  <- memberEventsQueue.offer(MemberDowned(member))
      es <- events
    } yield assertEquals(es, expected)
  }
}
