package dk.reportsoft.trivia.sbt.plugin.builder

import sbt._
import sbt.Keys._
import com.typesafe.config.ConfigFactory
import java.util.regex.Pattern

object TriviaBuildPlugin extends Plugin {

  object TriviaBuildKeys {
    lazy val findDependencies = TaskKey[List[java.io.File]]("find-dependencies")
    lazy val readConfig = SettingKey[AuxClasses.TriviaBuildConfig]("read-trivia-build-config")
    lazy val triviaBuild = TaskKey[Unit]("build-trivia")
  }

  object AuxClasses {
    case class TriviaBuildConfig(
      windowsJDKPath: String,
      linuxJDKPath: String,
      playPath: String)
  }

  object AuxUtils {
    val versionExtractPattern = Pattern.compile("""([0-9]+(\-|\.)*)+\.jar$""", Pattern.CASE_INSENSITIVE)

    def extractVersion(file: File) = {
      val m = versionExtractPattern.matcher(file.getName)
      m.find() match {
        case true => Some(m.group())
        case false => None
      }
    }

    def split(file: File) = {
      extractVersion(file) match {
        case Some(version) => (file.getName.replace(version, ""), version)
        case None => (file.getName, "1.0.jar")
      }
    }

    def config = {
      val confFile = new File(".", "trivia-build.conf")
      val conf = ConfigFactory.parseFile(confFile)
      AuxClasses.TriviaBuildConfig(
        conf.getString("jdks.windows.path"),
        conf.getString("jdks.linux.path"),
        conf.getString("play-path"))
    }
    def getFolderName(str: String) = str.replaceAll(".*(\\\\|\\/)", "")

    def copyPlay(playPath: java.io.File, outputFolder: java.io.File) {
      val (folders, files) = playPath.listFiles.toList.partition(_.isDirectory())
      println("Folders: " + folders + "-------\nfiles: " + files)
      files.foreach(file => { val target = new File(outputFolder, file.getName); IO.copyFile(file, target, false) })
      folders.foreach(folder => {
        val out = new File(outputFolder, folder.getName)
        out.mkdir
        if (!folder.getName.toLowerCase.contains("repository")) {
          println("Copying: " + folder.getName.toLowerCase)
          IO.copyDirectory(folder, out, true, true)
        }
      })
    }

    def createWindowsScripts(outputFolder: java.io.File, windowsJDKPath: java.io.File, playPath: java.io.File) {
      val setEnvFile = new File(outputFolder, "windows_setenv.bat")
      setEnvFile.createNewFile()
      IO.writeLines(setEnvFile, List("set PATH=%~dp0/" + windowsJDKPath.getName + "/bin;%WinDir%;%WinDir%/System32"), IO.utf8, false)

      val startPlayFile = new File(outputFolder, "windows_startspore.bat")
      startPlayFile.createNewFile()
      IO.writeLines(startPlayFile, List("call " + setEnvFile.getName, "call " + playPath.getName() + "/play.bat \"start 80\""), IO.utf8, false)

      val shutdownPlayFile = new File(outputFolder, "windows_shutdown.bat")
      shutdownPlayFile.createNewFile()
      IO.writeLines(shutdownPlayFile, List("call " + setEnvFile.getName, "set /p pid=<RUNNING_PID", "taskkill.exe /PID %pid% /F /T", "del RUNNING_PID"), IO.utf8, false)

    }
    
    def createLinuxScripts(outputFolder: java.io.File, linuxJDKPath: java.io.File, playPath: java.io.File) {
      val hashBang = """#!/bin/bash"""
      val setJava = "PATH=$(dirname $0)/" + linuxJDKPath.getName + "/bin:$PATH"
      val setPlay = "PATH=$(dirname $0)/" + playPath.getName + ":$PATH"
      def withBangAndPath(bottomLines: String*) = {
        val res = hashBang :: setJava :: setPlay :: bottomLines.toList
        //println("---")
        //res foreach println
        //println
        res
      }
      
      val startPlayFile = new File(outputFolder, "start.sh")
      startPlayFile.createNewFile()
      IO.writeLines(
        startPlayFile,
        withBangAndPath("""   play "start 9000"   """.trim),
        IO.utf8,
        false
      )

      val shutdownPlayFile = new File(outputFolder, "shutdown.sh")
      shutdownPlayFile.createNewFile()
      IO.writeLines(
        shutdownPlayFile,
        withBangAndPath(
          """PID_FILE=$(dirname $0)/RUNNING_PID""",
          """PID=$(cat $PID_FILE)""",
          """kill -9 $(pstree -p $PID | grep -oP '(?<=\()[0-9]+(?=\))')""", //TODO consider being less brutal
          """rm -f $PID_FILE"""
        ),
        IO.utf8,
        false
      )
    }    
  }

  object AuxConstants {
    val outputFolder = new File(".", "spore-dist")
  }

  import TriviaBuildKeys._

  lazy val triviaBuildSettings = Defaults.defaultSettings ++ Seq(
    readConfig := AuxUtils.config,
    Keys.libraryDependencies += "com.typesafe" % "config" % "1.0.2",

    findDependencies <<= (update) map { updateReport =>
      {
        val versionsMap = scala.collection.mutable.Map.empty[String, Seq[(String, File)]]
        updateReport.allFiles.map(file => {
          val splitted = AuxUtils.split(file)
          versionsMap += splitted._1 -> (versionsMap.getOrElse(splitted._1, List.empty) :+ (splitted._2 -> file))
        })
        val results = versionsMap.map(pair => pair._1 -> pair._2.maxBy(_._1)).toList.map(_._2._2).sortBy(_.getName)
        results
      }
    },

    triviaBuild <<= (findDependencies map { deps =>
      {
        import AuxConstants._
        IO.delete(outputFolder)
        if (!outputFolder.mkdirs()) fail("Could not create Spore dist folder")

        val conf = AuxUtils.config
        // Copy JDKs
        val winJDKOutputFolder = new File(outputFolder, AuxUtils.getFolderName(conf.windowsJDKPath) + "_windows")
        val linuxJDKOutputFolder = new File(outputFolder, AuxUtils.getFolderName(conf.linuxJDKPath) + "_linux")
        List((conf.windowsJDKPath, winJDKOutputFolder), (conf.linuxJDKPath, linuxJDKOutputFolder)).foreach(folderPair => {
          val (source, target) = folderPair
          println("Copying JDK from: " + source + " to: " + target.getAbsolutePath())
          IO.copyDirectory(new File(source), target, true, true)
        })
        // Copy libs
        println("Copying lib")
        val libFolder = new File(outputFolder, "lib")
        libFolder.mkdir()
        deps.map(dep => dep -> new File(libFolder, dep.getName)).foreach(depPair => IO.copyFile(depPair._1, depPair._2, true))
        // Copy play
        println("Copying Play!")
        val playOutputFolder = new File(outputFolder, AuxUtils.getFolderName(conf.playPath))
        playOutputFolder.mkdir()
        AuxUtils.copyPlay(new File(conf.playPath), playOutputFolder)
        // Copy project
        val projectInputFolder = new File(".", "project")
        val projectOutputFolder = new File(outputFolder, "project")
        projectOutputFolder.mkdir()
        if (!(projectInputFolder.listFiles() == null)) {
          projectInputFolder.listFiles.filter(_.isFile).foreach(confFile => {
            val outFile = new File(projectOutputFolder, confFile.getName)
            val didCreate = outFile.createNewFile()
            println("Did create file: " + outFile.getAbsolutePath() + "?: " + didCreate)
            var confLines = scala.io.Source.fromFile(confFile).getLines.filter(line => !line.contains("trivia-build:delete")).toList;
            val includePattern = Pattern.compile("(\\/)* *trivia-build\\:include")
            confLines = confLines.map(line => { val m = includePattern.matcher(line); if (m.find()) line.replace(m.group(), "") else line })
            IO.writeLines(outFile, confLines, IO.utf8, true)
          })
        }

        // Copy conf
        val confOutputFolder = new File(outputFolder, "conf")
        IO.copyDirectory(new File(".", "conf"), confOutputFolder, true, true)
        // Copy other folders
        List("app", "public").foreach(sourceName => {
          val sourceFolder = new File(".", sourceName)
          val targetFolder = new File(outputFolder, sourceName)
          targetFolder.mkdir()
          println("Copying:" + sourceName)
          IO.copyDirectory(sourceFolder, targetFolder, true, true)
        })
        // Create scripts
        println("Creating Windows bat scripts")
        AuxUtils.createWindowsScripts(outputFolder, winJDKOutputFolder, playOutputFolder)
        println("Creating Linux shell scripts")
        AuxUtils.createLinuxScripts(outputFolder, linuxJDKOutputFolder, playOutputFolder)
      }
    }))

}
