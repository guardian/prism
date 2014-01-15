package controllers

import collectors._

object Prism {
  val lazyStartup = conf.Configuration.accounts.lazyStartup
  val instanceAgent = new CollectorAgent[Instance](InstanceCollectorSet.collectors, lazyStartup)
  val hardwareAgent = new CollectorAgent[Hardware](HardwareCollectorSet.collectors, lazyStartup)
  val securityGroupAgent = new CollectorAgent[SecurityGroup](SecurityGroupCollectorSet.collectors, lazyStartup)
  val allAgents = Seq(instanceAgent, hardwareAgent, securityGroupAgent)
}