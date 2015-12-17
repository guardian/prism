name := "prism"

version := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.7"

scalacOptions ++= Seq("-unchecked", "-optimise", "-deprecation",
  "-Xcheckinit", "-encoding", "utf8", "-feature", "-Yinline-warnings",
  "-Xfatal-warnings"
)

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers ++= Seq(
  "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
  "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"
)

libraryDependencies ++= Seq(
    "com.google.code.findbugs" % "jsr305" % "2.0.0",
    "com.amazonaws" % "aws-java-sdk" % "1.7.7",
    "com.gu" %% "management-play" % "8.0",
    "com.gu" %% "configuration" % "4.1",
    "com.typesafe.akka" %% "akka-agent" % "2.4.1",
    "com.typesafe.akka" %% "akka-slf4j" % "2.4.1",
    filters,
    specs2 % "test"
)

scalacOptions ++= Seq("-feature")

def env(key: String): Option[String] = Option(System.getenv(key))

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, RiffRaffArtifact, UniversalPlugin)
  .settings(
      packageName in Universal := normalizedName.value,
      topLevelDirectory in Universal := Some(normalizedName.value),
      riffRaffPackageType := (packageZipTarball in Universal).value,
      riffRaffBuildIdentifier := env("TRAVIS_BUILD_NUMBER").getOrElse("DEV"),
      riffRaffUploadArtifactBucket := Option("riffraff-artifact"),
      riffRaffUploadManifestBucket := Option("riffraff-builds")
  )

