package controllers

import collectors.{Instance, InstanceCollector, CollectorAgent}

object Prism {
  val instanceAgent = new CollectorAgent[Instance](InstanceCollector.collectors)
  val allAgents = Seq(instanceAgent)
}