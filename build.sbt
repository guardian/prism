import com.gu.riffraff.artifact.BuildInfo

name := "prism"

version := "1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

val awsVersion = "2.23.12"
val awsVersionOne = "1.12.656"

lazy val root = (project in file("."))
  .enablePlugins(
    PlayScala,
    RiffRaffArtifact,
    JDebPackaging,
    SystemdPlugin,
    BuildInfoPlugin
  )
  .settings(
    Universal / packageName := normalizedName.value,
    fileDescriptorLimit := Some("16384"),
    maintainer := "Guardian Developers <dig.dev.software@theguardian.com>",
    Universal / topLevelDirectory := Some(normalizedName.value),
    riffRaffPackageName := s"devx::${name.value}",
    riffRaffManifestProjectName := riffRaffPackageName.value,
    riffRaffPackageType := (Debian / packageBin).value,
    riffRaffArtifactResources := Seq(
      riffRaffPackageType.value -> s"${name.value}/${name.value}.deb",
      baseDirectory.value / "riff-raff.yaml" -> "riff-raff.yaml",
      baseDirectory.value / "cdk/cdk.out/Prism-CODE.template.json" -> "cloudformation/Prism-CODE.template.json",
      baseDirectory.value / "cdk/cdk.out/Prism-PROD.template.json" -> "cloudformation/Prism-PROD.template.json"
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
      "software.amazon.awssdk" % "iam" % awsVersion,
      "software.amazon.awssdk" % "rds" % awsVersion,
      "software.amazon.awssdk" % "cloudformation" % awsVersion,
      "com.beust" % "jcommander" % "1.82", // TODO: remove once security vulnerability introduced by aws sdk v2 fixed: https://snyk.io/vuln/maven:com.beust%3Ajcommanderbu
      "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersionOne,
      "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersionOne,
      "org.playframework" %% "play-json" % "3.0.2",
      "org.playframework" %% "play-json-joda" % "3.0.2",
      "ai.x" %% "play-json-extensions" % "0.42.0",
      ws,
      "org.scala-stm" %% "scala-stm" % "0.11.1",
      filters,
      specs2 % "test",
      "net.logstash.logback" % "logstash-logback-encoder" % "7.4" exclude ("com.fasterxml.jackson.core", "jackson-databind"),
      // Transient dependency of Play. No newer version of Play 3.0.1 with this vulnerability fixed.
      "ch.qos.logback" % "logback-classic" % "1.5.0",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.16.1"
    ),
    excludeDependencies ++= Seq(
      // As of Play 3.0, groupId has changed to org.playframework; exclude transitive dependencies to the old artifacts
      ExclusionRule(organization = "com.typesafe.play")
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
