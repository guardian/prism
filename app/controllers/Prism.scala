package controllers

import collectors.{Instance, InstanceCollector, CollectorAgent}

object Prism {
  val lazyStartup = conf.Configuration.accounts.lazyStartup
  val instanceAgent = new CollectorAgent[Instance](InstanceCollector.collectors, lazyStartup)
  val allAgents = Seq(instanceAgent)
}