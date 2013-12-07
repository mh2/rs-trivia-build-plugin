package dk.reportsoft.trivia.sbt.plugin.builder

import sbt._
import sbt.Keys._
import com.typesafe.config.ConfigFactory

object TriviaBuildPlugin extends Plugin {

  object TriviaBuildKeys {
    lazy val findDependencies = TaskKey[List[java.io.File]]("find-dependencies")
    lazy val readConfig = SettingKey[AuxClasses.TriviaBuildConfig]("read-trivia-build-config")
    lazy val triviaBuild = TaskKey[Unit]("build-trivia")
  }
  
  object AuxClasses {
    case class TriviaBuildConfig(
       windowsJDKPath : String,
       linuxJDKPath : String,
       playPath : String
    )  }
  
  object AuxUtils {
    def config = {
      val confFile = new File(".","trivia-build.conf")
      val conf = ConfigFactory.parseFile(confFile)
      AuxClasses.TriviaBuildConfig(
          conf.getString("jdks.windows.path"), 
          conf.getString("jdks.linux.path"),
          conf.getString("play-path"))
    }
    def getFolderName(str : String) = str.replaceAll(".*(\\\\|\\/)", "")
    
    def copyPlay(playPath : java.io.File, outputFolder : java.io.File) {
      playPath.listFiles.foreach(folder => {
        val out = new File(outputFolder, folder.getName)
        out.mkdir
        if(!folder.getName.toLowerCase.contains("repository")) {
          IO.copyDirectory(folder, out, true, true)
        }
      })
    }
  }
  
  object AuxConstants {
    val outputFolder = new File(".", "spore-dist")
  }

  import TriviaBuildKeys._

  lazy val triviaBuildSettings = Defaults.defaultSettings ++ Seq(
    readConfig := AuxUtils.config,
    Keys.libraryDependencies += "com.typesafe" % "config" % "1.0.2",

    findDependencies <<= update map { updateReport =>
      {
        updateReport.allFiles map (f => { f }) toList
      }
    },

    triviaBuild <<= (findDependencies map { deps =>
      {
        import AuxConstants._
        IO.delete(outputFolder)
        if(!outputFolder.mkdirs()) fail("Could not create Spore dist folder")
        
        val conf = AuxUtils.config
        // Copy JDKs
        val winJDKOutputFolder = new File(outputFolder, AuxUtils.getFolderName(conf.windowsJDKPath) + "_windows")
        val linuxJDKOutputFolder = new File(outputFolder, AuxUtils.getFolderName(conf.linuxJDKPath) + "_linux")
        List((conf.windowsJDKPath ,winJDKOutputFolder), (conf.linuxJDKPath,linuxJDKOutputFolder)).foreach(folderPair => {
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
        IO.copyDirectory(new File(conf.playPath), playOutputFolder, true, true)
        // Copy project
        val projectInputFolder = new File(".", "project")
        val projectOutputFolder = new File(outputFolder, "project")
        projectOutputFolder.mkdir()
        if(!(projectInputFolder.listFiles() == null)) {
          projectInputFolder.listFiles.filter(_.isFile).foreach(confFile => {
            val outFile = new File(projectOutputFolder, confFile.getName)
            outFile.createNewFile()
            val confLines = scala.io.Source.fromFile(outFile).getLines.filter(line => !line.contains("trivia-build:delete")).toList;
            IO.writeLines(outFile, confLines, IO.utf8, false)
          })
        }
        
        
        // Copy conf
        val confOutputFolder = new File(outputFolder, "conf")
        IO.copyDirectory(new File(".", "conf"), confOutputFolder, true, true)
        // Copy public
      }
    }))
    
    
    
    
    
}