package data

import model._

trait Owners {
  def stacks: Set[(String, SSA)]

  def all: Set[Owner] = stacks
    .groupBy(_._1)
    .map { case (ownerId, stacks) =>
      Owner(ownerId, stacks.map(_._2))
    }.toSet

  def find(id: String): Option[Owner] = all.find(_.id == id)

  def find(ssa: SSA): Option[Owner] = all.find(_.hasSSA(ssa))

  def forStack(stackName: Option[String], stageName: Option[String], appName: Option[String]): Option[Owner] = {
    if(stackName.nonEmpty) {
      all.find(_.hasSSA(SSA(stackName, stageName, appName)))
        .orElse(all.find(_.hasSSA(SSA(stackName, app = appName))))
        .orElse(all.find(_.hasSSA(SSA(stackName, stageName))))
        .orElse(all.find(_.hasSSA(SSA(stackName))))
        .orElse(all.find(_.hasSSA(SSA())))
    } else None
  }
}

object Owners extends Owners {

  override def stacks: Set[(String, SSA)] = Set(
    "phil.wills" -> SSA(), // Default owner of all stacks
    "dotcom.platform" -> SSA(stack = Some("frontend")),
    "simon.hildrew" -> SSA(stack = Some("deploy")),
    "adam.fisher" -> SSA(stack = Some("security"))
    //TODO: complete list
  )

}


