
name := """minimal-cqrs"""
organization := "net.imadz"
logLevel := Level.Warn
version := "1.0-SNAPSHOT"
semanticdbEnabled := true
semanticdbVersion := "4.9.7"

val akkaVersion = "2.6.20"
val AkkaManagementVersion = "1.1.4"
val AkkaPersistenceJdbcVersion = "5.1.0"
val AkkaProjectionVersion = "1.2.5"
val ScalikeJdbcVersion = "3.5.0"
//val AlpakkaKafkaVersion = "4.0.0"
val SlickVersion = "3.3.3"
val MongoPluginVersion = "3.0.8"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.14"

Compile / scalacOptions ++= Seq(
  "-target:11",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlog-reflective-calls",
  "-Xlint")
Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")

resolvers ++= Seq(
  "Aliyun" at "https://maven.aliyun.com/repository/public/",
  "Huawei" at "https://repo.huaweicloud.com/repository/maven/"
)

//resolvers += "softprops-maven" at "http://dl.bintray.com/content/softprops/maven"
enablePlugins(JavaAppPackaging, DockerPlugin)
dockerBaseImage := "docker.io/library/adoptopenjdk:11-jre-hotspot"
dockerUsername := sys.props.get("docker.username")
dockerRepository := sys.props.get("docker.registry")
ThisBuild / dynverSeparator := "-"

libraryDependencies += guice
libraryDependencies += jdbc
libraryDependencies += clusterSharding
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

  // Akka Management powers Health Checks and Akka Cluster Bootstrapping
  "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
  "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion,
  "com.typesafe.akka" %% "akka-discovery" % akkaVersion,

  // Common Dependencies
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.4.5",
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,

  // Akka Persistence
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.github.scullxbones" %% "akka-persistence-mongo-scala" % MongoPluginVersion,
  "com.github.scullxbones" %% "akka-persistence-mongo-rxmongo" % MongoPluginVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  //  "com.lightbend.akka" %% "akka-persistence-jdbc" % AkkaPersistenceJdbcVersion,
  //  "com.typesafe.slick" %% "slick" % SlickVersion,
  //  "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,

  // 4. Querying or projecting data from Akka Persistence
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
  "org.scalikejdbc" %% "scalikejdbc" % ScalikeJdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-config" % ScalikeJdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-play-initializer" % "2.8.0-scalikejdbc-3.5",

  // 5. Excel Loaders
  "org.apache.poi" % "poi" % "5.2.4",
  "org.apache.poi" % "poi-ooxml" % "5.2.4",

  "mysql" % "mysql-connector-java" % "5.1.34",
  "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test
)
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies += "nl.gn0s1s" %% "base64" % "0.2.2"
libraryDependencies += "com.github.jwt-scala" %% "jwt-play" % "9.1.2"
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "4.11.0"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-protobuf-v3" % akkaVersion,
  "org.mongodb.scala" %% "mongo-scala-driver" % "4.11.1",
  "com.google.protobuf" % "protobuf-java" % "3.15.6",
  "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
)

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value / "protobuf"
)

addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.9.7" cross CrossVersion.full)
scalacOptions += "-Yrangepos"

testOptions in Test += Tests.Argument("-oDF")
logBuffered in Test := false
