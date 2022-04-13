// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository
resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.18")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.15")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")

libraryDependencies += "org.vafer" % "jdeb" % "1.7" artifacts Artifact("jdeb", "jar", "jar")
