package android

import sbt._
import sbt.compat.Load
import sbt.internal.{BuildStructure, SessionSettings}

import VariantSettings._

/**
 * @author pfnguyen
 */
object VariantSettings {
  type VariantMap = Map[ProjectRef, Seq[Setting[_]]]
  type VariantStatus = Map[ProjectRef, (Option[String],Option[String])]
  def empty(s: State) = VariantSettings(Map.empty, Nil, Map.empty)

  private[android] val onUnloadSkip = AttributeKey[Boolean]("on-unload-skip", "skip onUnload if set")
  private[android] val originalSettings = AttributeKey[Seq[Setting[_]]]("original-settings",
    "Track the settings originally loaded")
  private[android] val explicitGlobalLogLevels = AttributeKey[Boolean](
    "explicit-global-log-levels", "True if the global logging levels were explicitly set by the user.", 10)
  val variantSettings = AttributeKey[VariantSettings](
    "flavor-settings", "Tracks current build, project, and setting flavor modifications.", KeyRanks.DSetting)

  def variant(state: State): VariantSettings = state.get(variantSettings) getOrElse empty(state)
  def withVariant(s: State)(f: VariantSettings => State): State = f(variant(s))

  def clearVariant(s: State): State = {
    val session = Project.extract(s).session
    s.log.info("Clearing variant configuration from " + session.current.project)
    withVariant(s)(variants => reapply(session,
      variants.copy(append = variants.append - session.current, status = variants.status - session.current), Project.structure(s), s))
  }
  def clearAllVariants(s: State): State = {
    val session = Project.extract(s).session
    s.log.info("Clearing variant configuration from all projects")
    withVariant(s)(variants => reapply(session,
      variants.clearExtraSettings, Project.structure(s), s))
  }
  def clearVariant(s: State, ref: ProjectRef): State = {
    val session = Project.extract(s).session
    s.log.info("Clearing variant configuration from " + ref.project)
    withVariant(s)(variants => reapply(session,
      variants.copy(append = variants.append - ref, status = variants.status - ref), Project.structure(s), s))
  }

  def fixProjectScope(prj: ProjectRef): Setting[_] => Setting[_] = s => {
    val mapper = new Def.MapScoped {
      override def apply[T](a: Def.ScopedKey[T]) = {
        val scope0 = if (a.scope.project == This)
          a.scope.copy(project = Select(prj)) else a.scope
        val scope1 = if (scope0.task == This)   scope0.copy(task = Zero)   else scope0
        val scope2 = if (scope1.extra == This)  scope1.copy(extra = Zero)  else scope1
        val scope3 = if (scope2.config == This) scope2.copy(config = Zero) else scope2
        a.copy(scope = scope3)
      }
    }
    s.mapKey(mapper).mapReferenced(mapper)
  }
  def setVariant(s: State,
                 project: ProjectRef,
                 buildType: Option[String],
                 flavor: Option[String]): State = withVariant(s) { variants =>
    if (buildType.nonEmpty || flavor.nonEmpty) {
      val extracted = Project.extract(s)
      val buildTypes = extracted.get(Keys.buildTypes in project)
      val flavors = extracted.get(Keys.flavors in project)
      val ss: Seq[Setting[_]] =
        flavor.toSeq.flatMap(f => flavors.getOrElse(f, Nil)) ++
          buildType.toSeq.flatMap(t => buildTypes.getOrElse(t, Nil))

      val ss3 = variantOptions(buildType, project, s) ++ variantOptions(flavor, project, s) ++ variantOptions(for {
        f <- flavor
        t <- buildType
      } yield f + t.capitalize, project, s) ++
        List(Keys.variantConfiguration := ((buildType,flavor)))

      val ss2 = (ss ++ ss3) map fixProjectScope(project)
      val newVariant = variants.copy(append = variants.append + ((project, ss2)), status = variants.status + ((project, (buildType,flavor))))
      val bt = buildType.getOrElse("(none)")
      val fl = flavor.getOrElse("(none)")
      s.log.info(s"Applying variant settings buildType=$bt flavor=$fl to ${project.project}...")
      reapply(extracted.session, newVariant, extracted.structure, s)
    } else s
  }

  def variantOptions(variant: Option[String], project: ProjectRef, s: State): Seq[Setting[_]] = {
    val sourceDirectory = sbt.Keys.sourceDirectory in Global in project
    val e = Project.extract(s)
    val srcbase = e.get(sourceDirectory)
    def overlayManifest(v: Option[String]): Seq[Setting[_]] = v.fold(Seq.empty[Setting[_]]) { t =>
      val variantManifest = srcbase / t / "AndroidManifest.xml"
      if (variantManifest.isFile)
        List(Keys.manifestOverlays += sourceDirectory.value / t / "AndroidManifest.xml")
      else Nil
    }
    variant.fold(Seq.empty[Setting[_]]) { name =>
      val variantManifest = overlayManifest(variant)
      Seq(
        sbt.Keys.unmanagedSourceDirectories in Compile in project ++= {
          val srcbase = sourceDirectory.value
          srcbase / name / "java" ::
            srcbase / name / "scala" ::
            Nil
        },
        sbt.Keys.resourceDirectories in Compile in project += {
          sourceDirectory.value / name / "resources"
        },
        Keys.extraResDirectories in Keys.Android in project += {
          sourceDirectory.value / name / "res"
        },
        Keys.extraAssetDirectories in Keys.Android in project += {
          sourceDirectory.value / name / "assets"
        }) ++ variantManifest
    }
  }
  def showVariantStatus(s: State, project: ProjectRef): State = withVariant(s) { variants =>
      val extracted = Project.extract(s)
      val (bt,f) = variants.status.getOrElse(project, (None,None))
      val buildTypes = extracted.get(Keys.buildTypes in project)
      val flavors = extracted.get(Keys.flavors in project)
      val bts = bt.getOrElse("(none)")
      val fs = f.getOrElse("(none)")
      s.log.info(s"${project.project}: buildType=$bts flavor=$fs")
      val abt = if (buildTypes.isEmpty) "  (none)"
      else buildTypes.keys map ("  " + _) mkString "\n"
      val af = if (flavors.isEmpty) "  (none)"
      else flavors.keys map ("  " + _) mkString "\n"
      s.log.info("Available buildTypes:")
      s.log.info(abt)
      s.log.info("Available flavors:")
      s.log.info(af)
      s
  }
  def append(s: State, ss: Seq[Setting[_]]): State = {
    val e = Project.extract(s)
    val appendSettings = Load.transformSettings(Load.projectScope(e.currentRef), e.currentRef.build, e.rootProject, ss)
    val newSettings = e.session.original ++ appendSettings
    val newStructure = Load.reapply(newSettings, e.structure)(Project.showContextKey(e.session, e.structure))
    Project.setProject(e.session.copy(original = newSettings), newStructure, s.put(originalSettings, s.get(originalSettings).getOrElse(e.session.original) ++ appendSettings).put(onUnloadSkip, true))
  }

  def reapply(session: SessionSettings, newVariant: VariantSettings, structure: BuildStructure, s: State): State = {
    // Here, for correct behavior, we also need to re-inject a settings logger, as we'll be re-evaluating settings.
    val loggerInject = sbt.Keys.sLog in GlobalScope := new Logger {
      private[this] val ref = new java.lang.ref.WeakReference(s.globalLogging.full)
      private[this] def slog: Logger = Option(ref.get) getOrElse sys.error("Settings logger used after project was loaded.")

      override def trace(t: => Throwable) = slog.trace(t)
      override def success(message: => String) = slog.success(message)
      override def log(level: Level.Value, message: => String) = slog.log(level, message)
    }
    val withLogger = newVariant.appendRaw(loggerInject :: Nil)
    val originals = s.get(originalSettings) getOrElse session.original
    val session2 = session.copy(original = originals ++ withLogger.mergeSettings)
    val newStructure = Load.reapply(session2.mergeSettings, structure)(showContextKey(session2, structure))
    setProject(newVariant, session2, originals, newStructure, s)
  }

  def showContextKey(session: SessionSettings, structure: BuildStructure, keyNameColor: Option[String] = None): Show[ScopedKey[_]] =
    Def.showRelativeKey(session.current, structure.allProjects.size > 1, keyNameColor)
  def setProject(variants: VariantSettings, session: SessionSettings, originals: Seq[Setting[_]], structure: BuildStructure, s: State): State = {
    val unloaded = Project.runUnloadHooks(s.put(onUnloadSkip, true))
    val (onLoad, onUnload) = Project.getHooks(structure.data)
    val newAttrs = unloaded.attributes
      .put(sbt.Keys.stateBuildStructure, structure)
      .put(variantSettings, variants)
      .put(sbt.Keys.sessionSettings, session)
      .put(sbt.Keys.onUnload.key, onUnload)
      .put(originalSettings, originals)
    val newState = unloaded.copy(attributes = newAttrs)
    onLoad(setGlobalLogLevels(Project.updateCurrent(newState), structure.data))
  }

  private[this] def hasExplicitGlobalLogLevels(s: State): Boolean = s.get(explicitGlobalLogLevels) getOrElse false
  private[this] def setGlobalLogLevels(s: State, data: Settings[Scope]): State =
    if (hasExplicitGlobalLogLevels(s))
      s
    else {
      // FIXME: https://github.com/sbt/sbt/blob/05c2c506b2b218d8ed8befcef843951391c07be6/main/src/main/scala/sbt/internal/LogManager.scala#L204-L222
      //def get[T](key: SettingKey[T]) = key in GlobalScope get data
      //def transfer(l: AbstractLogger, traceKey: SettingKey[Int], levelKey: SettingKey[Level.Value]) {
      //  get(traceKey).foreach(l.setTrace)
      //  get(levelKey).foreach(l.setLevel)
      //}
      //val logging = s.globalLogging
      //transfer(logging.full, sbt.Keys.traceLevel, sbt.Keys.logLevel)
      //transfer(logging.backed, sbt.Keys.persistTraceLevel, sbt.Keys.persistLogLevel)
      s
    }

}
final case class VariantSettings(append: VariantMap, rawAppend: Seq[Setting[_]], status: VariantStatus) {
  def appendRaw(ss: Seq[Setting[_]]): VariantSettings = copy(rawAppend = rawAppend ++ ss)
  def mergeSettings: Seq[Setting[_]] = merge(append) ++ rawAppend
  def clearExtraSettings: VariantSettings = VariantSettings(Map.empty, Nil, Map.empty)

  private[this] def merge(map: VariantMap): Seq[Setting[_]] = map.values.toList.flatten[Setting[_]]
}
