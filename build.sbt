name := """minimal-cqrs"""

inThisBuild(List(
  organization := "net.imadz",
  version := "0.1-SNAPSHOT",
  organizationName := "MadPolaris",
  organizationHomepage := Some(url("https://github.com/MadPolaris")),
  homepage := Some(url("https://github.com/MadPolaris/playAkkaCQRS")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "MadPolaris",
      "MadPolaris Team",
      "zhongdj@gmail.com",
      url("https://github.com/MadPolaris")
    )
  ),
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
))

logLevel := Level.Warn

val akkaVersion = "2.6.20"
val AkkaManagementVersion = "1.1.4"
val AkkaPersistenceJdbcVersion = "5.1.0"
val AkkaProjectionVersion = "1.2.5"
val ScalikeJdbcVersion = "3.5.0"
val SlickVersion = "3.3.3"
val MongoPluginVersion = "3.0.8"

lazy val commonSettings = Seq(
  scalaVersion := "2.13.14",
  semanticdbEnabled := true,
  semanticdbVersion := "4.9.7",
  Compile / scalacOptions ++= Seq(
    "-target:11",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xlog-reflective-calls",
    "-Xlint"
  ),
  Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
  resolvers ++= Seq(
    "Aliyun" at "https://maven.aliyun.com/repository/public/",
    "Huawei" at "https://repo.huaweicloud.com/repository/maven/"
  ),
  addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.9.7" cross CrossVersion.full),
  scalacOptions += "-Yrangepos",
  testOptions in Test += Tests.Argument("-oDF"),
  logBuffered in Test := false,
  dependencyOverrides ++= Seq(
    "org.mongodb.scala" %% "mongo-scala-driver" % "4.3.4",
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
    "com.typesafe.play" %% "play-json" % "2.9.3",
    "com.google.guava" % "guava" % "30.1.1-jre",
    "com.typesafe.akka" %% "akka-http-core" % "10.2.7",
    "org.slf4j" % "slf4j-api" % "2.0.4"
  )
)

lazy val commonCore = (project in file("common-core"))
  .settings(
    commonSettings,
    name := "common-core",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-remote" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
      "org.scalikejdbc" %% "scalikejdbc" % ScalikeJdbcVersion,
      "org.scalikejdbc" %% "scalikejdbc-config" % ScalikeJdbcVersion,
      "com.zaxxer" % "HikariCP" % "3.4.5",
      "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.github.scullxbones" %% "akka-persistence-mongo-scala" % MongoPluginVersion
    )
  )

lazy val sagaCore = (project in file("saga-core"))
  .dependsOn(commonCore)
  .settings(
    commonSettings,
    name := "saga-core",
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value / "protobuf"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.5" % Test,
      "com.google.protobuf" % "protobuf-java" % "3.15.6",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.typesafe.play" %% "play-json" % "2.9.3",
      "javax.inject" % "javax.inject" % "1",
      "com.google.inject" % "guice" % "5.1.0"
    )
  )

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, JavaAppPackaging, DockerPlugin)
  .dependsOn(commonCore, sagaCore)
  .aggregate(commonCore, sagaCore)
  .settings(
    commonSettings,
    publish / skip := true,
    name := "minimal-cqrs",
    dockerBaseImage := "docker.io/library/adoptopenjdk:11-jre-hotspot",
    dockerUsername := sys.props.get("docker.username"),
    dockerRepository := sys.props.get("docker.registry"),
    ThisBuild / dynverSeparator := "-",
    
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value / "protobuf"
    ),

    libraryDependencies += guice,
    libraryDependencies += jdbc,
    libraryDependencies += clusterSharding,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

      "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion,
      "com.typesafe.akka" %% "akka-discovery" % akkaVersion,

      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.4.5",
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,

      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.github.scullxbones" %% "akka-persistence-mongo-scala" % MongoPluginVersion,
      "com.github.scullxbones" %% "akka-persistence-mongo-rxmongo" % MongoPluginVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,

      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
      "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
      "org.scalikejdbc" %% "scalikejdbc" % ScalikeJdbcVersion,
      "org.scalikejdbc" %% "scalikejdbc-config" % ScalikeJdbcVersion,
      "org.scalikejdbc" %% "scalikejdbc-play-initializer" % "2.8.0-scalikejdbc-3.5",

      "org.apache.poi" % "poi" % "5.2.4",
      "org.apache.poi" % "poi-ooxml" % "5.2.4",

      "mysql" % "mysql-connector-java" % "8.0.33",

      "net.java.dev.jna" % "jna" % "5.13.0",
      "net.java.dev.jna" % "jna-platform" % "5.13.0",
      "io.methvin" % "directory-watcher" % "0.17.1",
      "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test
    ),
    
    libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
    libraryDependencies += "nl.gn0s1s" %% "base64" % "0.2.2",
    libraryDependencies += "com.github.jwt-scala" %% "jwt-play" % "9.1.2",
    libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "4.11.0",
    
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-remote" % akkaVersion,
      "com.typesafe.akka" %% "akka-protobuf-v3" % akkaVersion,
      "com.google.protobuf" % "protobuf-java" % "3.15.6",
      "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
    )
  )
