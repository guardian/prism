import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

name := "prism"

version := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

scalacOptions ++= Seq("-unchecked", "-optimise", "-deprecation",
  "-Xcheckinit", "-encoding", "utf8", "-feature", "-Yinline-warnings",
  "-Xfatal-warnings"
)

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers ++= Seq(
  "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
  "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"
)

val awsVersion = "1.10.54"

libraryDependencies ++= Seq(
    "com.google.code.findbugs" % "jsr305" % "2.0.0",
    "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ec2" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-iam" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-sts" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,

    "com.gu" %% "management-play" % "8.0",
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
    maintainer := "Guardian Developers <dig.dev.software@theguardian.com>",
    topLevelDirectory in Universal := Some(normalizedName.value),
    serverLoading in Debian := Systemd,
    riffRaffPackageType := (packageBin in Debian).value,
    riffRaffBuildIdentifier := env("TRAVIS_BUILD_NUMBER").getOrElse("DEV"),
    riffRaffUploadArtifactBucket := Option("riffraff-artifact"),
    riffRaffUploadManifestBucket := Option("riffraff-builds"),
    riffRaffArtifactResources  := Seq(
      riffRaffPackageType.value -> s"${name.value}/${name.value}.deb",
      baseDirectory.value / "riff-raff.yaml" -> "riff-raff.yaml"
    )
  )

