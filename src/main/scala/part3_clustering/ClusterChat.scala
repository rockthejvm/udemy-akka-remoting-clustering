package part3_clustering

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberEvent, MemberRemoved, MemberUp}
import com.typesafe.config.ConfigFactory

object ChatDomain {
  case class ChatMessage(nickname: String, contents: String)
  case class UserMessage(contents: String)
  case class EnterRoom(fullAddress: String, nickname: String)
}

object ChatActor {
  def props(nickname: String, port: Int) = Props(new ChatActor(nickname, port))
}

class ChatActor(nickname: String, port: Int) extends Actor with ActorLogging {
  import ChatDomain._

  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent]
    )
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive = online(Map())

  def online(chatRoom: Map[String, String]): Receive = {
    case MemberUp(member) =>
      val remoteChatActorSelection = getChatActor(member.address.toString)
      remoteChatActorSelection ! EnterRoom(s"${self.path.address}@localhost:$port", nickname)

    case MemberRemoved(member, _) =>
      val remoteNickname = chatRoom(member.address.toString)
      log.info(s"$remoteNickname left the room.")
      context.become(online(chatRoom - member.address.toString))

    case EnterRoom(remoteAddress, remoteNickname) =>
      if (remoteNickname != nickname) {
        log.info(s"$remoteNickname entered the room.")
        context.become(online(chatRoom + (remoteAddress -> remoteNickname)))
      }

    case UserMessage(contents) =>
      chatRoom.keys.foreach { remoteAddressAsString =>
        getChatActor(remoteAddressAsString) ! ChatMessage(nickname, contents)
      }

    case ChatMessage(remoteNickname, contents) =>
      log.info(s"[$remoteNickname] $contents")
  }

  def getChatActor(memberAddress: String) =
    context.actorSelection(s"$memberAddress/user/chatActor")
}

class ChatApp(nickname: String, port: Int) extends App {
  import ChatDomain._

  val config = ConfigFactory.parseString(
    s"""
       |akka.remote.artery.canonical.port = $port
     """.stripMargin)
    .withFallback(ConfigFactory.load("part3_clustering/clusterChat.conf"))

  val system = ActorSystem("RTJVMCluster", config)
  val chatActor = system.actorOf(ChatActor.props(nickname, port), "chatActor")

  scala.io.Source.stdin.getLines().foreach { line =>
    chatActor ! UserMessage(line)
  }

}

object Alice extends ChatApp("Alice", 2551)
object Bob extends ChatApp("Bob", 2552)
object Charlie extends ChatApp("Charlie", 2553)

