package part3_clustering

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Address, Props, ReceiveTimeout}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import akka.util.Timeout

import scala.concurrent.duration._
import akka.pattern.pipe
import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Random

object ClusteringExampleDomain {
  case class ProcessFile(filename: String)
  case class ProcessLine(line: String, aggregator: ActorRef)
  case class ProcessLineResult(count: Int)
}

class ClusterWordCountPriorityMailbox(settings: ActorSystem.Settings, config: Config)
  extends UnboundedPriorityMailbox(
    PriorityGenerator {
      case _: MemberEvent => 0
      case _ => 4
    }
  )

class Master extends Actor with ActorLogging {
  import ClusteringExampleDomain._

  import context.dispatcher
  implicit val timeout = Timeout(3 seconds)

  val cluster = Cluster(context.system)

  var workers: Map[Address, ActorRef] = Map()
  var pendingRemoval: Map[Address, ActorRef] = Map()

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[UnreachableMember]
    )
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive =
    handleClusterEvents
      .orElse(handleWorkerRegistration)
      .orElse(handleJob)

  def handleClusterEvents: Receive = {
    case MemberUp(member) if member.hasRole("worker") =>
      log.info(s"Member is up: ${member.address}")
      if (pendingRemoval.contains(member.address)) {
        pendingRemoval = pendingRemoval - member.address
      } else {
        val workerSelection = context.actorSelection(s"${member.address}/user/worker")
        workerSelection.resolveOne().map(ref => (member.address, ref)).pipeTo(self)
      }

    case UnreachableMember(member) if member.hasRole("worker") =>
      log.info(s"Member detected as unreachable: ${member.address}")
      val workerOption = workers.get(member.address)
      workerOption.foreach { ref =>
        pendingRemoval = pendingRemoval + (member.address -> ref)
      }

    case MemberRemoved(member, previousStatus) =>
      log.info(s"Member ${member.address} removed after $previousStatus")
      workers = workers - member.address

    case m: MemberEvent =>
      log.info(s"Another member event I don't care about: $m")
  }

  def handleWorkerRegistration: Receive = {
    case pair: (Address, ActorRef) =>
      log.info(s"Registering worker: $pair")
      workers = workers + pair
  }

  def handleJob: Receive = {
    case ProcessFile(filename) =>
      val aggregator = context.actorOf(Props[Aggregator], "aggregator")
      scala.io.Source.fromFile(filename).getLines().foreach { line =>
        self ! ProcessLine(line, aggregator)
      }

    case ProcessLine(line, aggregator) =>
      val workerIndex = Random.nextInt((workers -- pendingRemoval.keys).size)
      val worker: ActorRef = (workers -- pendingRemoval.keys).values.toSeq(workerIndex)
      worker ! ProcessLine(line, aggregator)
      Thread.sleep(10)
  }
}


class Aggregator extends Actor with ActorLogging {
  import ClusteringExampleDomain._
  context.setReceiveTimeout(3 seconds)

  override def receive: Receive = online(0)

  def online(totalCount: Int): Receive = {
    case ProcessLineResult(count) =>
      context.become(online(totalCount + count))
    case ReceiveTimeout =>
      log.info(s"TOTAL COUNT: $totalCount")
      context.setReceiveTimeout(Duration.Undefined)
  }
}

class Worker extends Actor with ActorLogging {
  import ClusteringExampleDomain._

  override def receive: Receive = {
    case ProcessLine(line, aggregator) =>
      log.info(s"Processing: $line")
      aggregator ! ProcessLineResult(line.split(" ").length)
  }
}

object SeedNodes extends App {

  def createNode(port: Int, role: String, props: Props, actorName: String): ActorRef = {
    val config = ConfigFactory.parseString(
      s"""
         |akka.cluster.roles = ["$role"]
         |akka.remote.artery.canonical.port = $port
       """.stripMargin)
      .withFallback(ConfigFactory.load("part3_clustering/clusteringExample.conf"))

    val system = ActorSystem("RTJVMCluster", config)
    system.actorOf(props, actorName)
  }

  val master = createNode(2551, "master", Props[Master], "master")
  createNode(2552, "worker", Props[Worker], "worker")
  createNode(2553, "worker", Props[Worker], "worker")

  import ClusteringExampleDomain._
  Thread.sleep(10000)
  master ! ProcessFile("src/main/resources/txt/lipsum.txt")
}

object AdditionalWorker extends App {
  val config = ConfigFactory.parseString(
    s"""
       |akka.cluster.roles = ["worker"]
       |akka.remote.artery.canonical.port = 2554
       """.stripMargin)
    .withFallback(ConfigFactory.load("part3_clustering/clusteringExample.conf"))

  val system = ActorSystem("RTJVMCluster", config)
  system.actorOf(Props[Worker], "worker")
}