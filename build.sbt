name := "prism"

version := "1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

val awsVersion = "2.26.27"
val awsVersionOne = "1.12.767"

def env(propName: String): String =
  sys.env.get(propName).filter(_.trim.nonEmpty).getOrElse("DEV")

lazy val root = (project in file("."))
  .enablePlugins(
    PlayScala,
    JDebPackaging,
    SystemdPlugin,
    BuildInfoPlugin
  )
  .settings(
    Universal / packageName := normalizedName.value,
    fileDescriptorLimit := Some("16384"),
    maintainer := "Guardian Developers <dig.dev.software@theguardian.com>",
    Universal / topLevelDirectory := Some(normalizedName.value),
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,

      // These env vars are set by GitHub Actions
      // See https://docs.github.com/en/actions/learn-github-actions/variables#default-environment-variables
      "buildNumber" -> env("GITHUB_RUN_NUMBER"),
      "gitCommitId" -> env("GITHUB_SHA"),

      // so this next one is constant to avoid it always recompiling on dev machines.
      // we only really care about build time on teamcity, when a constant based on when
      // it was loaded is just fine
      "buildTime" -> System.currentTimeMillis
    ),
    buildInfoOptions += BuildInfoOption.BuildTime,
    buildInfoPackage := "prism",
    libraryDependencies ++= Seq(
      "com.google.code.findbugs" % "jsr305" % "3.0.2",
      "software.amazon.awssdk" % "lambda" % awsVersion,
      "software.amazon.awssdk" % "s3" % awsVersion,
      "software.amazon.awssdk" % "auth" % awsVersion,
      "software.amazon.awssdk" % "sts" % awsVersion,
      "software.amazon.awssdk" % "acm" % awsVersion,
      "software.amazon.awssdk" % "ec2" % awsVersion,
      "software.amazon.awssdk" % "autoscaling" % awsVersion,
      "software.amazon.awssdk" % "elasticloadbalancing" % awsVersion,
      "software.amazon.awssdk" % "route53" % awsVersion,
      "software.amazon.awssdk" % "cloudformation" % awsVersion,
      "com.beust" % "jcommander" % "1.82", // TODO: remove once security vulnerability introduced by aws sdk v2 fixed: https://snyk.io/vuln/maven:com.beust%3Ajcommanderbu
      "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersionOne,
      "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersionOne,
      "org.playframework" %% "play-json" % "3.0.4",
      "org.playframework" %% "play-json-joda" % "3.0.4",
      ws,
      "org.scala-stm" %% "scala-stm" % "0.11.1",
      filters,
      specs2 % "test",
      "net.logstash.logback" % "logstash-logback-encoder" % "8.0" exclude (
        "com.fasterxml.jackson.core",
        "jackson-databind"
      ),
      // Transient dependency of Play. No newer version of Play 3.0.5 with this vulnerability fixed.
      "ch.qos.logback" % "logback-classic" % "1.5.7",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.17.2"
    ),
    scalacOptions ++= List(
      "-encoding",
      "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Xcheckinit"
    ),
    Test / scalacOptions ++= Seq("-Yrangepos")
  )

Universal / javaOptions ++= Seq(
  "-Dpidfile.path=/dev/null",
  "-J-XX:MaxRAMPercentage=60",
  "-J-XX:InitialRAMPercentage=60",
  "-J-XX:MaxMetaspaceSize=300m",
  s"-J-Xlog:gc*:file=/var/log/${packageName.value}/gc.log:time:filecount=5,filesize=1024K"
)
