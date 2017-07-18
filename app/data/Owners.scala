package data

import model._

trait Owners {
  def default: Owner

  def all: Set[Owner]

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

  override def all: Set[Owner] = Set(
    Owner("journalism.dev", ssas = Set(SSA("content-api"), SSA("content-api-preview")), accounts = Set("capi")),
    Owner("dig.dev.tooling", ssas = Set(SSA("deploy")), accounts = Set("deploy-tools")),
    Owner("digitalcms.dev", ssas = Set(
      SSA("flexible"),
      SSA("workflow"),
      SSA("cms-fronts"),
      SSA("elk-new")
    ), accounts = Set("cmsFronts", "composer", "workflow", "media-service", "cmsSupport")),
    Owner("dotcom.platform", ssas = Set(SSA("frontend")), accounts = Set("frontend")),
    Owner("discussiondev", accounts = Set("discussion")),
    Owner("infosec", accounts = Set("infosec", "security")),
    Owner("membership.dev", accounts = Set("membership")),
    Owner("mobile.server.side", accounts = Set("mobile")),
    Owner("multimedia", accounts = Set("multimedia")),
    Owner("ophan", accounts = Set("ophan", "tailor"))
  )

}
