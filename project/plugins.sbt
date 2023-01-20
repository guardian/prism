// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository
resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.18")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.19")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

libraryDependencies += "org.vafer" % "jdeb" % "1.10" artifacts Artifact(
  "jdeb",
  "jar",
  "jar"
)

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")

/*
 * This is required for Scala Steward to run until SBT plugins all migrated to scala-xml 2.
 * See https://github.com/scala-steward-org/scala-steward/blob/13d63e8ae98a714efcdac2c7af18f004130512fa/project/plugins.sbt#L16-L19
 */
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
