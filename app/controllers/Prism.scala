package controllers

import agent.CollectorAgent
import collectors._

object Prism {
  val lazyStartup = conf.Configuration.accounts.lazyStartup
  val instanceAgent = new CollectorAgent[Instance](InstanceCollectorSet.collectors, lazyStartup)
  val dataAgent = new CollectorAgent[Data](DataCollectorSet.collectors, lazyStartup)
  val hardwareAgent = new CollectorAgent[Hardware](HardwareCollectorSet.collectors, lazyStartup)
  val securityGroupAgent = new CollectorAgent[SecurityGroup](SecurityGroupCollectorSet.collectors, lazyStartup)
  val ownerAgent = new CollectorAgent[Owner](OwnerCollectorSet.collectors, lazyStartup)
  val allAgents = Seq(instanceAgent, dataAgent, hardwareAgent, securityGroupAgent, ownerAgent)
}