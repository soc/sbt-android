package android

import java.util.Properties
import java.io.File
import java.net.URLEncoder

import scala.collection.JavaConverters._
import scala.util.Try
import scala.xml._
import scala.sys.process._

import com.android.builder.core._
import com.android.builder.compiling.{BuildConfigGenerator, ResValueGenerator}
import com.android.builder.internal.ClassFieldImpl
import com.android.manifmerger.ManifestMerger2
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.{IAndroidTarget, SdkVersionInfo}
import com.android.sdklib.BuildToolInfo.PathId
import com.android.ddmlib.{DdmPreferences, IDevice}
import com.android.ddmlib.testrunner.ITestRunListener


import sbt._
import sbt.Keys._
import sbt.internal.BuildStructure

import Keys._
import Keys.Internal._
import Dependencies.{AutoLibraryProject => _, LibraryProject => _, _}


import Resources.{ANDROID_NS, resourceUrl}
import android.BuildOutput.Converter


object Tasks extends TaskBase {
  val TOOLS_NS = "http://schemas.android.com/tools"
  val INSTRUMENTATION_TAG = "instrumentation"
  val USES_LIBRARY_TAG = "uses-library"
  val APPLICATION_TAG = "application"
  val ANDROID_PREFIX = "android"
  val TEST_RUNNER_LIB = "android.test.runner"

  val resValuesGeneratorTaskDef = Def.task {
    implicit val output = outputLayout.value
    val resources = resValues.value
    val items = resources map { case (typ, n, value) =>
      new ClassFieldImpl(typ, n, value)
    }
    val resTarget = projectLayout.value.generatedRes
    if (items.nonEmpty) {
      val generator = new ResValueGenerator(resTarget)
      generator.addItems((items: Seq[Object]).asJava)
      generator.generate()
    } else {
      IO.delete(resTarget / "values" / "generated.xml")
    }
  }

  val buildConfigGeneratorTaskDef = Def.task {
    val _ =  platformTarget.value
    val layout = projectLayout.value
    val l = libraryProjects.value
    val p = packageForR.value
    val o = buildConfigOptions.value
    val d = apkbuildDebug.value

    val b = new BuildConfigGenerator(layout.gen, p)
    b.addField("boolean", "DEBUG", d.toString)
    o.groupBy(_._2).values.map(_.head) foreach {
      case (tpe, n, value) => b.addField(tpe, n, value)
    }
    b.generate()
    l collect {
      case a: ApkLibrary         => a
      case a: AutoLibraryProject => a
    } foreach { lib =>
      val b = new BuildConfigGenerator(layout.gen, lib.pkg)
      b.addField("boolean", "DEBUG", d.toString)
      o foreach {
        case (tpe, n, value) => b.addField(tpe, n, value)
      }
      b.generate()
    }
    (layout.gen ** "BuildConfig.java").get
  }

  def moduleForFile(u: UpdateReport, f: File): ModuleID = {
    def concat(s1: Option[String], s2: Option[String]) =
      Option((s1 ++ s2).toList.distinct.mkString(",")).filter(_.nonEmpty)
    val map: Map[File,ModuleID] = {
      val fm = u.configurations.flatMap { c =>
        c.modules.flatMap { mr =>
          val module = mr.module.withConfigurations(Some(c.configuration.name))
          mr.artifacts.map { case (_, file) => (file, module) }
        }
      }
      fm.foldLeft(Map.empty[File,ModuleID]) { case (m, (k, mid)) =>
        val mid2 = m.getOrElse(k, mid)
        m.updated(k, mid2.withConfigurations(concat(mid.configurations, mid2.configurations)))
      }
    }

    map(f)
  }
  // takeWhile hack to bypass cross versions, I hope no real artifacts have
  // underscores in the name as a delimiter
  def moduleString(m: ModuleID): String =
    m.organization + ":" + m.name.takeWhile(_ != '_')

  val aarsTaskDef = Def.task {
    val u = (update in Compile).value
    val local = localAars.value
    val withTest = debugIncludesTests.?.value.getOrElse(false)
    val d = (libraryDependencies in Compile).value
    val tx = transitiveAndroidLibs.value
    val tw = transitiveAndroidWarning.value
    val ta = transitiveAars.value
    val layout = projectLayout.value
    implicit val output = outputLayout.value
    val s = streams.value
    val debug = apkbuildDebug.value()
    //    implicit val output = o

    def excludeTests(m: ModuleID): Boolean = {
      val isTest = m.configurations.exists(c => c.split(",").forall(s => s.startsWith("test") || s == "androidTest"))

      (isTest && !withTest) || (isTest && !debug)
    }

    val subaars = ta.collect { case a: AarLibrary => a }.map { a => moduleString(a.moduleID) }.toSet
    s.log.debug("aars in subprojects: " + subaars)
    val libs = u.matching(artifactFilter(`type` = "aar"))

    val deps = d.filterNot(_.configurations.exists(
      _ contains "test")).map(moduleString).toSet
    (libs flatMap { l =>
      val dest = SdkLayout.explodedAars
      val m = moduleForFile(u, l)
      if (excludeTests(m)) {
        s.log.debug("Excluding testing dependency: " + m)
        None
      } else if (tx || deps(moduleString(m))) {
        val mID = m.organization + "-" + m.name + "-" + m.revision
        val d = dest / mID
        if (!subaars(moduleString(m))) {
          IO.touch(layout.aars / mID)
          Some(unpackAar(l, d, m, s.log): LibraryDependency)
        } else {
          s.log.debug(m + " was already included by a dependent project, skipping")
          None
        }
      } else {
        if (tw)
          s.log.warn(m + " is not an explicit dependency, skipping")
        None
      }
    }) ++ (local map { a =>
      val dest = layout.aars
      unpackAar(a, dest / ("localAAR-" + a.getName), "localAAR" % a.getName % "LOCAL", s.log)
    })
  }

  val androidTestAarsTaskDef = Def.task {
    val u = (update in Compile).value
    val withTest = debugIncludesTests.?.value.getOrElse(false)
    val s = streams.value
    def testOnly(m: ModuleID): Boolean = {
      !withTest && m.configurations.exists(c => c.split(",").forall(_ == "androidTest"))
    }
    val libs = u.matching(artifactFilter(`type` = "aar"))
    libs.flatMap { l =>
      val m = moduleForFile(u, l)
      val dest = SdkLayout.explodedAars
      if (testOnly(m)) {
        val mID = m.organization + "-" + m.name + "-" + m.revision
        val d = dest / mID
        if (testAarWarning.value)
          s.log.warn("androidTest: test-only aar will not have resources merged " + m)
        List(unpackAar(l, d, m, s.log): LibraryDependency)
      } else {
        Nil
      }
    }
  }

  def unpackAar(aar: File, dest: File, m: ModuleID, log: Logger): LibraryDependency = {
    val am = dest / "aar.manifest"
    def checkmanifest(d: File) = am.isFile && {
      val ns = IO.readLines(am)
      val fs = ns.map(file)
      fs.forall(_.exists)
    }
    val _ = AarLibrary(dest)
    if (dest.lastModified < aar.lastModified || !checkmanifest(dest)) {
      IO.delete(dest)
      val mfile = Dependencies.moduleIdFile(dest)
      val mline = s"${m.organization}:${m.name}:${m.revision}"
      IO.writeLines(mfile, mline :: Nil)
      log.info("Unpacking aar: %s to %s" format (aar.getName, dest.getName))
      dest.mkdirs()
      val files = IO.unzip(aar, dest)
      IO.writeLines(am, files.filter(_.isFile).toList.map(_.getAbsolutePath))
    }
    AarLibrary(dest)
  }

  val autolibsTaskDef = Def.task {
    val prjs = localProjects.value
    val layout = projectLayout.value
    val o = outputLayout.value
    val isLib = libraryProject.value
    val bldr = builder.value
    val _ = ilogger.value
    val debug = apkbuildDebug.value
    val cfgs = resConfigs.value
    val aparams = aaptAdditionalParams.value
    val pseudo = pseudoLocalesEnabled.value
    val s = streams.value

     implicit val output = o
     prjs collect { case a: AutoLibraryProject => a } flatMap { lib =>
      s.log.info("Processing library project: " + lib.pkg)

      lib.getProguardRules.getParentFile.mkdirs
      // TODO collect resources from apklibs and aars
//      doCollectResources(bldr, true, true, Seq.empty, lib.layout,
//        logger, file("/"), s)
      Resources.aapt(bldr(s.log), lib.getManifest, null, aparams, cfgs, Seq.empty, lib = true, debug = debug(),
          pseudoLocalize = pseudo, lib.getResFolder, lib.getAssetsFolder, null,
          lib.layout.gen, lib.getProguardRules, layout.aaptTemp,
          s.log)

      def copyDirectory(src: File, dst: File) {
        IO.copy((src.allPaths --- (src ** "R.txt")) pair Path.rebase(src, dst), overwrite = false, preserveLastModified = true, preserveExecutable = true)
      }
      if (isLib)
        copyDirectory(lib.layout.gen, layout.gen)
      Some(lib: LibraryDependency)
    }
  }

  val apklibsTaskDef = Def.task {
    val u = (update in Compile).value
    val d = (libraryDependencies in Compile).value
    val layout = projectLayout.value
    val o =  outputLayout.value
    val isLib = libraryProject.value
    val tx = transitiveAndroidLibs.value
    val tw = transitiveAndroidWarning.value
    val s = streams.value
    val agg = aaptAggregate.value

    implicit val output: Converter = o
    val libs = u.matching(artifactFilter(`type` = "apklib"))
    val dest = layout.apklibs
    val deps = d.filterNot(_.configurations.exists(
      _ contains "test")).map(moduleString).toSet
    libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (tx || deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        val lib = ApkLibrary(d)
        if (d.lastModified < l.lastModified || !lib.getManifest.exists) {
          s.log.info("Unpacking apklib: " + m)
          d.mkdirs()
          IO.unzip(l, d)

          Resources.aapt(agg.builder(s.log), lib.getManifest, null,
            agg.additionalParams, agg.resConfigs, Seq.empty, lib = true, debug = agg.debug,
            pseudoLocalize = agg.pseudoLocalize,
            lib.getResFolder, lib.getAssetsFolder, null,
            lib.layout.gen, lib.getProguardRules, layout.aaptTemp,
            s.log)
        }
        def copyDirectory(src: File, dst: File) {
          IO.copy((src.allPaths --- (src ** "R.txt")) pair Path.rebase(src, dst), overwrite = false, preserveLastModified = true, preserveExecutable = true)
        }
        if (isLib)
          copyDirectory(lib.layout.gen, layout.gen)
        Some(lib: LibraryDependency)
      } else {
        if (tw)
          s.log.warn(m + " is not an explicit dependency, skipping")
        None
      }
    }
  }

  implicit class OrgArtRepOps(report: OrganizationArtifactReport) {
    def ids: Seq[ModuleID] = report.modules map(_.module)

    def latest: String = ids.max.revision
  }

  implicit val revOrdering: Ordering[ModuleID] = new Ordering[ModuleID] {
    def comparePart(part: (String, String)): Int = {
      val (a, b) = part
      Try((a.toInt, b.toInt)) match {
        case scala.util.Success((l, r)) ⇒
          l compareTo r
        case scala.util.Failure(_) ⇒
          a compareTo b
      }
    }

    def compare(a: ModuleID, b: ModuleID): Int = {
      val aParts = a.revision.split('.')
      val bParts = b.revision.split('.')
      aParts.zip(bParts)
        .map(comparePart)
        .find(_ != 0)
        .getOrElse(aParts.length compareTo bParts.length)
    }
  }

  val resolvedAars = Def.task {
    (update in Compile).value.configuration(ConfigRef("compile"))
      .map(_.details)
      .getOrElse(Nil)
      .filter(_.modules.exists(_.artifacts.exists(_._1.`type` == "aar")))
  }

  val checkAarsTaskDef = Def.task {
    implicit val log: Logger = streams.value.log
    implicit val struct: BuildStructure = buildStructure.value
    implicit val projects: Seq[ProjectRef] = thisProjectRef.value.deepDeps
    val resolved = resolvedAars.value
    transitiveAars.value
      .collect { case a: AarLibrary ⇒ a → resolved.find(_.name == a.moduleID.name) }
      .collect { case (a, Some(r)) if r.latest > a.moduleID.revision ⇒ r }
      .foreach(reportIncompatibleAars)
  }

  def reportIncompatibleAars
      (aar: OrganizationArtifactReport)
      (implicit log: Logger, struct: BuildStructure, projects: Seq[ProjectRef]): Unit = {
    log.warn(s"aar ${aar.name} older than latest version ${aar.latest}")
    aar.ids foreach { id ⇒
      val dpds = projects filter(_.dependsOn(id)) map(_.project)
      val sourceDesc =
        if (dpds.isEmpty) "as transitive dep"
        else s"specified in ${dpds.mkString(", ")}"
      log.warn(s" * ${id.revision} $sourceDesc")
    }
  }

  val typedResourcesGeneratorTaskDef = Def.task {
    Resources.generateTR(typedResources.value,
      rGenerator.value ++ (collectResources.value._2 ** "*.xml").get,
      packageForR.value, projectLayout.value, platformApi.value,
      platformJars.value, (scalaVersion in ThisProject).value,
      libraryProjects.value, typedResourcesFull.value, typedResourcesIds.value,
      typedResourcesAar.value, typedViewHolders.value,
      typedResourcesIgnores.value, streams.value)
  }

  val viewHoldersGeneratorTaskDef = Def.task {
    Resources.generateViewHolders(
      typedResources.value && typedViewHolders.value,
      packageForR.value,
      platformJars.value,
      projectLayout.value,
      libraryProjects.value,
      typedResourcesAar.value,
      typedResourcesIgnores.value,
      streams.value,
      platformApi.value)
  }

  def ndkbuild(manager: AndroidSdkHandler, layout: ProjectLayout, args: Seq[String],
               envs: Seq[(String,String)], ndkHome: Option[String], srcs: File,
               showProgress: Boolean, log: Logger, debug: Boolean)
              (implicit m: BuildOutput.Converter): Option[File] = {
    val hasJni = (layout.jni ** "Android.mk").get.nonEmpty
    if (hasJni) {
      val ndk = ndkHome.getOrElse {
        val bundlePath = SdkLayout.ndkBundle(manager.getLocation.getAbsolutePath)
        SdkInstaller.autoInstallPackage(manager, "", "ndk-bundle", "android NDK", showProgress, log, _ => !bundlePath.isDirectory)
        bundlePath.getAbsolutePath
      }
      val inputs = (layout.jni ** FileOnlyFilter).get.toSet
      FileFunction.cached(layout.ndkObj, FilesInfo.lastModified) { _ =>
        val env = Seq("NDK_PROJECT_PATH" -> (layout.jni / "..").getAbsolutePath,
          "NDK_OUT" -> layout.ndkObj.getAbsolutePath,
          "NDK_LIBS_OUT" -> layout.ndkBin.getAbsolutePath,
          "SBT_SOURCE_MANAGED" -> srcs.getAbsolutePath) ++ envs

        log.info("Executing NDK build")
        val ndkbuildFile = if (!Commands.isWindows) file(ndk) / "ndk-build" else  {
          val f = file(ndk) / "ndk-build.cmd"
          if (f.exists) f else file(ndk) / "ndk-build.bat"
        }
        val ndkBuildInvocation = Seq(
          ndkbuildFile.getAbsolutePath,
          if (debug) "NDK_DEBUG=1" else "NDK_DEBUG=0"
        ) ++ args

        val rc = Process(ndkBuildInvocation, layout.base, env: _*).!

        if (rc != 0)
          PluginFail("ndk-build failed!")

        (layout.ndkBin ** FileOnlyFilter).get.toSet
      }(inputs)
      Option(layout.ndkBin)
    } else None
  }

  val ndkJavahTaskDef = Def.task {
    val src = (sourceManaged in Compile).value
    val _ = (compile in Compile).value
    val classes = (classDirectory in Compile).value
    val cp = (fullClasspath in Compile).value
    val boot = bootClasspath.value
    val s = streams.value

    val natives = NativeFinder(classes)

    if (natives.nonEmpty) {
      val javah = Seq("javah",
        "-d", src.getAbsolutePath,
        "-classpath", cp map (_.data.getAbsolutePath) mkString File.pathSeparator,
        "-bootclasspath", boot.map(_.data) mkString File.pathSeparator) ++ natives

      s.log.debug(javah mkString " ")

      val rc = javah.!

      if (rc != 0)
        PluginFail("Failed to execute: " + (javah mkString " "))

      (src ** "*.h").get
    } else Seq.empty
  }
  val ndkbuildAggregateTaskDef = Def.task {
    Aggregate.Ndkbuild(ndkJavah.value, ndkPath.value, ndkEnv.value, ndkArgs.value)
  }

  val ndkBuildTaskDef = Def.task {
    val layout = projectLayout.value
    val o = outputLayout.value
    val sdk = sdkManager.value
    val libs = libraryProjects.value
    val srcs = (sourceManaged in Compile).value
    val agg = ndkbuildAggregate.value
    val showProgress = showSdkProgress.value
    val s = streams.value
    val debug = apkbuildDebug.value

    implicit val output: Converter = o
    val subndk = libs flatMap { l =>
      ndkbuild(sdk, l.layout, agg.args, agg.env, agg.path, srcs, showProgress, s.log, debug()).toSeq
    }

    ndkbuild(sdk, layout, agg.args, agg.env, agg.path, srcs, showProgress, s.log, debug()).toSeq ++ subndk
  }

  val collectProjectJniTaskDef = Def.task {
    val layout = projectLayout.value
    implicit val output: Converter = outputLayout.value
    ndkBuild.value ++ Seq(layout.jniLibs, layout.rsLib).filter(_.isDirectory)
  }

  val collectJniTaskDef = Def.task {
    implicit val output: Converter = outputLayout.value
    def libJni(lib: LibraryDependency): Seq[File] =
      Seq(lib.getJniFolder, lib.layout.rsLib).filter(_.isDirectory) ++
        lib.getLibraryDependencies.asScala.flatMap(l => libJni(l.asInstanceOf[LibraryDependency]))

    collectProjectJni.value ++ libraryProjects.value.flatMap(libJni)
  }


  val collectResourcesAggregateTaskDef = Def.task {
    Aggregate.CollectResources(
      libraryProject.value, libraryProjects.value, packageForR.value,
      extraResDirectories.value,
      extraAssetDirectories.value,
      projectLayout.value, outputLayout.value)
  }
  val collectResourcesTaskDef = Def.task {
    val bldr = builder.value
    val noTestApk = debugIncludesTests.?.value.getOrElse(false)
    val minSdk = minSdkVersion.value
    val cra = collectResourcesAggregate.value
    val logger = ilogger.value
    val rv = renderVectorDrawables.value
    val c = aaptPngCrunch.value
    val c9 = aapt9PngCrunch.value
    val s = streams.value
    implicit val output: Converter = cra.outputLayout
    val layout = cra.projectLayout
    val er = cra.extraResDirectories
    val ea = cra.extraAssetDirectories
    val isLib = cra.libraryProject
    val libs = cra.libraryProjects
    val minLevel = Try(minSdk.toInt).toOption getOrElse
      SdkVersionInfo.getApiByBuildCode(minSdk, true)
    // hack because cached can only return Set[File]
    val out = (layout.mergedAssets, layout.mergedRes)
    val assets = layout.assets +: ea.map(_.getCanonicalFile).distinct.flatMap(a => (a ** FileOnlyFilter).get)
    withCachedRes(s, "collect-resources-task", assets ++ normalres(layout, er, libs), genres(layout, libs)) {
      val res = Resources.doCollectResources(bldr(s.log), cra.packageForR, minLevel, noTestApk,
        isLib, libs, layout, ea, layout.generatedRes +: er, rv, c, c9, logger(s.log),
        s.cacheDirectory, s)
      if (out != res) sys.error(s"Unexpected directories $out != $res")
      Set(res._1, res._2)
    }
    out
  }


  val packageApklibMappings = Def.task {
    val layout = projectLayout.value
    implicit val output: Converter = outputLayout.value
    import layout._

    (PathFinder(layout.processedManifest) pair Path.flat) ++
    (PathFinder(javaSource) ** "*.java"   pair Path.rebase(javaSource,  "src"))  ++
    (PathFinder(scalaSource) ** "*.scala" pair Path.rebase(scalaSource, "src"))  ++
    (PathFinder(libs).allPaths            pair Path.rebase(libs,        "libs")) ++
    (PathFinder(res).allPaths             pair Path.rebase(res,         "res"))  ++
    (PathFinder(assets).allPaths          pair Path.rebase(assets,      "assets"))
  }
  val packageApklibTaskDef = Def.task {
    implicit val output: Converter = outputLayout.value
    val outfile = projectLayout.value.outputApklibFile(name.value)
    streams.value.log.info("Packaging " + outfile.getName)
    val mapping = (mappings in packageApklib).value
    IO.jar(mapping, outfile, new java.util.jar.Manifest)
    outfile
  }

  val packageAarMappings = Def.task {
    implicit val output: Converter = outputLayout.value
    val layout = projectLayout.value
    import layout._

    val so = collectProjectJni.value
    val j = (packageT in Compile).value
    val rsLibs = layout.rsLib
    val rsRes = layout.rsRes

    (PathFinder(layout.processedManifest)       pair Path.flat) ++
    (PathFinder(layout.rTxt)                    pair Path.flat) ++
    (PathFinder(layout.proguardTxt)             pair Path.flat) ++
    (PathFinder(j)                              pair Path.flat) ++
    ((PathFinder(libs) ** "*.jar")              pair Path.rebase(libs,   "libs")) ++
    ((PathFinder(rsLibs) * "*.jar")             pair Path.rebase(rsLibs, "libs")) ++
    (PathFinder(res).allPaths                   pair Path.rebase(res,    "res"))  ++
    (PathFinder(rsRes).allPaths                 pair Path.rebase(rsRes,  "res"))  ++
    (PathFinder(assets).allPaths                pair Path.rebase(assets, "assets")) ++
    so.flatMap { d => (PathFinder(d) ** "*.so") pair Path.rebase(d, "jni") }
  }

  val packageAarTaskDef = Def.task {
    implicit val output: Converter = outputLayout.value
    val outfile = projectLayout.value.outputAarFile(name.value)
    val mapping = (mappings in packageAar).value
    streams.value.log.info("Packaging " + outfile.getName)

    IO.jar(mapping, outfile, new java.util.jar.Manifest)
    outfile
  }

  val packageResourcesTaskDef = Def.task {
    val agg = aaptAggregate.value
    val layout = projectLayout.value
    val output = outputLayout.value
    val extrares = extraResDirectories.value
    val manif = processManifest.value
    val (assets, res) = collectResources.value
    val pkg = packageForR.value
    val lib = libraryProject.value
    val libs = libraryProjects.value
    val s = streams.value

    implicit val o: Converter = output
    val p = layout.resApk(agg.debug)
    withCachedRes(s, p.getName, layout.manifest +: normalres(layout, extrares, libs), genres(layout, libs)) {
      layout.proguardTxt.getAbsolutePath
      layout.proguardTxt.getParentFile.mkdirs()

      s.log.info("Packaging resources: " + p.getName)
      Resources.aapt(agg.builder(s.log), manif, pkg, agg.additionalParams,
        agg.resConfigs, libs, lib, agg.debug, agg.pseudoLocalize, res, assets, p,
        layout.gen, layout.proguardTxt, layout.aaptTemp, s.log)
      Set(p)
    }
    p
  }

  // collect un-merged resources for cached(),
  // post-collectResources has modified timestamps
  // this breaks for `extraResDirectories` in sub projects
  def normalres(layout: ProjectLayout, extrares: Seq[File], libs: Seq[LibraryDependency]): Seq[File] =
    libs.flatMap(l => (l.getResFolder ** FileOnlyFilter).get) ++
      (layout.res ** FileOnlyFilter).get ++
      extrares.map(_.getCanonicalFile).distinct.flatMap(a => (a ** FileOnlyFilter).get)

  def genres(layout: ProjectLayout, libs: Seq[LibraryDependency])
            (implicit out: BuildOutput.Converter): Seq[File] =
    (libs flatMap {
      case lp: LibraryProject =>
        (lp.layout.generatedRes ** FileOnlyFilter).get
      case _ => Nil
    }) ++ (layout.generatedRes ** FileOnlyFilter).get

  val apkbuildAggregateTaskDef = Def.task {
    Aggregate.Apkbuild(packagingOptions.value,
      apkbuildDebug.value(), apkDebugSigningConfig.value,
      processManifest.value, dex.value, predex.value, collectJni.value,
      resourceShrinker.value, minSdkVersion.value.toInt)
  }

  val apkbuildTaskDef = Def.task {
    implicit val output: Converter = outputLayout.value
    val layout = projectLayout.value
    val a = apkbuildAggregate.value
    val n = name.value
    val u = (unmanagedJars in Compile).value
    val m = managedClasspath.value
    val dcp = (dependencyClasspath in Runtime).value.filterNot(_.data == layout.classesJar)
    val s = streams.value
    val filter = ndkAbiFilter.value
    val logger = ilogger.value(s.log)
    Packaging.apkbuild(builder.value(s.log), Packaging.Jars(m, u, dcp), libraryProject.value, a,
      filter.toSet, layout.collectJni, layout.resources, layout.collectResource, layout.mergedAssets,
      layout.unsignedApk(a.apkbuildDebug, n), logger, s)
  }

  val signReleaseTaskDef = Def.task {
    val c = apkSigningConfig.value
    val a = apkbuild.value
    val d = apkbuildDebug.value
    val l = projectLayout.value
    val o = outputLayout.value
    val s = streams.value

    implicit val output: Converter = o
    if (d()) {
      s.log.info("Debug package does not need signing: " + a.getName)
      a
    } else {
      c map { cfg =>
        import SignAndroidJar._
        val signed = l.signedApk(a)
        val options = Seq( storeType(cfg.storeType)
          , storePassword(cfg.storePass)
          , signedJar(signed)
          , keyStore(cfg.keystore.toURI.toURL)
        )

        val kp = cfg.keyPass map { p => keyPassword(p) }
        sign(a, cfg.alias, options ++ kp) { (jarsigner, args) =>
          (jarsigner +: (args ++ Seq(
            "-digestalg", "SHA1", "-sigalg", "MD5withRSA"))).!
        }

        s.log.info("Signed: " + signed.getName)
        signed
      } getOrElse {
        s.log.warn("Package needs signing: " + a.getName)
        a
      }
    }
  }

  val zipalignTaskDef = Def.task {
    val z = zipalignPath.value
    val r = signRelease.value
    val a = apkFile.value
    val l = projectLayout.value
    val o = outputLayout.value
    val s = streams.value

    implicit val output: Converter = o
    if (r.getName.contains("-unsigned")) {
      s.log.warn("Package needs signing and zipaligning: " + r.getName)
      a.delete()
      r
    } else {
      val aligned = l.alignedApk(r)

      val checkalign = Seq(z, "-c", "-p", "4", r.getAbsolutePath).!

      if (checkalign != 0) {
        val rv = Seq(z, "-f", "-p", "4", r.getAbsolutePath, aligned.getAbsolutePath).!

        if (rv != 0) {
          PluginFail("zipalign failed")
        }

        s.log.info("zipaligned: " + aligned.getName)
        a.getParentFile.mkdirs()
        IO.copyFile(aligned, a)
      } else {
        s.log.debug("APK is already aligned, skipping zipalign")
        IO.copyFile(r, aligned)
        IO.copyFile(r, a)
      }
      aligned
    }
  }

  val renderscriptTaskDef =
    Def.task {
      val str = streams.value
      implicit val output: Converter = outputLayout.value
      val layout = projectLayout.value
      val scripts = (layout.renderscript ** "*.rs").get

      FileFunction.cached(str.cacheDirectory / "rs-gen", FilesInfo.lastModified) { _ =>
        IO.delete(layout.rsBin)
        val target = Try(rsTargetApi.value.toInt).getOrElse(11) max 11
        val abis = ndkAbiFilter.value.toSet
        val abiFilter = if (abis.isEmpty) null else abis.asJava
        val bldr = builder.value(str.log)
        bldr.compileAllRenderscriptFiles(Seq(layout.renderscript).asJava,
          List.empty.asJava, layout.rsSrc, layout.rsRes, layout.rsObj,
          layout.rsLib, target, false, rsOptimLevel.value, false,
          rsSupportMode.value, abiFilter,
          SbtProcessOutputHandler(str.log))

        if (rsSupportMode.value) { // copy support library
          val in = SdkLayout.renderscriptSupportLibFile(buildToolInfo.value)
          val out = layout.rsLib
          IO.copy(
            (in * "*.jar" pair Path.rebase(in, out)) ++
              (in / "packaged" ** "*.so" pair Path.rebase(in / "packaged", out))
          )
        }
        layout.rsBin.allPaths.get.filter(_.isFile).toSet
      }(scripts.toSet)

      (layout.rsSrc ** "*.java").get
    }

  val aidlTaskDef = Def.task {
    val s = sdkPath.value
    val m = sdkManager.value
    val layout = projectLayout.value
    val p = platform.value
    val l = streams.value

    val tools = Option(m.getLatestBuildTool(SbtAndroidProgressIndicator(l.log), false))
    val aidl  = tools map (_.getPath(PathId.AIDL)) getOrElse SdkLayout.aidl(s).getCanonicalPath
    val frameworkAidl = p.getTarget.getPath(IAndroidTarget.ANDROID_AIDL)
    val aidls = (layout.aidl ** "*.aidl").get

    aidls flatMap { idl =>
      val out = (layout.gen ** (idl.getName.stripSuffix(".aidl") + ".java")).get
      val cmd = Seq(aidl,
        "-p" + frameworkAidl,
        "-o" + layout.gen.getAbsolutePath,
        "-I" + layout.aidl.getAbsolutePath
        ) :+ idl.getAbsolutePath

      // TODO FIXME this doesn't account for other dependencies
      if (out.isEmpty || (out exists { idl.lastModified > _.lastModified })) {
        val r = cmd.!

        if (r != 0)
          PluginFail("aidl failed")

        (layout.gen ** (idl.getName.stripSuffix(".aidl") + ".java")).get
      } else out
    }
  }

  val processManifestTaskDef = Def.task {
    val bldr = builder.value
    val isLib = libraryProject.value
    val libs = libraryProjects.value
    val a = manifestAggregate.value
    val merge = mergeManifests.value
    val trunner = instrumentTestRunner.?.value
    val layout = projectLayout.value
    val noTestApk = debugIncludesTests.?.value.getOrElse(false)
    val s = streams.value
    implicit val o: Converter = outputLayout.value
    val pkg = a.applicationId
    val ph = a.placeholders: Map[String,Object]
    val vc = a.versionCode
    val vn = a.versionName
    val sdk = a.targetSdkVersion
    val minSdk = a.minSdkVersion

    layout.bin.mkdirs()
    val output = layout.processedManifest
    output.getParentFile.mkdirs()
    try {
      bldr(s.log).mergeManifestsForApplication(layout.manifest, a.overlays.filter(_.isFile).asJava,
        if (merge && !isLib) libs.asJava else Seq.empty.asJava,
        pkg, vc getOrElse -1, vn.orNull, minSdk.toString, sdk.toString, null,
        output.getAbsolutePath, null, null,
        if (isLib) ManifestMerger2.MergeType.LIBRARY
        else
          ManifestMerger2.MergeType.APPLICATION, ph.asJava, List.empty.asJava,
        layout.processedManifestReport)
    } catch {
      case e: Exception =>
        val ms = layout.manifest :: (a.overlays.filter(_.isFile) ++
          libs.map(_.getManifest).filter(_.isFile)).toList
        s.log.trace(e)
        fail(
          s"Failed to merge manifest files:\n    ${ms.mkString("\n    ")}")
    }
    if (noTestApk && !isLib) {
      val top = XML.loadFile(output)
      val prefix = top.scope.getPrefix(ANDROID_NS)
      val application = top \ APPLICATION_TAG
      val usesLibraries = top \ APPLICATION_TAG \ USES_LIBRARY_TAG
      val instrument = top \ INSTRUMENTATION_TAG
      if (application.isEmpty) PluginFail("no manifest application node")
      val hasTestRunner = usesLibraries exists (
        _.attribute(ANDROID_NS, "name") exists (_ == TEST_RUNNER_LIB))

      def runnerLibTag(top: Elem) = {
        if  (!hasTestRunner) {
          val runnerlib = new PrefixedAttribute(
            prefix, "name", TEST_RUNNER_LIB, Null)
          val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope,
            minimizeEmpty = true)
          val u = top.copy(
            child = top.child.updated(top.child.indexOf(application.head),
              application.head.asInstanceOf[Elem].copy(
                child = application.head.child ++ usesLib)))
          u
        } else top
      }

      def instrumentTag(top: Elem) = {
        if (instrument.isEmpty) {
          trunner.fold(top) { tr =>
            val target = new PrefixedAttribute(prefix,
              "targetPackage", pkg, Null)
            val label = new PrefixedAttribute(prefix,
              "label", "Test Runner", target)
            val name = new PrefixedAttribute(
              prefix, "name", tr, label)
            val instrumentation = new Elem(null,
              INSTRUMENTATION_TAG, name, TopScope, minimizeEmpty = true)
            val u = top.copy(child = top.child ++ instrumentation)
            u
          }
        } else top
      }

      XML.save(output.getAbsolutePath, instrumentTag(runnerLibTag(top)), "utf-8", xmlDecl = true, null)
    }
    output
  }

  val rGeneratorTaskDef = Def.task {
    val agg = aaptAggregate.value
    val layout = projectLayout.value
    val o = outputLayout.value
    val extrares = extraResDirectories.value
    val manif = processManifest.value
    val (assets, res) = collectResources.value
    val pkg = packageForR.value
    val lib = libraryProject.value
    val libs = libraryProjects.value
    val s = streams.value

    implicit val output: Converter = o
    layout.proguardTxt
    layout.proguardTxt.getParentFile.mkdirs()

    // not covered under FileFunction.cached because FilesInfo.hash is too
    // slow. Just let aapt do its thing every time...
    withCachedRes(s, "R.java",
      normalres(layout, extrares, libs), genres(layout, libs)) {
      s.log.info("Processing resources")
      if (!res.exists)
        s.log.warn("No resources found at " + res.getAbsolutePath)
      Resources.aapt(agg.builder(s.log), manif, pkg, agg.additionalParams,
        agg.resConfigs, libs, lib, agg.debug, agg.pseudoLocalize, res, assets, null, layout.gen,
        layout.proguardTxt, layout.aaptTemp, s.log)
      ((layout.gen ** "R.java").get ++ (layout.gen ** "Manifest.java").get).toSet
    }
  }

  def withCachedRes(s: sbt.Keys.TaskStreams, tag: String, inStamp: Seq[File],
                    inHash: Seq[File])(body: => Set[File]): Seq[File] = {
    var dirty = false
    if (inStamp.isEmpty && inHash.isEmpty) body.toSeq else {
      (FileFunction.cached(s.cacheDirectory / tag, FilesInfo.lastModified) { _ =>
        dirty = true
        body
      }(inStamp.toSet) ++
        FileFunction.cached(s.cacheDirectory / (tag + "-hash"), FilesInfo.hash) { _ =>
          if (!dirty) body
          else Set.empty
        }(inHash.toSet)).toSeq
    }
  }

  val proguardConfigTaskDef = Def.task {
    val layout = projectLayout.value
    val out = outputLayout.value
    val p = sdkPath.value
    val a = aars.value
    val txa = transitiveAars.value
    val s = streams.value

    implicit val output: Converter = out
    val proguardTxt     = layout.proguardTxt
    val proguardProject = layout.proguard

    val unused = layout.base / "proguard-sbt.txt"
    if (unused.isFile) {
      s.log.warn(s"$unused is generated by IntelliJ and unused, deleting.")
      s.log.warn(s"Store project-specific rules in ${layout.proguard}")
      unused.delete()
    }

    val base = {
      val c1 = SdkLayout.androidProguardConfig(p)

      if (!c1.exists)
        PluginFail("Unable to locate SDK proguard config: " + c1)
      IO.readLinesURL(resourceUrl("android-proguard.config")) ++
        IO.readLines(c1)
    }
    def lines(file: File): Seq[String] =
      if (file.exists) IO.readLines(file) else Seq.empty

    val aarConfig = (txa ++ a).distinct.flatMap { l =>
      val pc = l.path / "proguard.txt"
      if (pc.isFile) IO.readLines(pc) else Seq.empty
    }
    base ++ lines(proguardProject) ++ lines(proguardTxt) ++ aarConfig: Seq[String]
  }

  val dexMainClassesConfigTaskDef = Def.task {
    implicit val output: Converter = outputLayout.value
    if (libraryProject.value)
      PluginFail("This project cannot dex, it has set 'libraryProject := true'")
    Dex.dexMainClassesConfig(
      processManifest.value,
      (managedClasspath in AndroidInternal).value,
      projectLayout.value,
      dexLegacyMode.value,
      dexMulti.value,
      dexMainRoots.value,
      dexMainClassesRules.value,
      dexInputs.value._2,
      dexMainClasses.value,
      buildToolInfo.value,
      streams.value
    )
  }

  val retrolambdaAggregateTaskDef = Def.task {
    Aggregate.Retrolambda(retrolambdaEnabled.value,
      Project.extract(state.value).currentUnit.classpath, bootClasspath.value.map(_.data), builder.value)
  }
  val dexInputsTaskDef = Def.task {
    val progOut = proguard.value
    val in = proguardInputs.value
    val pa = proguardAggregate.value
    val ra = retrolambdaAggregate.value
    val multiDex = dexMulti.value
    val b = projectLayout.value
    val o = outputLayout.value
    val deps = dependencyClasspath.value
    val debug = apkbuildDebug.value
    val s = streams.value

    implicit val output: Converter = o
    Dex.dexInputs(progOut, in, pa, ra, multiDex, b.dex, deps, b.classesJar, debug(), s)
  }

  val manifestAggregateTaskDef = Def.task {
    Aggregate.Manifest(applicationId.value,
      versionName.value, versionCode.value,
      minSdkVersion.value, targetSdkVersion.value,
      manifestPlaceholders.value, manifestOverlays.value)
  }
  val aaptAggregateTaskDef = Def.task {
    Aggregate.Aapt(builder.value, apkbuildDebug.value(), pseudoLocalesEnabled.value, resConfigs.value, aaptAdditionalParams.value)
  }

  val dexAggregateTaskDef = Def.task {
    Aggregate.Dex(dexInputs.value, dexMaxHeap.value, dexMaxProcessCount.value,
      dexMulti.value, dexMainClassesConfig.value, dexMinimizeMain.value,
      dexInProcess.value, buildToolInfo.value, dexAdditionalParams.value)
  }

  val dexTaskDef = Def.task {
    val bldr = builder.value
    val dexOpts = dexAggregate.value
    val shards = dexShards.value
    val pd = predex.value
    val legacy = dexLegacyMode.value
    val lib = libraryProject.value
    val bin = projectLayout.value
    val o = outputLayout.value
    val d = apkbuildDebug.value
    val s = streams.value

    implicit val output: Converter = o
    if (lib)
      PluginFail("This project cannot dex, it has set 'libraryProject := true'")
    Dex.dex(bldr(s.log), dexOpts, pd, None /* unused, left for compat */, legacy, lib, bin.dex, shards, d(), s)
  }

  val predexTaskDef = Def.task {
    implicit val output: Converter = outputLayout.value
    val layout = projectLayout.value
    val opts = dexAggregate.value
    val inputs = opts.inputs._2
    val multiDex = opts.multi
    val shards = dexShards.value
    val legacy = dexLegacyMode.value
    val skip0 = (proguardInputs.value.proguardCache.toList ++ predexSkip.value).map(
      _.getCanonicalFile).toSet
    val skip = if (!predexRetrolambda.value) skip0 + RetrolambdaSupport.processedJar(layout.dex) else skip0
    val classes = layout.classesJar
    val pg = proguard.value
    val s = streams.value
    val bldr = builder.value(s.log)
    Dex.predex(opts,
      inputs.map(_.getCanonicalFile) filterNot skip,
      multiDex || shards, legacy, classes, pg, bldr, baseDirectory.value, layout.predex, s)
  }

  val proguardInputsTaskDef = Def.task {
    val pa = proguardAggregate.value
    val l = proguardLibraries.value
    val d = dependencyClasspath.value
    val b = bootClasspath.value
    val c = projectLayout.value
    val o = outputLayout.value
    val dbg = apkbuildDebug.value
    val st = streams.value

    implicit val output: Converter = o
    Proguard.proguardInputs(
      (pa.useProguard && !dbg()) || (pa.useProguardInDebug && dbg()),
      pa.proguardOptions, pa.proguardConfig,
      l, d, b, c.classesJar, pa.proguardScala, pa.proguardCache, dbg(), st)
   }

  val resourceShrinkerTaskDef = Def.task {
    val jar = proguard.value
    implicit val output: Converter = outputLayout.value
    val resApk = packageResources.value
    val doShrink = shrinkResources.value
    val layout = projectLayout.value
    val log = streams.value.log
    import com.android.build.gradle.tasks.ResourceUsageAnalyzer
    val procMan = processManifest.value
    if (jar.isDefined && doShrink && !ResourceUsageAnalyzer.TWO_PASS_AAPT) {
      val shrunkResApk = resApk.getParentFile / ("shrunk-" + resApk.getName)
      val resTarget = layout.mergedRes
      val analyzer = new ResourceUsageAnalyzer(
        layout.gen, jar.get, procMan, null, resTarget, null)
      analyzer.analyze()
      analyzer.rewriteResourceZip(resApk, shrunkResApk)
      val unused = analyzer.getUnusedResourceCount
      if (unused > 0) {
        val before = resApk.length
        val after = shrunkResApk.length
        val pct = (before - after) * 100 / before
        log.info(s"Resource Shrinker: $unused unused resources")
        log.info(s"Resource Shrinker: data reduced from ${Packaging.sizeString(before)} to ${Packaging.sizeString(after)}, removed $pct%")
      }
      shrunkResApk
    } else {
      resApk
    }
  }

  val proguardAggregateTaskDef = Def.task {
    Aggregate.Proguard(useProguard.value, useProguardInDebug.value,
      (managedClasspath in AndroidInternal).value, proguardScala.value,
      proguardConfig.value, proguardOptions.value, proguardCache.value)
  }

  val proguardTaskDef: Def.Initialize[Task[Option[File]]] = Def.task {
    val a = proguardAggregate.value
    val bldr = builder.value
    val l = libraryProject.value
    val inputs = proguardInputs.value
    val d = apkbuildDebug.value
    val b = projectLayout.value
    val output = outputLayout.value
    val ra = retrolambdaAggregate.value
    val s = streams.value

    implicit val o: Converter = output
    Proguard.proguard(a, bldr(s.log), l, inputs, d(), b.proguardOut, ra, s)
  }

  case class TestListener(log: Logger) extends ITestRunListener {
    import com.android.ddmlib.testrunner.TestIdentifier
    private var _failures: Seq[TestIdentifier] = Seq.empty
    def failures: Seq[TestIdentifier] = _failures

    type TestMetrics = java.util.Map[String,String]
    override def testRunStarted(name: String, count: Int) {
      log.info("testing %s (tests: %d)" format (name, count))
    }

    override def testStarted(id: TestIdentifier) {
      log.info(s" - %s (%s)" format (id.getTestName, id.getClassName))
    }

    override def testEnded(id: TestIdentifier, metrics: TestMetrics) {
      log.debug("finished: %s (%s)" format (id.getTestName, metrics))
    }

    override def testFailed(id: TestIdentifier, m: String) {
      log.error("failed: %s\n%s" format (id.getTestName, m))

      _failures = _failures :+ id
    }

    override def testRunFailed(msg: String) {
      log.error("Testing failed: " + msg)
    }

    override def testRunStopped(elapsed: Long) {
      log.info("test run stopped (%dms)" format elapsed)
    }

    override def testRunEnded(elapsed: Long, metrics: TestMetrics) {
      log.info("test run finished (%dms)" format elapsed)
      log.debug("run end metrics: " + metrics)
    }

    override def testAssumptionFailure(id: TestIdentifier, m: String) {
      log.error("assumption failed: %s\n%s" format (id.getTestName, m))

      _failures = _failures :+ id
    }

    override def testIgnored(test: TestIdentifier) {
      log.warn("ignored: %s" format test.getTestName)
    }
  }

  val testAggregateTaskDef = Def.task {
    Aggregate.AndroidTest(
      debugIncludesTests.?.value.getOrElse(false),
      (packageT in Test).value,
      instrumentTestRunner.value,
      instrumentTestTimeout.value,
      installTimeout.?.value.getOrElse(0),
      allDevices.?.value.getOrElse(false),
      apkbuildDebug.value(),
      apkDebugSigningConfig.?.value.getOrElse(DebugSigningConfig()),
      dexMaxHeap.?.value.getOrElse("1024m"),
      dexMaxProcessCount.?.value.getOrElse(java.lang.Runtime.getRuntime.availableProcessors),
      (externalDependencyClasspath in AndroidTest).value map (_.data),
      (externalDependencyClasspath in Compile).value map (_.data),
      packagingOptions.?.value.getOrElse(PackagingOptions()),
      libraryProject.value)
  }
  val testTaskDef = Def.task {
    val layout = projectLayout.value

    implicit val output: Converter = outputLayout.value
    val agg = aaptAggregate.value
    val classes = classDirectory.value
    val sdk = sdkPath.value
    val ta = testAggregate.value
    val ma = manifestAggregate.value
    val ra = retrolambdaAggregate.value
    val libs = libraryProjects.value
    val s = streams.value
    val pkg = ma.applicationId
    val minSdk = ma.minSdkVersion
    val targetSdk = ma.targetSdkVersion
    val placeholders = ma.placeholders
    val timeo = ta.instrumentTestTimeout
    val noTestApk = ta.debugIncludesTests
    val runner = ta.instrumentTestRunner
    val clib = ta.externalDependencyClasspathInCompile
    val tlib = ta.externalDependencyClassPathInTest
    val cache = s.cacheDirectory
    val re = ra.enable
    val debug = ta.apkbuildDebug
    val bldr = agg.builder(s.log)
    val all = ta.allDevices

    val testManifest = layout.testManifest
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      generateTestManifest(pkg, layout.processedTestManifest, runner)
    }

    if (!noTestApk) {
      classes.mkdirs()
      val manifest = XML.loadFile(manifestFile)
      val testPackage = manifest.attribute("package").get.head.text
      val instr = manifest \\ "instrumentation"
      val instrData = (instr map { i =>
        (i.attribute(ANDROID_NS, "name") map (_.head.text),
        i.attribute(ANDROID_NS, "targetPackage") map (_.head.text))
      }).headOption
      val trunner = instrData flatMap (
        _._1) getOrElse runner
      val tpkg = instrData flatMap (_._2) getOrElse pkg
      val processedManifest = layout.processedManifest
      // profiling and functional test? false for now
      bldr.mergeManifestsForTestVariant(testPackage, minSdk, targetSdk,
        tpkg, trunner, false, false, "androidTest", manifestFile, libs.asJava,
        (placeholders: Map[String,Object]).asJava, processedManifest.getAbsoluteFile,
        cache / "processTestManifest")
      val options = new DexOptions {
        override def getJumboMode = false
        override def getPreDexLibraries = false
        override def getJavaMaxHeapSize = ta.dexMaxHeap
        override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
        override def getMaxProcessCount = ta.dexMaxProcessCount
        override def getDexInProcess = false
        override def getKeepRuntimeAnnotatedClasses = true
        override def getAdditionalParameters = List.empty.asJava
      }
      val rTxt = layout.testRTxt
      val dex = layout.testDex
      dex.mkdirs()
      val res = layout.testResApk
      val apk = layout.testApk

      if (!rTxt.exists) rTxt.createNewFile()
      Resources.aapt(bldr, processedManifest, testPackage,
        agg.additionalParams, agg.resConfigs, libs, lib = false, debug = debug, pseudoLocalize = agg.pseudoLocalize, layout.testRes,
        layout.testAssets, res, classes, null, layout.aaptTemp, s.log)

      val deps = tlib filterNot (clib.contains)
      val tmp = cache / "test-dex"
      tmp.mkdirs()
      val inputs = if (re && RetrolambdaSupport.isAvailable) {
        RetrolambdaSupport(classes, ta.classesJar +: deps, ra.classpath, ra.bootClasspath, s)
      } else {
        Seq(classes) ++ deps
      }
      // dex doesn't support --no-optimize, see
      // https://android.googlesource.com/platform/tools/base/+/9f5a5e1d91a489831f1d3cc9e1edb850514dee63/build-system/gradle-core/src/main/groovy/com/android/build/gradle/tasks/Dex.groovy#219
      bldr.convertByteCode(inputs.asJava,
        dex, false, null, options, SbtProcessOutputHandler(s.log))

      Packaging.packageApk(apk, bldr, minSdk.toInt, processedManifest, res,
        ta.debugSigningConfig, layout.testAssets, List(dex),
        layout.testSources / "fake-no-jni-doesnt-exist-sbt-android",
        layout.testSources / "fake-no-javaresource-doesnt-exist-sbt-android",
        Set.empty, debug = true, s)

      s.log.debug("Installing test apk: " + apk)

      def install(device: IDevice) {
        installPackage(apk, ta.installTimeout, sdk, device, s.log)
      }

      if (all)
        Commands.deviceList(sdk, s.log).par foreach install
      else
        Commands.targetDevice(sdk, s.log) foreach install

      try {
        runTests(sdk, testPackage, s, trunner, timeo, all)
      } finally {
        def uninstall(device: IDevice) {
          uninstallPackage(None, testPackage, sdk, device, s.log)
        }

        if (all)
          Commands.deviceList(sdk, s.log).par foreach uninstall
        else
          Commands.targetDevice(sdk, s.log) foreach uninstall
      }
    } else {
      runTests(sdk, pkg, s, runner, timeo, all)
    }
    ()
  }

  val testOnlyTaskDef: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val layout = projectLayout.value
    implicit val output: Converter = outputLayout.value
    val noTestApk = debugIncludesTests.?.value.getOrElse(false)
    val pkg = applicationId.value
    val all = allDevices.?.value.getOrElse(false)
    val s = streams.value
    val timeo = instrumentTestTimeout.value
    val sdk = sdkPath.value
    val runner = instrumentTestRunner.value
    val testManifest = layout.testManifest
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      generateTestManifest(pkg, layout.processedTestManifest, runner)
    }
    val manifest = XML.loadFile(manifestFile)
    val testPackage = manifest.attribute("package").get(0).text
    val testSelection = Def.spaceDelimited().parsed.mkString(" ")
    val trunner = if(noTestApk) runner else {
      val instr = manifest \\ "instrumentation"
      (instr map { i =>
        (i.attribute(ANDROID_NS, "name") map (_.head.text),
          i.attribute(ANDROID_NS, "targetPackage") map (_.head.text))
      }).headOption flatMap ( _._1) getOrElse runner
    }
    runTests(sdk, testPackage, s, trunner, timeo, all, Some(testSelection))
  }

  private def generateTestManifest(pkg: String, manifestOut: File,
    runner: String) = {
    val TOOLS_PREFIX = "tools"
    val tns = NamespaceBinding(TOOLS_PREFIX, TOOLS_NS, TopScope)
    val vn = new PrefixedAttribute(ANDROID_PREFIX, "versionName", "1.0", Null)
    val vc = new PrefixedAttribute(ANDROID_PREFIX, "versionCode", "1", vn)
    val pkgAttr = new UnprefixedAttribute("package",
      pkg + ".instrumentTest", vc)
    val ns = NamespaceBinding(ANDROID_PREFIX, ANDROID_NS, tns)

    val minSdk = new PrefixedAttribute(
      ANDROID_PREFIX, "minSdkVersion", "3", Null)
    val usesSdk = new Elem(null, "uses-sdk", minSdk, TopScope, minimizeEmpty = true)
    val runnerlib = new PrefixedAttribute(
      ANDROID_PREFIX, "name", TEST_RUNNER_LIB, Null)
    val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope, minimizeEmpty = true)
    val app = new Elem(null,
      APPLICATION_TAG, Null, TopScope, minimizeEmpty = false, usesLib)
    val name = new PrefixedAttribute(
      ANDROID_PREFIX, "name", runner, Null)
    val instrumentation = new Elem(null, INSTRUMENTATION_TAG, name, TopScope, minimizeEmpty = true)
    val manifest = new Elem(null,
      "manifest", pkgAttr, ns, minimizeEmpty = false, usesSdk, app, instrumentation)

    manifestOut.getParentFile.mkdirs()
    XML.save(manifestOut.getAbsolutePath, manifest, "utf-8", xmlDecl = true, null)
    manifestOut
  }

  private def runTests(sdk: String, testPackage: String,
      s: TaskStreams, runner: String, timeo: Int, all: Boolean, testSelection: Option[String] = None) {
    import com.android.ddmlib.testrunner.InstrumentationResultParser
    val intent = testPackage + "/" + runner
    def execute(d: IDevice): Unit = {
      val listener = TestListener(s.log)
      val p = new InstrumentationResultParser(testPackage, listener)
      val receiver = new Commands.ShellLogging(l => p.processNewLines(Array(l)))
      val selection = testSelection map { "-e " + _ } getOrElse ""
      val command = "am instrument -r -w %s %s" format(selection, intent)
      s.log.info(s"Testing on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
      s.log.debug("Executing [%s]" format command)
      withDdmTimeout(timeo) {
        d.executeShellCommand(command, receiver)
      }

      s.log.debug("instrument command executed")

      if (listener.failures.nonEmpty) {
        PluginFail("Tests failed: " + listener.failures.size + "\n" +
          (listener.failures map (" - " + _)).mkString("\n"))
      }
    }
    if (all)
      Commands.deviceList(sdk, s.log).par foreach execute
    else
      Commands.targetDevice(sdk, s.log) foreach execute

  }

  val cleanTaskDef = Def.task {
    val s = streams.value
    def execute(d: IDevice): Unit = {
      val receiver = new Commands.ShellLogging(l => s.log.info(l))
      val pkg = applicationId.value
      val command = "pm clear %s" format pkg
      s.log.info(s"Clearing data for $pkg on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
      s.log.debug("Executing [%s]" format command)
      d.executeShellCommand(command, receiver)
    }
    val all = allDevices.value
    val k = sdkPath.value
    if (all)
      Commands.deviceList(k, s.log).par foreach execute
    else
      Commands.targetDevice(k, s.log) foreach execute
  }

  def runTaskDef(debug: Boolean): Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val k = sdkPath.value
    val l = projectLayout.value
    val p = applicationId.value
    val s = streams.value
    val all = allDevices.value
    val isLib = libraryProject.value
    implicit val output: Converter = outputLayout.value
    if (isLib)
      PluginFail("This project is not runnable, it has set 'libraryProject := true")

    val manifestXml = l.processedManifest
    val m = XML.loadFile(manifestXml)
    // if an arg is specified, try to launch that
    parsers.activityParser.parsed orElse parsers.findMainActivities(m).headOption.map(activity => {
      val name = activity.attribute(ANDROID_NS, "name").get.head.text
      "%s/%s" format (p, if (name.indexOf(".") == -1) "." + name else name)
    }) match {
      case Some(intent) =>
        val receiver = new Commands.ShellLogging(l => s.log.info(l))
        val command = "am start %s -n %s" format (if (debug) "-D" else "", intent)
        def execute(d: IDevice): Unit = {
          s.log.info(s"Running on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
          s.log.debug("Executing [%s]" format command)
          d.executeShellCommand(command, receiver)
          s.log.debug("run command executed")
        }
        if (all)
          Commands.deviceList(k, s.log).par foreach execute
        else
          Commands.targetDevice(k, s.log) foreach execute
      case None =>
        PluginFail(
          "No activity found with action 'android.intent.action.MAIN'")
    }

    ()
  }

  val KB = 1024 * 1.0
  val MB = KB * KB
  val installTaskDef = Def.task {
    val p = packageT.value
    val l = libraryProject.value
    val k = sdkPath.value
    val timeo = installTimeout.value
    val all = allDevices.value
    val s = streams.value

    if (!l) {
      def execute(d: IDevice): Unit = {
        val cacheName = "install-" + URLEncoder.encode(
          d.getSerialNumber, "utf-8")
        FileFunction.cached(s.cacheDirectory / cacheName, FilesInfo.hash) { in =>
          s.log.info(s"Installing to ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
          installPackage(p, timeo, k, d, s.log)
          in
        }(Set(p))
      }
      if (all)
        Commands.deviceList(k, s.log).par foreach execute
      else
        Commands.targetDevice(k, s.log) foreach execute
    }
  }

  def withDdmTimeout[A](timeo: Int)(thunk: => A): A = {
    val timeout = DdmPreferences.getTimeOut
    DdmPreferences.setTimeOut(timeo)
    try {
      thunk
    } finally {
      DdmPreferences.setTimeOut(timeout)
    }
  }
  def installPackage(apk: File, timeo: Int, sdkPath: String, device: IDevice, log: Logger) {
    logRate(log, "[%s] Install finished:" format apk.getName, apk.length) {
      withDdmTimeout(timeo) {
        Try(device.installPackage(apk.getAbsolutePath, true)) match {
          case scala.util.Failure(err) =>
            PluginFail("Install failed: " + err.getMessage, err)
          case scala.util.Success(_) =>
        }
      }
    }
  }

  def logRate[A](log: Logger, msg: String, size: Long)(f: => A): A = {
    val start = System.currentTimeMillis
    val a = f
    val end = System.currentTimeMillis
    val secs = (end - start) / 1000d
    val rate = size/KB/secs
    val mrate = if (rate > MB) rate / MB else rate
    log.info("%s %s in %.2fs. %.2f%s/s" format (msg, Packaging.sizeString(size), secs, mrate,
      if (rate > MB) "MB" else "KB"))
    a
  }

  def uninstallPackage(cacheDir: Option[File], packageName: String, sdkPath: String, device: IDevice, log: Logger) {
    val cacheName = "install-" + URLEncoder.encode(
      device.getSerialNumber, "utf-8")
    cacheDir foreach { c =>
      FileFunction.cached(c / cacheName, FilesInfo.hash) { in =>
        Set.empty
      }(Set.empty)
    }
    log.info(s"Uninstalling from ${device.getProperty(IDevice.PROP_DEVICE_MODEL)} (${device.getSerialNumber})...")
    Option(device.uninstallPackage(packageName)) map { err =>
      PluginFail("[%s] Uninstall failed: %s" format (packageName, err))
    } getOrElse {
      log.info("[%s] Uninstall finished" format packageName)
    }
  }
  val uninstallTaskDef = Def.task {
    val k = sdkPath.value
    val p = applicationId.value
    val s = streams.value
    val all = allDevices.value

    def uninstall(device: IDevice): Unit = {
      uninstallPackage(Some(s.cacheDirectory), p, k, device, s.log)
    }

    if (all)
      Commands.deviceList(k, s.log).par foreach uninstall
    else
      Commands.targetDevice(k, s.log) foreach uninstall
  }

  def loadLibraryReferences(b: File, props: Properties, prefix: String = "")(implicit m: BuildOutput.Converter): Seq[AutoLibraryProject] = {
    val p = props.asScala
    p.keys
      .collect { case k if k.startsWith("android.library.reference") => k }
      .toList
      .sortWith { (a,b) => a < b }
      .flatMap { k => AutoLibraryProject(b/p(k)) +: loadLibraryReferences(b/p(k), loadProperties(b/p(k)), k) }
      .distinct
  }

  val unmanagedJarsTaskDef = Def.task {
    val u = unmanagedJars.value
    val b = baseDirectory.value
    val t = buildToolInfo.value
    val rs = rsSupportMode.value
    val l = libraryProjects.value
    val _ = streams.value

    val rsJars =
      if (rs) SdkLayout.renderscriptSupportLibs(t).map(f =>
        Attributed.blank(f.getCanonicalFile))
      else Seq()

    // remove scala-library if present
    // add all dependent library projects' classes.jar files
    (u ++ rsJars ++ (l filterNot {
        case _: ApkLibrary         => true
//        case _: AarLibrary         => true
        case _: AutoLibraryProject => true
        case _ => false
    } map {
      case a@AarLibrary(_) => Attributed.blank(a.getJarFile.getCanonicalFile).put(moduleID.key, a.moduleID)
      case p => Attributed.blank(p.getJarFile.getCanonicalFile)
    }) ++ (for {
      d <- l filterNot {
        case _: AarLibrary => true
        case _ => false
      }
      j <- d.getLocalJars.asScala
    } yield Attributed.blank(j.getCanonicalFile)) ++ (for {
      d <- l collect {
        case l: AarLibrary => l
      }
      j <- d.getLocalJars.asScala
    } yield Attributed.blank(j.getCanonicalFile).put(moduleID.key, d.moduleID)) ++ (for {
      d <- Seq(b / "libs", b / "lib")
      j <- (d * "*.jar").get
    } yield Attributed.blank(j.getCanonicalFile)) ) filter { c =>
      !c.data.getName.startsWith("scala-library") && c.data.isFile
    }
  }
}

object FileOnlyFilter extends FileFilter {
  override def accept(pathname: sbt.File) = pathname.isFile
}
