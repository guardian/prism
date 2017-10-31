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

  override def default = Owner("dig.dev.tooling")

  override def stacks: Set[(String, SSA)] = Set(
    "adam.fisher" -> SSA("security"),
    "dotcom.platform" -> SSA("frontend"),
    "dotcom.platform" -> SSA("frontend-elk"),
    "dotcom.platform" -> SSA(stack = "discussion"),
    "dotcom.platform" -> SSA(stack = "abacus"),
    "identitydev" -> SSA(stack = "identity"),
    "identitydev" -> SSA(stack = "identity-elk"),
    "dig.dev.tooling" -> SSA("deploy"),
    "dig.dev.tooling" -> SSA("domains"),
    "digitalcms.dev" -> SSA("flexible"),
    "digitalcms.dev" -> SSA("flexible-secondary"),
    "digitalcms.dev" -> SSA("workflow"),
    "digitalcms.dev" -> SSA("cms-fronts"),
    "digitalcms.dev" -> SSA("elk-new"),
    "thegrid.dev" -> SSA("media-service"),
    "commercial.dev" -> SSA(stack = "frontend", app = Some("ipad-ad-preview")),
    "commercial.dev" -> SSA(stack = "flexible", app = Some("campaign-central")),
    "content.platforms" -> SSA(stack = "content-api"),
    "content.platforms" -> SSA(stack = "content-api-crier-v2"),
    "content.platforms" -> SSA(stack = "content-api-crier-v2-preview"),
    "content.platforms" -> SSA(stack = "content-api-dashboard"),
    "content.platforms" -> SSA(stack = "content-api-facebook-news-bot"),
    "content.platforms" -> SSA(stack = "content-api-logging"),
    "content.platforms" -> SSA(stack = "content-api-okr-weekly"),
    "content.platforms" -> SSA(stack = "content-api-preview"),
    "content.platforms" -> SSA(stack = "content-api-recipeasy"),
    "content.platforms" -> SSA(stack = "content-api-sanity-tests"),
    "content.platforms" -> SSA(stack = "pubflow"),
    "content.platforms" -> SSA(stack = "pubflow-preview"),
    "mobile.server.side" -> SSA(stack = "mobile"),
    "mobile.server.side" -> SSA(stack = "mobile-notifications"),
    "mobile.server.side" -> SSA(stack = "mobile-preview"),
    "mobile.server.side" -> SSA(stack = "mobile-purchases"),
    "infosec.ops" -> SSA(stack = "infosec-elk"),
    "infosec.ops" -> SSA(stack = "infosec-elk-stack"),
    "data.technology" -> SSA(stack = "ophan"),
    "data.technology" -> SSA(stack = "ophan-data-lake"),
    "data.technology" -> SSA(stack = "tailor"),
    "membership-dev" -> SSA(stack = "membership"),
    "membership-dev" -> SSA(stack = "subscriptions")
  )
}
