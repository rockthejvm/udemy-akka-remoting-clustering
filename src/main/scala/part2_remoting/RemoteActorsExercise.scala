package part2_remoting

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, Identify, PoisonPill, Props}
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

object WordCountDomain {
  case class Initialize(nWorkers: Int)
  case class WordCountTask(text: String)
  case class WordCountResult(count: Int)
  case object EndWordCount
}

class WordCountWorker extends Actor with ActorLogging {
  import WordCountDomain._

  override def receive: Receive = {
    case WordCountTask(text) =>
      log.info(s"I'm processing: $text")
      sender() ! WordCountResult(text.split(" ").length)
  }
}

class WordCountMaster extends Actor with ActorLogging {
  import WordCountDomain._

  val workerRouter = context.actorOf(FromConfig.props(Props[WordCountWorker]), "workerRouter")

  override def receive: Receive = onlineWithRouter(0, 0)

  def onlineWithRouter(remainingTasks: Int, totalCount: Int): Receive = {
    case text: String =>
      // split it into sentences
      val sentences = text.split("\\. ")
      // send sentences to workers in turn
      sentences.foreach(sentence => workerRouter ! WordCountTask(sentence))
      context.become(onlineWithRouter(remainingTasks + sentences.length, totalCount))

    case WordCountResult(count) =>
      if (remainingTasks == 1) {
        log.info(s"TOTAL RESULT: ${totalCount + count}")
        context.stop(self)
      } else {
        context.become(onlineWithRouter(remainingTasks - 1, totalCount + count))
      }
  }


  def waitingToInitializeWorkers(nWorkers: Int): Receive = {
    case Initialize(nWorkers) =>
      val workers = (1 to nWorkers).map(id => context.actorOf(Props[WordCountWorker], s"wordCountWorker$id"))
      context.become(online(workers.toList, 0, 0))
  }

  def identifyWorkers(nWorkers: Int) = {
    log.info("Master initializing...")
    val workerSelections = (1 to nWorkers).map(id => context.actorSelection(s"akka://WorkersSystem@localhost:2552/user/wordCountWorker$id"))
    workerSelections.foreach(_ ! Identify("rtjvm"))
    context.become(initializing(List(), nWorkers))
  }

  def initializing(workers: List[ActorRef], remainingWorkers: Int): Receive = {
    case ActorIdentity("rtjvm", Some(workerRef)) =>
      log.info(s"Worker identified: $workerRef")
      if (remainingWorkers == 1) context.become(online(workerRef :: workers, 0, 0))
      else context.become(initializing(workerRef :: workers, remainingWorkers - 1))
  }

  def online(workers: List[ActorRef], remainingTasks: Int, totalCount: Int): Receive = {
    case text: String =>
      // split it into sentences
      val sentences = text.split("\\. ")
      // send sentences to workers in turn
      Iterator.continually(workers).flatten.zip(sentences.iterator).foreach { pair =>
        val (worker, sentence) = pair
        worker ! WordCountTask(sentence)
      }
      context.become(online(workers, remainingTasks + sentences.length, totalCount))

    case WordCountResult(count) =>
      if (remainingTasks == 1) {
        log.info(s"TOTAL RESULT: ${totalCount + count}")
        workers.foreach(_ ! PoisonPill)
        context.stop(self)
      } else {
        context.become(online(workers, remainingTasks - 1, totalCount + count))
      }
  }
}

object MasterApp extends App {
  import WordCountDomain._

  val config = ConfigFactory.parseString(
    """
      |akka.remote.artery.canonical.port = 2551
    """.stripMargin)
    .withFallback(ConfigFactory.load("part2_remoting/remoteActorsExercise.conf"))

  val system = ActorSystem("MasterSystem", config)

  val master = system.actorOf(Props[WordCountMaster], "wordCountMaster")
  master ! Initialize(5)
  Thread.sleep(1000)

  scala.io.Source.fromFile("src/main/resources/txt/lipsum.txt").getLines().foreach { line =>
    master ! line
  }
}

object WorkersApp extends App {
  val config = ConfigFactory.parseString(
    """
      |akka.remote.artery.canonical.port = 2552
    """.stripMargin)
    .withFallback(ConfigFactory.load("part2_remoting/remoteActorsExercise.conf"))

  val system = ActorSystem("WorkersSystem", config)
}