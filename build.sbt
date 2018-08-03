
lazy val root = (project in file("."))
  .settings(
    name := "xmpp-akka-stream",
    organization := "com.github.rgafiyatullin",
    version := BuildEnv.version,

    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    scalacOptions ++= Seq("-language:implicitConversions"),
    scalacOptions ++= Seq("-Ywarn-value-discard", "-Xfatal-warnings"),

    scalaVersion := BuildEnv.scalaVersion,

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % {
        scalaVersion.value match {
          case v2_12 if v2_12.startsWith("2.12.") => "3.0.4"
          case v2_11 if v2_11.startsWith("2.11.") => "2.2.6"
        }
      },
      "com.github.rgafiyatullin"      %% "xml"              % "0.2.0.3",
      "com.github.rgafiyatullin"      %% "xmpp-protocol"    % "0.5.5.0",
      "com.github.rgafiyatullin"      %% "akka-stream-util" % "0.2.2.2",
      "com.typesafe.akka"             %% "akka-stream"      % "2.5.7",
      "com.fasterxml"                 %  "aalto-xml"        % "1.1.0"
    ),

    publishTo := BuildEnv.publishTo,
    credentials ++= BuildEnv.credentials.toSeq
  )

