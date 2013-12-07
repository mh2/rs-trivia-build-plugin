import sbt._
import Keys._

name := "sbt.plugin.trivia-build"

organization := "dk.reportsoft"

version := "0.1-SNAPSHOT"

sbtPlugin := true

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

libraryDependencies += "com.typesafe" % "config" % "1.0.2"
