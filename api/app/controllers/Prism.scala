package controllers

import agent.{Accounts, CollectorAgentTrait, IndexedItem, ObjectStoreCollectorAgent}
import akka.actor.ActorSystem
import collectors._
import conf.PrismConfiguration
import utils.{CollectorAgent, SourceStatusAgent, StopWatch}

// TODO: Maybe we should refactor this to be PrismAgents and to not be in the controllers package?
class Prism(prismConfiguration: PrismConfiguration, lambdaS3Agent: ObjectStoreCollectorAgent[Lambda])(actorSystem: ActorSystem) {
  val prismRunTimeStopWatch = new StopWatch()
  val sourceStatusAgent = new SourceStatusAgent(actorSystem, prismRunTimeStopWatch)
  val accounts = new Accounts(prismConfiguration)

  val lazyStartup: Boolean = prismConfiguration.accounts.lazyStartup

  // TODO: Maybe we should refactor this to not require a circular reference / pass in this
  val instanceAgent = new CollectorAgent[Instance](new InstanceCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val lambdaAgent = lambdaS3Agent // new CollectorAgent[Lambda](new LambdaCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val dataAgent = new CollectorAgent[Data](new DataCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)

  val securityGroupAgent = new CollectorAgent[SecurityGroup](new SecurityGroupCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)

  val imageAgent = new CollectorAgent[Image](new ImageCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val launchConfigurationAgent = new CollectorAgent[LaunchConfiguration](new LaunchConfigurationCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val serverCertificateAgent = new CollectorAgent[ServerCertificate](new ServerCertificateCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val acmCertificateAgent = new CollectorAgent[AcmCertificate](new AmazonCertificateCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val route53ZoneAgent = new CollectorAgent[Route53Zone](new Route53ZoneCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val elbAgent = new CollectorAgent[LoadBalancer](new LoadBalancerCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val bucketAgent = new CollectorAgent[Bucket](new BucketCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val reservationAgent = new CollectorAgent[Reservation](new ReservationCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val rdsAgent = new CollectorAgent[Rds](new RdsCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)
  val vpcAgent = new CollectorAgent[Vpc](new VpcCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)

  // We do not currently start this agent because it causes us to hit AWS rate limits.
  // Ultimately, this prevents new instances from coming into service reliably.
  // To re-enable this functionality, add cloudformationStackAgent to allAgents.
  val cloudformationStackAgent = new CollectorAgent[CloudformationStack](new CloudformationStackCollectorSet(accounts), sourceStatusAgent, lazyStartup)(actorSystem)

  val allInternalAgents: Seq[CollectorAgent[_ <: IndexedItem]] = Seq(instanceAgent, dataAgent, securityGroupAgent, imageAgent, launchConfigurationAgent,
    serverCertificateAgent, acmCertificateAgent, route53ZoneAgent, elbAgent, bucketAgent, reservationAgent, rdsAgent,
    vpcAgent)

  val allAgents: Seq[CollectorAgentTrait[_ <: IndexedItem]] = allInternalAgents ++ Seq(lambdaS3Agent)
}