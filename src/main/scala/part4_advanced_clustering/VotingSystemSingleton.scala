package part4_advanced_clustering

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.typesafe.config.ConfigFactory

import scala.util.Random
import scala.concurrent.duration._

case class Person(id: String, age: Int)
object Person {
  def generate() = Person(UUID.randomUUID().toString, 16 + Random.nextInt(90))
}

case class Vote(person: Person, candidate: String)
case object VoteAccepted
case class VoteRejected(reason: String)

class VotingAggregator extends Actor with ActorLogging {
  val CANDIDATES: Set[String] = Set("Martin", "Roland", "Jonas", "Daniel")

  context.setReceiveTimeout(20 seconds)

  override def receive: Receive = online(Set(), Map())

  def online(personsVoted: Set[String], polls: Map[String, Int]): Receive = {
    case Vote(Person(id, age), candidate) =>
      if (personsVoted.contains(id)) sender() ! VoteRejected("already voted")
      else if (age < 18) sender() ! VoteRejected("not above legal voting age")
      else if (!CANDIDATES.contains(candidate)) sender() ! VoteRejected("invalid candidate")
      else {
        log.info(s"Recording vote from person $id for $candidate")
        val candidateVotes = polls.getOrElse(candidate, 0)
        sender() ! VoteAccepted
        context.become(online(personsVoted + id, polls + (candidate -> (candidateVotes + 1))))
      }
    case ReceiveTimeout =>
      log.info(s"TIME'S UP, here are the poll results: $polls")
      context.setReceiveTimeout(Duration.Undefined)
      context.become(offline)
  }

  def offline: Receive = {
    case v: Vote =>
      log.warning(s"Received $v, which is invalid as the time is up")
      sender() ! VoteRejected("cannot accept votes after the polls closing time")
    case m =>
      log.warning(s"Received $m - will not process more messages after polls closing time")
  }
}

object VotingStation {
  def props(votingAggregator: ActorRef) = Props(new VotingStation(votingAggregator))
}

class VotingStation(votingAggregator: ActorRef) extends Actor with ActorLogging {
  override def receive: Receive = {
    case v: Vote => votingAggregator ! v
    case VoteAccepted => log.info("Vote was accepted")
    case VoteRejected(reason) => log.warning(s"Vote was rejected: $reason")
  }
}

object CentralElectionSystem extends App {
  def startNode(port: Int) = {
    val config = ConfigFactory.parseString(
      s"""
         |akka.remote.artery.canonical.port = $port
       """.stripMargin)
      .withFallback(ConfigFactory.load("part4_advanced_clustering/votingSystemSingleton.conf"))

    val system = ActorSystem("RTJVMCluster", config)

    system.actorOf(
      ClusterSingletonManager.props(
        Props[VotingAggregator],
        PoisonPill,
        ClusterSingletonManagerSettings(system)
      ),
      "centralVotingAggregator"
    )
  }

  (2551 to 2553).foreach(startNode)
}

class VotingStationApp(port: Int) extends App {
  val config = ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = $port
       """.stripMargin)
    .withFallback(ConfigFactory.load("part4_advanced_clustering/votingSystemSingleton.conf"))

  val system = ActorSystem("RTJVMCluster", config)

  val centralVotingAggregatorProxy = system.actorOf(
    ClusterSingletonProxy.props(
      "/user/centralVotingAggregator",
      ClusterSingletonProxySettings(system)
    )
  )

  val votingStation = system.actorOf(VotingStation.props(centralVotingAggregatorProxy))
  scala.io.Source.stdin.getLines().foreach { line =>
    votingStation ! Vote(Person.generate(), line)
  }
}

object Washington extends VotingStationApp(2561)
object NewYork extends VotingStationApp(2562)
object SanFrancisco extends VotingStationApp(2563)

