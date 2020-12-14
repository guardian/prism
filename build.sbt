import com.gu.riffraff.artifact.BuildInfo

name := "prism"

version := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.13.3"

resolvers ++= Seq(
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  "Guardian Github Snapshots" at "https://guardian.github.io/maven/repo-releases"
)

val awsVersion = "2.15.31"
val awsVersionOne = "1.11.918"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, RiffRaffArtifact, JDebPackaging, SystemdPlugin, BuildInfoPlugin)
  .settings(
    packageName in Universal := normalizedName.value,
    fileDescriptorLimit := Some("16384"),
    maintainer := "Guardian Developers <dig.dev.software@theguardian.com>",
    topLevelDirectory in Universal := Some(normalizedName.value),
    riffRaffPackageType := (packageBin in Debian).value,
    riffRaffArtifactResources  := Seq(
      riffRaffPackageType.value -> s"${name.value}/${name.value}.deb",
      baseDirectory.value / "riff-raff.yaml" -> "riff-raff.yaml"
    ),
    buildInfoKeys := {
      lazy val buildInfo = BuildInfo(baseDirectory.value)
      Seq[BuildInfoKey](
        BuildInfoKey("buildNumber" -> buildInfo.buildIdentifier),
        // so this next one is constant to avoid it always recompiling on dev machines.
        // we only really care about build time on teamcity, when a constant based on when
        // it was loaded is just fine
        BuildInfoKey("buildTime" -> System.currentTimeMillis),
        BuildInfoKey("gitCommitId" -> buildInfo.revision)
      )
    },
    buildInfoPackage := "prism",
    libraryDependencies ++= Seq(
      "com.google.code.findbugs" % "jsr305" % "2.0.0",
      "software.amazon.awssdk" % "lambda" % awsVersion,
      "software.amazon.awssdk" % "s3" % awsVersion,
      "software.amazon.awssdk" % "auth" % awsVersion,
      "software.amazon.awssdk" % "sts" % awsVersion,
      "software.amazon.awssdk" % "acm" % awsVersion,
      "software.amazon.awssdk" % "ec2" % awsVersion,
      "software.amazon.awssdk" % "autoscaling" % awsVersion,
      "software.amazon.awssdk" % "elasticloadbalancing" % awsVersion,
      "software.amazon.awssdk" % "route53" % awsVersion,
      "software.amazon.awssdk" % "iam" % awsVersion,
      "com.beust" % "jcommander" % "1.75", // TODO: remove once security vulnerability introduced by aws sdk v2 fixed: https://snyk.io/vuln/maven:com.beust%3Ajcommanderbu
      "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersionOne,
      "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersionOne,
      "com.typesafe.play" %% "play-json" % "2.9.1",
      "com.typesafe.play" %% "play-json-joda" % "2.9.1",
      ws,
      "org.scala-stm" %% "scala-stm" % "0.9.1",
      filters,
      specs2 % "test",
      "net.logstash.logback" % "logstash-logback-encoder" % "6.4" exclude("com.fasterxml.jackson.core", "jackson-databind"),
      "com.gu" % "kinesis-logback-appender" % "2.0.1",
    ),
    scalacOptions ++= List(
      "-encoding", "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Xcheckinit"
    ),
    scalacOptions in Test ++= Seq("-Yrangepos")
  )

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-J-XX:MaxRAMFraction=2",
  "-J-XX:InitialRAMFraction=2",
  "-J-XX:MaxMetaspaceSize=300m",
  "-J-XX:+PrintGCDetails",
  "-J-XX:+PrintGCDateStamps",
  s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
)

