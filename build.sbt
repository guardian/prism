name := "prism"

version := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.13.3"

resolvers ++= Seq(
  "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  "Guardian Github Snapshots" at "https://guardian.github.io/maven/repo-releases"
)

val awsVersion = "1.11.759"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, RiffRaffArtifact, JDebPackaging, SystemdPlugin, UniversalPlugin)
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
      "com.typesafe.play" %% "play-json" % "2.9.1",
      "com.typesafe.play" %% "play-json-joda" % "2.9.1",
      ws,
      "org.scala-stm" %% "scala-stm" % "0.9.1",
      filters,
      specs2 % "test"
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
  "-Dconfig.file=/etc/gu/production.conf",
  s"-Dpidfile.path=/dev/null",
  "-J-XX:MaxRAMFraction=2",
  "-J-XX:InitialRAMFraction=2",
  "-J-XX:MaxMetaspaceSize=1g",
  "-J-XX:+PrintGCDetails",
  "-J-XX:+PrintGCDateStamps",
  s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
)

