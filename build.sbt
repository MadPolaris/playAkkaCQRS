name := """minimal-cqrs-saga-monarch"""

inThisBuild(List(
  organization := "net.imadz",
  organizationName := "MadPolaris",
  organizationHomepage := Some(url("https://github.com/MadPolaris")),
  homepage := Some(url("https://github.com/MadPolaris/playAkkaCQRS")),
  scmInfo := Some(
    ScmInfo(url("https://github.com/MadPolaris/playAkkaCQRS"), "git@github.com:MadPolaris/playAkkaCQRS.git")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "MadPolaris",
      "MadPolaris Team",
      "zhongdj@gmail.com",
      url("https://github.com/MadPolaris")
    )
  ),
  sonatypeCredentialHost := "central.sonatype.com"
))

logLevel := Level.Warn

// Acceptance gate runner (plan §9): G0.1 (saga-core) + G0.2 (app) today; G0.3 (codegen)
// joins after Phase C. E2E gates (G2 live-infra) are manual. Any FAIL breaks the build.
addCommandAlias("acceptance", "test")

// Docker release builds must start from a clean target/: sbt incremental compilation
// does not recompile unchanged sources when the build JDK changes, so stale classes
// compiled by a newer JDK (e.g. class-file 61) can survive into the image and then
// fail at runtime on the 11-jre base image (UnsupportedClassVersionError).
addCommandAlias("dockerRelease", ";clean;docker:publishLocal")


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
  Compile / javacOptions ++= Seq("--release", "11", "-Xlint:unchecked", "-Xlint:deprecation"),
  resolvers ++= Seq(
    "Aliyun" at "https://maven.aliyun.com/repository/public/"
  ),
  addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.9.7" cross CrossVersion.full),
  scalacOptions += "-Yrangepos",
  testOptions in Test += Tests.Argument("-oDF"),
  logBuffered in Test := false,
  dependencyOverrides ++= Seq(
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-distributed-data" % akkaVersion,
    "com.typesafe.akka" %% "akka-coordination" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-pki" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
    "org.mongodb.scala" %% "mongo-scala-driver" % "4.3.4",
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
    "com.typesafe.play" %% "play-json" % "2.9.3",
    "com.google.guava" % "guava" % "30.1.1-jre",
    "com.typesafe.akka" %% "akka-http-core" % "10.2.7",
    "org.slf4j" % "slf4j-api" % "2.0.4",
    "org.scalatest" %% "scalatest" % "3.2.15" % Test,
    "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
  )
)

lazy val dagEngineCore = (project in file("dag-engine-core"))
  .settings(
    commonSettings,
    publish / skip := true,
    name := "dag-engine-core",
    libraryDependencies ++= Seq(
      "net.imadz" %% "common-core" % "0.2",
      "net.imadz" %% "monarch-core" % "0.2",
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
      // protobuf 运行时：common-core 的 proto 事件类在恢复/序列化路径上会触及
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
    ),
    // protobuf 生成类在依赖层，默认分层类加载器会找不到 scalapb.GeneratedMessage
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )

lazy val fabSimulation = (project in file("fab-simulation"))
  .settings(
    commonSettings,
    publish / skip := true,
    name := "fab-simulation",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
    )
  )

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, JavaAppPackaging, DockerPlugin)
  .dependsOn(dagEngineCore, fabSimulation)
  .aggregate(dagEngineCore, fabSimulation)
  .settings(
    commonSettings,
    publish / skip := true,
    name := "minimal-cqrs-saga-monarch",
    dockerBaseImage := "eclipse-temurin:11-jre",
    dockerExposedPorts := Seq(9000),
    Docker / daemonUser := "daemon",
    dockerEntrypoint := Seq("sh", "-c", "mkdir -p /opt/docker/logs && exec bin/minimal-cqrs-saga-monarch"),
    dockerUpdateLatest := true,
    bashScriptExtraDefines += """addJava "-Dconfig.resource=docker.conf"""",
    Universal / mappings := (Universal / mappings).value.filterNot {
      case (_, name) => name.contains("logback-test.xml")
    },
    dockerUsername := sys.props.get("docker.username"),
    dockerRepository := sys.props.get("docker.registry"),
    ThisBuild / dynverSeparator := "-",
    
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value / "protobuf"
    ),

    libraryDependencies += guice,
    libraryDependencies += jdbc,
    libraryDependencies += clusterSharding,
    // Engine libs extracted to MadPolaris org, published on Maven Central (0.2)
    libraryDependencies ++= Seq(
      "net.imadz" %% "common-core" % "0.2",
      "net.imadz" %% "monarch-core" % "0.2",
      "net.imadz" %% "saga-core" % "0.2",
      "net.imadz" %% "auth-core" % "0.2"
    ),
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
	libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0",
    
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-remote" % akkaVersion,
      "com.typesafe.akka" %% "akka-protobuf-v3" % akkaVersion,
      "com.google.protobuf" % "protobuf-java" % "3.15.6",
      "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
    )
  )
