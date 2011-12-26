import sbt._
import Keys._

object CFtpBuild extends Build {

  lazy val root = Project(id = "cftp",
    base = file("."),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.allDependencies))

  lazy val buildSettings = Seq(
    organization := "com.shsz.young",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.9.1")

  override lazy val settings = super.settings ++ buildSettings

  lazy val baseSettings = Defaults.defaultSettings
  lazy val defaultSettings = baseSettings ++ Seq(
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked"),
    javacOptions ++= Seq("-encoding", "utf-8"))
}

object Dependencies {
  import Dependency._
  val allDependencies = Seq(akkaActor, akkaCamel, akkaRemote, scalaTest, junit, commonsNet,
    log4j, slf4jApi, slf4jLog4j)
}

object Dependency {
  object V {
    val Akka = "1.2"
    val ScalaTest = "1.6.1"
    val JUnit = "4.8.1"
    val Slf4j = "1.6.4"
  }
  val akkaActor = "se.scalablesolutions.akka" % "akka-actor" % V.Akka
  val akkaCamel = "se.scalablesolutions.akka" % "akka-camel" % V.Akka
  val akkaRemote = "se.scalablesolutions.akka" % "akka-remote" % V.Akka
  val scalaTest = "org.scalatest" %% "scalatest" % V.ScalaTest % "test"
  val junit = "junit" % "junit" % V.JUnit % "test"

  val log4j = "log4j" % "log4j" % "1.2.15"
  val slf4jApi = "org.slf4j" % "slf4j-api" % V.Slf4j
  val slf4jLog4j = "org.slf4j" % "slf4j-log4j12" % V.Slf4j
  val commonsNet = "commons-net" % "commons-net" % "3.0.1"
}

