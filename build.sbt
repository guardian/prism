name := "prism"

version := "1.0-SNAPSHOT"

resolvers += "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-releases"

libraryDependencies ++= Seq(
    "com.amazonaws" % "aws-java-sdk" % "1.6.0",
    "com.gu" %% "configuration" % "3.9",
    "com.typesafe.akka" %% "akka-agent" % "2.1.2"
)

scalacOptions ++= Seq("-feature")

play.Project.playScalaSettings
