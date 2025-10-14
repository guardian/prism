package data

import model._

trait Owners {
  def default: Owner
  def stacks: Set[(String, SSA)]

  def all: Set[Owner] = stacks
    .groupBy(_._1)
    .map { case (ownerId, stacks) =>
      Owner(ownerId, stacks.map(_._2))
    }
    .toSet

  def find(id: String): Option[Owner] = all.find(_.id == id)

  def find(ssa: SSA): Option[Owner] = all.find(_.hasSSA(ssa))

  def forStack(
      stackName: String,
      stageName: Option[String],
      appName: Option[String]
  ): Owner = {
    all
      .find(_.hasSSA(SSA(stackName, stageName, appName)))
      .orElse(all.find(_.hasSSA(SSA(stackName, app = appName))))
      .orElse(all.find(_.hasSSA(SSA(stackName, stageName))))
      .orElse(all.find(_.hasSSA(SSA(stackName))))
      .getOrElse(default)
  }
}

object Owners extends Owners {

  override def default: Owner = Owner("devx.sec.ops+unowned")

  override def stacks: Set[(String, SSA)] = Set(
    "devx.sec.ops" -> SSA("security"),
    "devx.sec.ops" -> SSA("deploy"),
    "devx.sec.ops" -> SSA("amigo-packer"),
    "devx.sec.ops" -> SSA("teamcity"),
    "dotcom.platform" -> SSA(stack = "abacus"),
    "dotcom.platform" -> SSA("domains"),
    "dotcom.platform" -> SSA("frontend"),
    "dotcom.platform" -> SSA("frontend-elk"),
    "identitydev" -> SSA(stack = "discussion"),
    "identitydev" -> SSA(stack = "identity"),
    "newsroom.resilience" -> SSA("print-production"),
    "digitalcms.dev" -> SSA("crosswords"),
    "digitalcms.dev" -> SSA("flexible"),
    "digitalcms.dev" -> SSA("flexible-secondary"),
    "digitalcms.dev" -> SSA("workflow"),
    "digitalcms.dev" -> SSA("cms-fronts"),
    "digitalcms.dev" -> SSA("fronts"),
    "digitalcms.dev" -> SSA("elk-new"),
    "digitalcms.dev" -> SSA("media-service"),
    "digitalcms.dev" -> SSA("grid-elasticsearch"),
    "digitalcms.dev" -> SSA("composer"),
    "digitalcms.dev" -> SSA("editorial-feeds"),
    "digital.investigations" -> SSA("pfi"),
    "digital.investigations" -> SSA("pfi-giant"),
    "digital.investigations" -> SSA("pfi-playground"),
    "digital.investigations" -> SSA("pfi-structured"),
    "digital.investigations" -> SSA("pfi-lurch"),
    "digital.investigations" -> SSA("investigations"),
    "secure.collaboration" -> SSA(stack = "secure-collaboration"),
    "commercial.dev" -> SSA(stack = "frontend", app = Some("ipad-ad-preview")),
    "commercial.dev" -> SSA(stack = "flexible", app = Some("campaign-central")),
    "consent" -> SSA(stack = "baton"),
    "consent" -> SSA(stack = "cmp-monitoring"),
    "content.platforms" -> SSA(stack = "content-api"),
    "content.platforms" -> SSA(stack = "content-api-crier-v2"),
    "content.platforms" -> SSA(stack = "content-api-crier-v2-preview"),
    "content.platforms" -> SSA(stack = "content-api-dashboard"),
    "content.platforms" -> SSA(stack = "content-api-facebook-news-bot"),
    "content.platforms" -> SSA(stack = "content-api-facebook-newstab"),
    "content.platforms" -> SSA(stack = "content-api-fastly-cache-purger"),
    "content.platforms" -> SSA(stack = "content-api-floodgate"),
    "content.platforms" -> SSA(stack = "content-api-logging"),
    "content.platforms" -> SSA(stack = "content-api-okr-weekly"),
    "content.platforms" -> SSA(stack = "content-api-podcasts-analytics"),
    "content.platforms" -> SSA(stack = "content-api-preview"),
    "content.platforms" -> SSA(stack = "content-api-recipeasy"),
    "content.platforms" -> SSA(stack = "content-api-sanity-tests"),
    "content.platforms" -> SSA(stack = "pubflow"),
    "content.platforms" -> SSA(stack = "pubflow-preview"),
    "mobile.server.side" -> SSA(stack = "content-api-mobile-notifications"),
    "mobile.server.side" -> SSA(stack = "mobile"),
    "mobile.server.side" -> SSA(stack = "mobile-notifications"),
    "mobile.server.side" -> SSA(stack = "mobile-notifications-workers"),
    "mobile.server.side" -> SSA(stack = "mobile-preview"),
    "mobile.server.side" -> SSA(stack = "mobile-purchases"),
    "infosec.ops" -> SSA(stack = "infosec"),
    "infosec.ops" -> SSA(stack = "infosec-nessus"),
    "infosec.ops" -> SSA(stack = "secure-contact"),
    "infosec.ops" -> SSA(stack = "infosec-hive"),
    "infosec.ops" -> SSA(stack = "wazuh"),
    "ophan.dev" -> SSA(stack = "ophan"),
    "data.technology" -> SSA(stack = "ophan-data-lake"),
    "reader.revenue.dev" -> SSA(stack = "membership"),
    "reader.revenue.dev" -> SSA(stack = "subscriptions"),
    "reader.revenue.dev" -> SSA(stack = "support"),
    "newsletters.dev" -> SSA(stack = "newsletters"),
    "multimediatech" -> SSA(stack = "multimedia"),
    "value.dev" -> SSA(stack = "targeting"),
    "engineering.managers" -> SSA(stack = "hiring-and-onboarding"),
    "ai.dev" -> SSA(stack = "ai"),
    "data.scientists" -> SSA(stack = "data-science"),
    "feast.devs" -> SSA(stack = "feast")
  )
}
