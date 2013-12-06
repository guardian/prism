package controllers

import collectors.{Instance, InstanceCollector, CollectorAgent}

object Prism {
  def instanceAgent = new CollectorAgent[Instance](InstanceCollector.collectors)
  def allAgents = Seq(instanceAgent)
}