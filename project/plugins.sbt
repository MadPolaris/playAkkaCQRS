addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.18")
addSbtPlugin("org.foundweekends.giter8" % "sbt-giter8-scaffold" % "0.13.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.8.1")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")

addSbtPlugin("ch.epfl.scala"             % "sbt-version-policy"        % "1.0.0-RC5")
addSbtPlugin("com.codecommit"            % "sbt-github-actions"        % "0.10.1")
addSbtPlugin("com.eed3si9n"              % "sbt-buildinfo"             % "0.10.0")
addSbtPlugin("com.geirsson"              % "sbt-ci-release"            % "1.5.7")
addSbtPlugin("com.github.cb372"          % "sbt-explicit-dependencies" % "0.2.16")
//addSbtPlugin("org.typelevel"             % "sbt-tpolecat"              % "0.5.1")
addSbtPlugin("net.vonbuchholtz"          % "sbt-dependency-check"      % "5.1.0")
addSbtPlugin("org.scalameta"             % "sbt-mdoc"                  % "2.2.18")
addSbtPlugin("org.scalameta"             % "sbt-scalafmt"              % "2.4.2")
addSbtPlugin("org.scalastyle"           %% "scalastyle-sbt-plugin"     % "1.0.0")
addSbtPlugin("org.wartremover"           % "sbt-wartremover"           % "3.1.6")
disablePlugins(sbt.plugins.JUnitXmlReportPlugin)
addSbtPlugin("nz.co.bottech" % "sbt-scala2plantuml" % "0.3.0")


libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.14"