package controllers

import agent.{Accounts, CollectorAgentTrait, IndexedItem, ObjectStoreAgent}
import akka.actor.ActorSystem
import collectors._
import conf.PrismConfiguration
import software.amazon.awssdk.services.s3.S3Client
import utils.{CollectorAgent, CollectorAgentWithObjectStorePersistance, SourceStatusAgent, StopWatch}
import jsonimplicits.model._

// TODO: Maybe we should refactor this to be PrismAgents and to not be in the controllers package?
class Prism(prismConfiguration: PrismConfiguration, s3Client: S3Client, stage: String)(actorSystem: ActorSystem) {
  val prismRunTimeStopWatch = new StopWatch()
  val sourceStatusAgent = new SourceStatusAgent(actorSystem, prismRunTimeStopWatch)
  val accounts = new Accounts(prismConfiguration)

  val lazyStartup: Boolean = prismConfiguration.accounts.lazyStartup

  private val instanceCollectorSet = new InstanceCollectorSet(accounts)
  val instanceAgent = new ObjectStoreAgent[Instance](instanceCollectorSet, s3Client, prismConfiguration.collectionStore.bucketName, stage)
  val instanceCollector = new CollectorAgentWithObjectStorePersistance[Instance](instanceCollectorSet, sourceStatusAgent, lazyStartup, s3Client, prismConfiguration.collectionStore.bucketName, stage)(actorSystem)

  private val lambdaCollectorSet = new LambdaCollectorSet(accounts)
  val lambdaAgent = new ObjectStoreAgent[Lambda](lambdaCollectorSet, s3Client, prismConfiguration.collectionStore.bucketName, stage)
  val lambdaCollector = new CollectorAgentWithObjectStorePersistance[Lambda](lambdaCollectorSet, sourceStatusAgent, lazyStartup, s3Client, prismConfiguration.collectionStore.bucketName, stage)(actorSystem)

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

  val allInternalAgents: Seq[CollectorAgent[_ <: IndexedItem]] = Seq(instanceCollector, dataAgent, securityGroupAgent, imageAgent, launchConfigurationAgent,
    serverCertificateAgent, acmCertificateAgent, route53ZoneAgent, elbAgent, bucketAgent, reservationAgent, rdsAgent,
    vpcAgent, lambdaCollector)

  val allAgents: Seq[CollectorAgentTrait[_ <: IndexedItem]] = allInternalAgents ++ Seq(lambdaAgent, instanceAgent)
}