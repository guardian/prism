package controllers

import akka.actor.ActorSystem
import agent.Accounts
import agent.{CollectorAgent, GlobalCollectorAgent}
import collectors._

class PrismAgents(system: ActorSystem, prismConfig: conf.PrismConfiguration) {
  val lazyStartup = prismConfig.accounts.lazyStartup
  val accounts = new Accounts(prismConfig)
  val globalCollectorAgent = new GlobalCollectorAgent(system, new conf.Metrics(this))
  val instanceAgent = new CollectorAgent[Instance](system, globalCollectorAgent, new InstanceCollectorSet(accounts, this), lazyStartup)
  val dataAgent = new CollectorAgent[Data](system, globalCollectorAgent, new DataCollectorSet(accounts), lazyStartup)
  val securityGroupAgent = new CollectorAgent[SecurityGroup](system, globalCollectorAgent, new SecurityGroupCollectorSet(accounts, this), lazyStartup)
  val imageAgent = new CollectorAgent[Image](system, globalCollectorAgent, new ImageCollectorSet(accounts), lazyStartup)
  val launchConfigurationAgent = new CollectorAgent[LaunchConfiguration](system, globalCollectorAgent, new LaunchConfigurationCollectorSet(accounts), lazyStartup)
  val serverCertificateAgent = new CollectorAgent[ServerCertificate](system, globalCollectorAgent, new ServerCertificateCollectorSet(accounts), lazyStartup)
  val acmCertificateAgent = new CollectorAgent[AcmCertificate](system, globalCollectorAgent, new AmazonCertificateCollectorSet(accounts), lazyStartup)
  val route53ZoneAgent = new CollectorAgent[Route53Zone](system, globalCollectorAgent, new Route53ZoneCollectorSet(accounts), lazyStartup)
  val elbAgent = new CollectorAgent[LoadBalancer](system, globalCollectorAgent, new LoadBalancerCollectorSet(accounts), lazyStartup)
  val bucketAgent = new CollectorAgent[Bucket](system, globalCollectorAgent, new BucketCollectorSet(accounts), lazyStartup)
  val reservationAgent = new CollectorAgent[Reservation](system, globalCollectorAgent, new ReservationCollectorSet(accounts), lazyStartup)
  val allAgents = Seq(instanceAgent, dataAgent, securityGroupAgent, imageAgent, launchConfigurationAgent,
    serverCertificateAgent, acmCertificateAgent, route53ZoneAgent, elbAgent, bucketAgent, reservationAgent)
}