import sbt._

object DeployPlugins extends Build {
  val playArtifactPluginVersion = "play2.2.0_3"

  lazy val plugins = Project("plugins", file("."))
    .dependsOn(uri("git://github.com/guardian/sbt-play-artifact.git#" + playArtifactPluginVersion)
  )
}