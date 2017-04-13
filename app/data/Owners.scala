package data

import model._

trait Owners {
  def default: Owner
  def stacks: Set[(String, SSA)]

  def all: Set[Owner] = stacks
    .groupBy(_._1)
    .map { case (ownerId, stacks) =>
      Owner(ownerId, stacks.map(_._2))
    }.toSet

  def find(id: String): Option[Owner] = all.find(_.id == id)

  def find(ssa: SSA): Option[Owner] = all.find(_.hasSSA(ssa))

  def forStack(stackName: String, stageName: Option[String], appName: Option[String]): Owner = {
    all.find(_.hasSSA(SSA(stackName, stageName, appName)))
      .orElse(all.find(_.hasSSA(SSA(stackName, app = appName))))
      .orElse(all.find(_.hasSSA(SSA(stackName, stageName))))
      .orElse(all.find(_.hasSSA(SSA(stackName))))
      .getOrElse(default)
  }
}

object Owners extends Owners {

  override def default = Owner("phil.wills")

  override def stacks: Set[(String, SSA)] = Set(
    "dotcom.platform" -> SSA("frontend"),
    "simon.hildrew" -> SSA("deploy"),
    "adam.fisher" -> SSA("security"),
    "digitalcms.dev" -> SSA("flexible"),
    "digitalcms.dev" -> SSA("workflow"),
    "thegrid.dev" -> SSA("media-service")
    //TODO: complete list
  )

}


