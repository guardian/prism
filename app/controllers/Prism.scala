package controllers

import agent.CollectorAgent
import collectors._

object Prism {
  val lazyStartup = conf.PrismConfiguration.accounts.lazyStartup
  val instanceAgent = new CollectorAgent[Instance](InstanceCollectorSet.collectors, lazyStartup)
  val dataAgent = new CollectorAgent[Data](DataCollectorSet.collectors, lazyStartup)
  val securityGroupAgent = new CollectorAgent[SecurityGroup](SecurityGroupCollectorSet.collectors, lazyStartup)
  val imageAgent = new CollectorAgent[Image](ImageCollectorSet.collectors, lazyStartup)
  val launchConfigurationAgent = new CollectorAgent[LaunchConfiguration](LaunchConfigurationCollectorSet.collectors, lazyStartup)
  val serverCertificateAgent = new CollectorAgent[ServerCertificate](ServerCertificateCollectorSet.collectors, lazyStartup)
  val bucketAgent = new CollectorAgent[Bucket](BucketCollectorSet.collectors, lazyStartup)
  val allAgents = Seq(instanceAgent, dataAgent, securityGroupAgent, imageAgent, launchConfigurationAgent, serverCertificateAgent, bucketAgent)
}