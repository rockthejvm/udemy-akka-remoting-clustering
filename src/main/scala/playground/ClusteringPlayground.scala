package playground

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

/**
  * This simple application starts a 3-node Akka Cluster on ports 2551 to 2553.
  * When you run this app, you should see lots of logs of what Akka Cluster is doing: establish connections, accept members, set leader etc.
  * Running the application successfully means your libraries from build.sbt were correctly imported, and you should be good to go.
  *
  * Rock the JVM!
  * Daniel
  */
object ClusteringPlayground extends App {

  def startNode(port: Int) = {
    val config = ConfigFactory.parseString(
      s"""
         |akka {
         |  actor {
         |    provider = cluster
         |  }
         |
         |  remote {
         |    artery {
         |      enabled = on
         |      transport = aeron-udp
         |      canonical.hostname = "localhost"
         |      canonical.port = $port
         |    }
         |  }
         |
         |  cluster {
         |    seed-nodes = ["akka://RTJVMCluster@localhost:2551", "akka://RTJVMCluster@localhost:2552"]
         |  }
         |}
       """.stripMargin)

    ActorSystem("RTJVMCluster", config)
  }

  (2551 to 2553).foreach(startNode)
}
