//import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

name := "prism"

version := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.13.1"

//scalacOptions ++= Seq("-unchecked", "-deprecation",
//  "-Xcheckinit", "-encoding", "utf8", "-feature",
//  "-Yinline-warnings", "-Xfatal-warnings"
//)

//scalacOptions in Test ++= Seq("-Yrangepos")

resolvers ++= Seq(
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  "Guardian Github Snapshots" at "https://guardian.github.io/maven/repo-releases"
)

val awsVersion = "1.11.759"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, UniversalPlugin)
  .settings(
    name := """play-scala-compile-di-example""",
    packageName in Universal := normalizedName.value,
    maintainer := "Guardian Developers <dig.dev.software@theguardian.com>",
    topLevelDirectory in Universal := Some(normalizedName.value),
//    serverLoading in Debian := Systemd,
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.13.1",
    libraryDependencies ++= Seq(
      "com.google.code.findbugs" % "jsr305" % "2.0.0",
      "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-ec2" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-iam" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-sts" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-lambda" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-acm" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-route53" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-elasticloadbalancing" % awsVersion,
      ws,

//      "com.gu" %% "management-play" % "9.0",
//      "com.typesafe.akka" %% "akka-agent" % "2.4.1",
     // "com.typesafe.akka" %% "akka-slf4j" % "2.4.1",
      filters,
      specs2 % "test"
    ),
    scalacOptions ++= List(
      "-encoding", "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    )
  )


javaOptions in Universal ++= Seq(
  s"-Dpidfile.path=/dev/null",
  "-J-XX:MaxRAMFraction=2",
  "-J-XX:InitialRAMFraction=2",
  "-J-XX:MaxMetaspaceSize=300m",
  "-J-XX:+PrintGCDetails",
  "-J-XX:+PrintGCDateStamps",
  s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
)

//lazy val root = (project in file("."))
//  .enablePlugins(PlayScala, RiffRaffArtifact, UniversalPlugin)
//  .settings(
//    packageName in Universal := normalizedName.value,
//    maintainer := "Guardian Developers <dig.dev.software@theguardian.com>",
//    topLevelDirectory in Universal := Some(normalizedName.value),
//    serverLoading in Debian := Systemd,
////    riffRaffPackageType := (packageBin in Debian).value,
////    riffRaffUploadArtifactBucket := Option("riffraff-artifact"),
////    riffRaffUploadManifestBucket := Option("riffraff-builds"),
////    riffRaffArtifactResources  := Seq(
////      riffRaffPackageType.value -> s"${name.value}/${name.value}.deb",
////      baseDirectory.value / "riff-raff.yaml" -> "riff-raff.yaml"
////    )
//  )
//
