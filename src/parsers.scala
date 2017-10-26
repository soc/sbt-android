package android

import java.io.File

import scala.collection.JavaConverters._
import scala.xml.{Elem, Node, NodeSeq, XML}

import sbt.{Def, State, TaskKey}
import sbt.Def.Initialize
import sbt.complete.Parser
import sbt.complete.Parser._
import sbt.complete.Parsers._
import sbt.Defaults.loadFromContext
import sbt.CacheImplicits.StringJsonFormat

import com.android.sdklib.repository.AndroidSdkHandler

import sjsonnew.{Builder, JsonFormat, Unbuilder}


/**
  * @author pfnguyen
  */
private[android] object parsers {
  val ACTION_MAIN = "android.intent.action.MAIN"
  def activityName(n: Node): String = n.attribute(Resources.ANDROID_NS, "name").head.text
  def findMainActivities(element: Elem): NodeSeq = {
    for {
      a <- element \\ "activity" ++ element \\ "activity-alias"
      i <- a \ "intent-filter" \ "action"
      nm <- i.attribute(Resources.ANDROID_NS, "name").toSeq.flatten
      m <- nm if m.text == ACTION_MAIN
    } yield a
  }
  def activityParser: Initialize[State => Parser[Option[String]]] =
    loadForParser2(Keys.processManifest, Keys.applicationId) { (state, mfile, appid) =>
      val parser = for {
        f   <- mfile if f.isFile
        pkg <- appid
      } yield {
        val manifest = XML.loadFile(f)
        val names = findMainActivities(manifest) map activityName
        EOF.map(_ => None) | (Space ~> opt(
          (token(StringBasic.examples(pkg + "/")) ~ token(StringBasic.examples(names:_*)))
            .map { case (a,b) => a + b }
        ))
      }
      parser getOrElse opt(Def.spaceDelimited("<activity name>").map(_.mkString(" ")))
    }

  private[this] def sdkManager(s: State): AndroidSdkHandler = {
    val e = sbt.Project.extract(s)
    def existing = {
      val androids = e.structure.allProjects map (p => sbt.ProjectRef(e.structure.root, p.id)) filter {
        ref => e.getOpt(Keys.projectLayout in ref).isDefined
      }
      androids.headOption.map(p => e.get(Keys.Internal.sdkManager in p))
    }

    e.getOpt(Keys.Internal.sdkManager).orElse(existing).getOrElse(
      SdkInstaller.sdkManager(
        sbt.file(SdkInstaller.sdkPath(s.log, Tasks.loadProperties(sbt.file(".")))),
        showProgress = true, s.log))
  }
  def installSdkParser: State => Parser[Option[String]] = state => {
    val ind = SbtAndroidProgressIndicator(state.log)
    val repomanager = sdkManager(state).getSdkManager(ind)
    val newpkgs = repomanager.getPackages.getNewPkgs.asScala.filterNot(_.obsolete).toList.map { p =>
      p.getPath
    }
    EOF.map(_ => Option.empty[String]) |
      Space ~> token(StringBasic).examples(newpkgs:_*).map(Option.apply)
  }
  //noinspection MutatorLikeMethodIsParameterless
  def updateSdkParser: State => Parser[Either[Option[String],String]] = state => {
    val ind = SbtAndroidProgressIndicator(state.log)
    val repomanager = sdkManager(state).getSdkManager(ind)
    val updates = repomanager.getPackages.getUpdatedPkgs.asScala.toList.collect {
      case u if u.hasRemote => u.getRemote.getPath
    }
    EOF.map(_ => Left(Option.empty[String])) | Space ~> choiceParser(
      token("all").map(Option.apply),
      token(StringBasic).examples(updates:_*))
  }
  private[android] implicit val sjsonFileFormat: JsonFormat[File] = new JsonFormat[File] {
    override def write[J](obj: File, builder: Builder[J]): Unit = StringJsonFormat.write(obj.getCanonicalPath, builder)
    override def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): File = sbt.file(StringJsonFormat.read(jsOpt, unbuilder))
  }
  def loadForParser2[P, T: JsonFormat, T2: JsonFormat]
                    (task: TaskKey[T], task2: TaskKey[T2])
                    (f: (State, Option[T], Option[T2]) => Parser[P]): Initialize[State => Parser[P]] =
    loadForParserI2(task, task2)(Def.value(f))

  def loadForParserI2[P, T : JsonFormat, T2 : JsonFormat]
                     (task: TaskKey[T], task2: TaskKey[T2])
                     (init: Initialize[(State, Option[T], Option[T2]) => Parser[P]]): Initialize[State => Parser[P]] =
    Def.setting{
      val ctx = sbt.Keys.resolvedScoped.value
      val f = init.value
      (s: State) => f(s, loadFromContext(task, ctx, s), loadFromContext(task2, ctx, s))
    }
}
