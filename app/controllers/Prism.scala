package controllers

import agent.{Accounts, CollectorAgent, LabelAgent}
import akka.actor.ActorSystem
import collectors._
import conf.PrismConfiguration

class Prism(prismConfiguration: PrismConfiguration)(actorSystem: ActorSystem) {
  val labelAgent = new LabelAgent(actorSystem)
  val accounts = new Accounts(prismConfiguration)

  val lazyStartup: Boolean = prismConfiguration.accounts.lazyStartup
  val instanceAgent = new CollectorAgent[Instance](new InstanceCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val lambdaAgent = new CollectorAgent[Lambda](new LambdaCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val dataAgent = new CollectorAgent[Data](new DataCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val securityGroupAgent = new CollectorAgent[SecurityGroup](new SecurityGroupCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val imageAgent = new CollectorAgent[Image](new ImageCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val launchConfigurationAgent = new CollectorAgent[LaunchConfiguration](new LaunchConfigurationCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val serverCertificateAgent = new CollectorAgent[ServerCertificate](new ServerCertificateCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val acmCertificateAgent = new CollectorAgent[AcmCertificate](new AmazonCertificateCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val route53ZoneAgent = new CollectorAgent[Route53Zone](new Route53ZoneCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val elbAgent = new CollectorAgent[LoadBalancer](new LoadBalancerCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val bucketAgent = new CollectorAgent[Bucket](new BucketCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val reservationAgent = new CollectorAgent[Reservation](new ReservationCollectorSet(accounts), labelAgent, lazyStartup)(actorSystem)
  val allAgents = Seq(instanceAgent, lambdaAgent, dataAgent, securityGroupAgent, imageAgent, launchConfigurationAgent,
    serverCertificateAgent, acmCertificateAgent, route53ZoneAgent, elbAgent, bucketAgent, reservationAgent)
}