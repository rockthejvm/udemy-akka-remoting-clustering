package part2_remoting

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, AddressFromURIString, Deploy, PoisonPill, Props, Terminated}
import akka.remote.RemoteScope
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

object DeployingActorsRemotely_LocalApp extends App {
  val system = ActorSystem("LocalActorSystem", ConfigFactory.load("part2_remoting/deployingActorsRemotely.conf").getConfig("localApp"))

  val simpleActor = system.actorOf(Props[SimpleActor], "remoteActor") // /user/remoteActor
  simpleActor ! "hello, remote actor!"

  // actor path of a remotely deployed actor
  println(simpleActor)
  // expected: akka://RemoteActorSystem@localhost:2552/user/remoteActor
  // actual: akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/remoteActor

  // programmatic remote deployment
  val remoteSystemAddress: Address = AddressFromURIString("akka://RemoteActorSystem@localhost:2552")
  val remotelyDeployedActor = system.actorOf(
    Props[SimpleActor].withDeploy(
      Deploy(scope = RemoteScope(remoteSystemAddress))
    )
  )

  remotelyDeployedActor ! "hi, remotely deployed actor!"

  // routers with routees deployed remotely
  val poolRouter = system.actorOf(FromConfig.props(Props[SimpleActor]), "myRouterWithRemoteChildren")
  (1 to 10).map(i => s"message $i").foreach(poolRouter ! _)

  // watching remote actors
  class ParentActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case "create" =>
        log.info("Creating remote child")
        val child = context.actorOf(Props[SimpleActor], "remoteChild")
        context.watch(child)
      case Terminated(ref) =>
        log.warning(s"Child $ref terminated")
    }
  }

  val parentActor = system.actorOf(Props[ParentActor], "watcher")
  parentActor ! "create"
//  Thread.sleep(1000)
//  system.actorSelection("akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/watcher/remoteChild") ! PoisonPill


}

object DeployingActorsRemotely_RemoteApp extends App {
  val system = ActorSystem("RemoteActorSystem", ConfigFactory.load("part2_remoting/deployingActorsRemotely.conf").getConfig("remoteApp"))
}