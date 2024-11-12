// Comment to get more information during initialization
logLevel := Level.Warn

addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.5")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

libraryDependencies += "org.vafer" % "jdeb" % "1.11" artifacts Artifact(
  "jdeb",
  "jar",
  "jar"
)

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

/*
 * This is required for Scala Steward to run until SBT plugins all migrated to scala-xml 2.
 * See https://github.com/scala-steward-org/scala-steward/blob/13d63e8ae98a714efcdac2c7af18f004130512fa/project/plugins.sbt#L16-L19
 */
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
