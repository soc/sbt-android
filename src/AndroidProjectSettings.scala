package android

import java.io.File

import android.BuildOutput.Converter
import android.Keys._
import android.Keys.Internal._
import com.android.tools.lint.LintCliFlags
//import com.hanhuy.sbt.bintray.UpdateChecker
import Tasks._
import com.android.builder.core.{AndroidBuilder, LibraryRequest}
import com.android.builder.sdk.{DefaultSdkLoader, SdkLibData}
import com.android.ide.common.process.DefaultProcessExecutor
import com.android.repository.Revision
import com.android.sdklib.IAndroidTarget

import scala.collection.JavaConverters._
import scala.util.Try
import scala.xml.XML
import sbt._
import sbt.Keys._
//import parsers.sbinaryFileFormat
import Resources.ANDROID_NS

/**
  * @author pfnguyen
  */
trait AndroidProjectSettings extends AutoPlugin {

  override def projectSettings: Seq[Setting[_]] = {
    // only set the property below if this plugin is actually used
    // this property is a workaround for bootclasspath messing things
    // up and causing full-recompiles
    System.setProperty("xsbt.skip.cp.lookup", "true")
    allPluginSettings
  }

  private def allPluginSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    dependencyClasspath := {
      Def.taskDyn {
        val dcp = dependencyClasspath.value
        if (debugIncludesTests.?.value.getOrElse(false) && apkbuildDebug.value()) Def.task {
          (dcp ++ (externalDependencyClasspath in AndroidTest).value).distinct
        } else Def.task {
          dcp.distinct
        }
      }
    }.value,
    compile := {
      val c       = compile.value
      val ld      = lintDetectors.value
      val f       = lintFlags.value
      val en      = lintEnabled.value
      val strict  = lintStrict.value
      val layout  = projectLayout.value
      val o       = outputLayout.value
      val classes = classDirectory.value
      val minSdk  = minSdkVersion.value
      val tgtSdk  = targetSdkVersion.value
      val s       = streams.value


      checkVersion("minSdkVersion", minSdk)
      checkVersion("targetSdkVersion", tgtSdk)
      implicit val output: Converter = o
      if (en)
        AndroidLint(layout, classes, f, ld, strict, minSdk, tgtSdk, s)
      c
    },
    sourceManaged               := projectLayout.value.gen,
    unmanagedSourceDirectories  := {
      val l = projectLayout.value
      Defaults.makeCrossSources(l.scalaSource, l.javaSource, scalaBinaryVersion.value, crossPaths.value)
    },
    // was necessary prior to 0.13.8 to squelch "No main class detected" warning
    //packageOptions in packageBin := Package.JarManifest(new java.util.jar.Manifest) :: Nil,
    packageConfiguration in packageBin := {
      val c = (packageConfiguration in packageBin).value
      val _ = baseDirectory.value
      val l = libraryProject.value
      val p = projectLayout.value
      val o = outputLayout.value

        // remove R.java generated code from library projects
        implicit val output: Converter = o
        val sources = if (l) {
          c.sources filter {
            case (f,n) => !f.getName.matches("R\\W+.*class")
          }
        } else {
          c.sources
        }
        new Package.Configuration(sources, p.classesJar, c.options)
    },
    publishArtifact in packageBin := false,
    resourceDirectory  := projectLayout.value.resources,
    scalaSource        := projectLayout.value.scalaSource,
    javaSource         := projectLayout.value.javaSource,
    unmanagedJars      := unmanagedJarsTaskDef.value,
    // doesn't work properly yet, not for intellij integration
    //managedClasspath  <<= managedClasspathTaskDef,
    sourceGenerators   := sourceGenerators.value ++ List(
      rGenerator.taskValue,
      viewHoldersGenerator.taskValue,
      typedResourcesGenerator.taskValue,
      aidl.taskValue,
      buildConfigGenerator.taskValue,
      renderscript.taskValue,
      cleanForR.taskValue,
      Def.task {
        (apklibs.value ++ autolibs.value flatMap { l =>
          (l.layout.javaSource ** "*.java").get ++
            (l.layout.scalaSource ** "*.scala").get
        }) map (_.getAbsoluteFile)
      }.taskValue
    ),
    packageT          := (packageT dependsOn compile).value,
    javacOptions      := {
      val o = javacOptions.value
      val boot = bootClasspath.value
      val debug = apkbuildDebug.value
      val re = retrolambdaEnabled.value

      // users will want to call clean before compiling if changing debug
      val debugOptions = if (debug()) Seq("-g") else Seq.empty
      val bcp = boot.map(_.data) mkString File.pathSeparator
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      o ++ (if (!re) Seq("-bootclasspath" , bcp) else Seq("-Xbootclasspath/a:" + bcp)) ++ debugOptions
    },
    javacOptions in doc := {
      (javacOptions in doc).value.flatMap { opt =>
        if (opt.startsWith("-Xbootclasspath/a:"))
          Seq("-bootclasspath", opt.substring(opt.indexOf(":") + 1))
        else if (opt == "-g")
          Seq.empty
        else Seq(opt)
      }.foldRight(List.empty[String]) {
        (x, a) => if (x != "-target") x :: a else a.drop(1)
      }
    },
    scalacOptions     := {
      val o = scalacOptions.value
      val boot = bootClasspath.value

      // scalac has -g:vars by default
      val bcp = boot.map(_.data) mkString File.pathSeparator
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    exportJars         := false,
    scalacOptions in console    := Seq.empty
  )) ++ inConfig(Android) (Classpaths.configSettings ++ Seq(
    // fix for sbt 0.13.11
    artifactPath in packageBin  := (artifactPath in (Compile,packageBin)).value,
    flavors                     := Map.empty,
    buildTypes                  := Map.empty,
    variantConfiguration        := ((None, None)),
    pluginSettingsLoaded        := {
      if (pluginSettingsLoaded.?.value.isDefined)
        fail(s"androidBuild has been applied to project ${thisProject.value.id} more than once")
      true
    },
    lint                        := {
      implicit val output: Converter = outputLayout.value
      AndroidLint(projectLayout.value, (classDirectory in Compile).value,
        lintFlags.value, lintDetectors.value, lintStrict.value,
        minSdkVersion.value, targetSdkVersion.value, streams.value)
    },
    lintFlags                := {
      val flags = new LintCliFlags
      flags.setQuiet(true)
      flags
    },
    lintStrict                  := false,
    lintEnabled                 := true,
    lintDetectors               := AndroidLint.lintDetectorList,
    // support for android:test
    classDirectory              := (classDirectory in Test).value,
    sourceDirectory             := projectLayout.value.testSources,
    managedSources              := Nil,
    unmanagedSourceDirectories  := projectLayout(l => Set(l.testSources, l.testJavaSource, l.testScalaSource).toSeq).value,
    unmanagedSources            := Defaults.collectFiles(
      unmanagedSourceDirectories,
      includeFilter in (Compile,unmanagedSources),
      excludeFilter in (Compile,unmanagedSources)).value,
    sources := Classpaths.concat(unmanagedSources, managedSources).value,
    // productX := Nil is a necessity to use Classpaths.configSettings
    exportedProducts         := Nil,
    products                 := Nil,
    classpathConfiguration   := Compile,
    // end for Classpaths.configSettings
    // hack since it doesn't take in dependent project's libs
    dependencyClasspath      :=
      Def.taskDyn {
        val cp = (dependencyClasspath in Runtime).value
        val layout = projectLayout.value
        implicit val out: Converter = outputLayout.value
        if (apkbuildDebug.value() && debugIncludesTests.?.value.getOrElse(false)) Def.task {
          val s = streams.value
          val tcp = (externalDependencyClasspath in AndroidTest).value
          cp foreach { a =>
            s.log.debug(s"${a.data.getName} => ${a.get(configuration.key)}: ${a.get(moduleID.key)}")
          }
          val newcp = cp ++ tcp
          newcp.distinct.filterNot(_.data == layout.classesJar)
        } else Def.task {
          cp.distinct.filterNot(_.data == layout.classesJar)
        }
      }.value,
    updateCheck              := {
      val _ = streams.value.log
      /* fixme
      UpdateChecker("pfn", "sbt-plugins", "sbt-android") {
        case Left(t) =>
          log.debug("Failed to load version info: " + t)
        case Right((versions, current)) =>
          log.debug("available versions: " + versions)
          log.debug("current version: " + BuildInfo.version)
          log.debug("latest version: " + current)
          if (versions(BuildInfo.version)) {
            if (BuildInfo.version != current) {
              log.warn(
                s"UPDATE: A newer sbt-android is available:" +
                  s" $current, currently running: ${BuildInfo.version}")
            }
          }
      }*/
    },
    updateCheckSdk           := SdkInstaller.updateCheckSdkTaskDef.value,
    showSdkProgress          := true,
    antLayoutDetector        := {
      val log = streams.value.log
      val prj = thisProjectRef.value.project
      projectLayout.value match {
        case a: ProjectLayout.Ant if a.manifest.exists =>
          log.warn(s"Detected an ant-style project layout in $prj;")
          log.warn("  this format has been deprecated in favor of modern layouts")
          log.warn("  If this is what you want, set 'antLayoutDetector in Android := ()'")
        case _ =>
      }
    },
    transitiveAndroidLibs     := true,
    transitiveAndroidWarning  := true,
    testAarWarning            := true,
    autolibs                  := autolibsTaskDef.value,
    apklibs                   := apklibsTaskDef.value,
    localAars                 := Nil,
    aars                      := aarsTaskDef.value,
    transitiveAars            := Nil,
    // TODO remove producing apklib in a future version
    apklibArtifact            := normalizedName { n => Artifact(n, "apklib", "apklib") }.value,
    packageApklib             := packageApklibTaskDef.value,
    mappings in packageApklib := packageApklibMappings.value,
    aaptAggregate             := aaptAggregateTaskDef.value,
    aaptAdditionalParams      := Nil,
    pseudoLocalesEnabled      := false,
    aaptPngCrunch             := true,
    aapt9PngCrunch            := true,
    cleanForR                 := {
        val _ = rGenerator.value
        val l = projectLayout.value
        val o = outputLayout.value
        val d = (classDirectory in Compile).value
        val s = streams.value

        implicit val output: Converter = o
        FileFunction.cached(s.cacheDirectory / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
          if (in.nonEmpty) {
            s.log.info("Rebuilding all classes because R.java has changed")
            IO.delete(d)
          }
          in
        }(Set((l.generatedSrc ** "R.java").get: _*))
        Seq.empty[File]
    },
    buildConfigGenerator     := buildConfigGeneratorTaskDef.value,
    buildConfigOptions       := {
      val pkg = applicationId.value
      val (buildType,flavor) = variantConfiguration.value
      List(
        ("String", "BUILD_TYPE", s""""${buildType getOrElse ""}""""),
        ("String", "FLAVOR", s""""${flavor getOrElse ""}""""),
        ("String", "APPLICATION_ID", s""""$pkg"""")
      ) ++
        versionName.value.toList.map(n => ("String", "VERSION_NAME", s""""$n"""")) ++
        versionCode.value.toList.map (c => ("int", "VERSION_CODE", c.toString)
        )
    },
    resConfigs               := Nil,
    resValues                := Nil,
    resValuesGenerator       := resValuesGeneratorTaskDef.value,
    rGenerator               := rGeneratorTaskDef.value,
    rGenerator               := (rGenerator dependsOn renderscript).value,
    ndkJavah                 := ndkJavahTaskDef.value,
    ndkAbiFilter             := Nil,
    ndkEnv                   := Nil,
    ndkArgs                  := Nil,
    ndkBuild                 := ndkBuildTaskDef.value,
    aidl                     := aidlTaskDef.value,
    rsTargetApi              := {
      val p = properties.value
      val m = minSdkVersion.value

      Option(p.getProperty("renderscript.target")).getOrElse(m)
    },
    rsSupportMode            := properties { p =>
      Try(p.getProperty("renderscript.support.mode").toBoolean).getOrElse(false)
    }.value,
    rsOptimLevel             := 3,
    renderscript             := renderscriptTaskDef.value,
    localProjects            := {
      val b = baseDirectory.value
      val p = properties.value
      val o = outputLayout.value

      loadLibraryReferences(b, p)(o)
    },
    libraryProjects          := localProjects.value ++ apklibs.value ++ aars.value,
    libraryProject           := properties { p =>
      Option(p.getProperty("android.library")) exists { _.equals("true") } }.value,
    checkAars                := checkAarsTaskDef.value,
    collectResourcesAggregate:= collectResourcesAggregateTaskDef.value,
    manifestAggregate        := manifestAggregateTaskDef.value,
    ndkbuildAggregate        := ndkbuildAggregateTaskDef.value,
    retrolambdaAggregate     := retrolambdaAggregateTaskDef.value,
    platformJars             := platform { p =>
      val t = p.getTarget
      (t.getPath(IAndroidTarget.ANDROID_JAR),
        t.getOptionalLibraries.asScala map (_.getJar.getAbsolutePath))
    }.value,
    projectLayout            := ProjectLayout(baseDirectory.value, Some(target.value)),
    outputLayout             := { layout => new BuildOutput.AndroidOutput(layout) },
    manifestPath             := projectLayout(l => l.manifest).value,
    properties               := projectLayout(l => loadProperties(l.base)).value,
    mergeManifests           := true,
    manifestPlaceholders     := Map.empty,
    manifestOverlays         := Seq.empty,
    processManifest          := processManifestTaskDef/*.storeAs(processManifest)*/.value, // fixme
    manifest                 := (manifestPath map { m =>
      if (!m.exists)
        fail("cannot find AndroidManifest.xml: " + m)
      XML.loadFile(m)
    }).value,
    versionCode              := manifest.value.attribute(ANDROID_NS, "versionCode").map(_.head.text.toInt),
    versionName              := manifest.value.attribute(ANDROID_NS, "versionName").map(_.head.text) orElse Some(version.value),
    packageForR              := manifest.map(m => m.attribute("package").get.head.text).value,
    applicationId            := "", // fixme
    targetSdkVersion         := {
      val m = manifest.value
      val usesSdk = m \ "uses-sdk"
      val v = String.valueOf(platformApi.value)
      if (usesSdk.isEmpty) v else
        usesSdk.head.attribute(ANDROID_NS, "targetSdkVersion").fold(v) { _.head.text }
    },
    minSdkVersion            := {
      val m = manifest.value
      val defmin = 19
      val tgt = Try(targetSdkVersion.value.toInt).getOrElse(defmin)
      val usemin = math.min(defmin, tgt).toString
      val usesSdk = m \ "uses-sdk"
      if (usesSdk.isEmpty) usemin else
        usesSdk.head.attribute(ANDROID_NS, "minSdkVersion").fold(usemin) { _.head.text }
    },
    retrolambdaEnabled       := false,
    typedResources           := autoScalaLibrary.value,
    typedResourcesIds        := true,
    typedResourcesFull       := true,
    typedResourcesAar        := false,
    typedViewHolders         := autoScalaLibrary.value,
    typedResourcesIgnores    := Seq.empty,
    typedResourcesGenerator  := typedResourcesGeneratorTaskDef.value,
    viewHoldersGenerator     := viewHoldersGeneratorTaskDef.value,
    extraResDirectories         := Nil,
    extraAssetDirectories       := Nil,
    renderVectorDrawables    := true,
    collectResources         := collectResourcesTaskDef.value,
    collectResources         := (collectResources dependsOn renderscript).value,
    collectResources         := (collectResources dependsOn resValuesGenerator).value,
    collectResources         := (collectResources dependsOn checkAars).value,
    collectProjectJni        := collectProjectJniTaskDef.value,
    collectProjectJni        := (collectProjectJni dependsOn renderscript).value,
    collectJni               := collectJniTaskDef.value,
    proguardOptions          := Nil,
    apkbuildDebug            := MutableSetting(true),
    setDebug                 := apkbuildDebug.value(true),
    setRelease               := apkbuildDebug.value(false),
    sdkPath                  := SdkInstaller.sdkPath(sLog.value, properties.value),
    ndkPath                  := {

      val _     = thisProject.value
      val props = properties.value
      val spath = sdkPath.value
      val log   = sLog.value

      val cache = SdkLayout.androidNdkHomeCache
      def storePathInCache(path: String): Unit = {
        cache.getParentFile.mkdirs()
        IO.writeLines(cache, path :: Nil)
      }
      def propertiesSetting = Option(props getProperty "ndk.dir").map("'ndk.dir' property" -> _)
      def envVarSetting = Option(System getenv "ANDROID_NDK_HOME").map("'ANDROID_NDK_HOME' env var" -> _)
      def sdkBundleFallback = Some(SdkLayout.ndkBundle(spath)).filter(_.isDirectory).map("ndk-bundle" -> _.absolutePath)

      val alternatives = propertiesSetting ++ envVarSetting ++ sdkBundleFallback
      val foundNdk = alternatives.view.map {
        case (desc, f) if file(f).isDirectory => Some(f)
        case (desc, _) =>
          log.warn(s"$desc does not point to a valid ndk installation")
          None
      }.find(_.isDefined).flatten
      foundNdk.foreach(storePathInCache)
      foundNdk orElse SdkLayout.sdkFallback(cache)
    },
    ilogger                  := {
      val logger = SbtILogger()

      {
        l =>
          logger(l)
          logger
      }
    },
    buildToolsVersion        := None,
    sdkLoader                := DefaultSdkLoader.getLoader(sdkManager.value.getLocation),
    libraryRequests          := Nil,
    builder                  := {
      val ldr  = sdkLoader.value
      val _sm  = sdkManager.value
      val n    = name.value
      val l_   = ilogger.value
      val _b   = buildToolInfo.value
      val t    = platform.value
      val reqs = libraryRequests.value
      val log  = sLog.value



      val l = l_(log)
      val l2 = SbtAndroidErrorReporter()
      val bldr = new AndroidBuilder(n, "sbt-android",
        new DefaultProcessExecutor(l), SbtJavaProcessExecutor, l2, l, false)
      val sdkInfo = ldr.getSdkInfo(l)
      bldr.setSdkInfo(sdkInfo)
      bldr.setTargetInfo(t)
      bldr.setLibraryRequests(reqs.map { case ((nm, required)) =>
        new LibraryRequest(nm, required) }.asJava)

      { logger =>
        l_(logger)
        l2(logger)
        bldr
      }
    },
    bootClasspath            := builder.value(sLog.value).getBootClasspath(false).asScala map Attributed.blank,
    sdkManager               := SdkInstaller.sdkManager(file(sdkPath.value), showSdkProgress.value, sLog.value),
    buildToolInfo            := {
      val slog = sLog.value
      val ind = SbtAndroidProgressIndicator(slog)
      val sdkHandler = sdkManager.value
      val showProgress = showSdkProgress.value
      val r = buildToolsVersion.value map { version =>
        val bti = SdkInstaller.retryWhileFailed("fetch build tool info", slog) {
          sdkHandler.getBuildToolInfo(Revision.parseRevision(version), ind)
        }
        SdkInstaller.autoInstallPackage(sdkHandler, "build-tools;", version, "build-tools " + version, showProgress, slog,
          _ => bti == null).fold(bti)(_ => sdkHandler.getBuildToolInfo(Revision.parseRevision(version), ind))
      } getOrElse {
        val tools = SdkInstaller.retryWhileFailed("determine latest build tools", slog)(
          sdkHandler.getLatestBuildTool(ind, false)
        )
        SdkInstaller.autoInstall(sdkHandler, "latest build-tools", "build-tools;", showProgress, slog, _ => tools == null) { pkgs =>
          val buildTools = pkgs.keys.toList.collect {
            case k if k.startsWith("build-tools;") => pkgs(k)
          }
          buildTools.sorted(SdkInstaller.packageOrder).dropWhile(_.getVersion.getPreview > 0).headOption
        }.fold {
          sLog.value.debug("Using Android build-tools: " + tools)
          if (tools == null)
            PluginFail("Unable to detect installed Android build-tools")
          tools
        } { _ =>
          sdkHandler.getLatestBuildTool(ind, false)
        }
      }
      if (r == null)
        PluginFail("Android SDK installation registry failed to update, restart SBT")
      r
    },
    platformTarget          := {
      val p = properties.value
      Option(p.getProperty("target")) orElse {
        sLog.value.warn("`platformTarget` not set, automatically detecting latest...")
        val plat = SdkInstaller.platforms(sdkManager.value, showSdkProgress.value).headOption
        plat.foreach { t =>
          sLog.value.warn(s"""Using `platformTarget := "$t"`""")
          val gen = baseDirectory.value / "z-platform.sbt"
          IO.writeLines(gen,
            "// AUTOMATICALLY GENERATED FILE, REPLACE BY SETTING platformTarget IN build.sbt" ::
              "" ::
              s"""platformTarget := "$t"""" :: Nil)
        }
        plat
      } getOrElse "android-24"
    },
    platformApi             := platform.value.getTarget.getVersion.getApiLevel,
    platform                := {
      val targetHash = platformTarget.value
      val slog = sLog.value
      val sdkHandler = sdkManager.value
      AndroidGlobalPlugin.platformTarget(targetHash, sdkHandler, showSdkProgress.value, slog)
      val logger = ilogger.value(slog)
      sdkLoader.value.getTargetInfo(
        targetHash, buildToolInfo.value.getRevision, logger, SdkLibData.dontDownload)
    },
    m2repoCheck        := {
      val manager = sdkManager.value
      val libs = libraryDependencies.value
      val slog = sLog.value
      val showProgress = showSdkProgress.value
      val gmsOrgs = Set("com.google.android.gms",
        "com.google.android.support.wearable",
        "com.google.android.wearable")
      val supportOrgs = Set("com.android.support", "com.android.databinding")
      val (needSupp, needGms) = libs.foldLeft((false,false)) { case ((supp, gms), mid) =>
        (supp || supportOrgs(mid.organization), gms || gmsOrgs(mid.organization))
      }
      if (needSupp || needGms) {
        SdkInstaller.autoInstallPackage(manager, "extras;android;",
          "m2repository", "android support repository", showProgress, slog,
          !_.contains("extras;android;m2repository") && needSupp)
        SdkInstaller.autoInstallPackage(manager,
          "extras;google;", "m2repository", "google play services repository", showProgress, slog,
          !_.contains("extras;google;m2repository") && needGms)
      }
    }
  )) ++ Seq(
    autoScalaLibrary   := {
      ((scalaSource in Compile).value ** "*.scala").get.nonEmpty ||
        (managedSourceDirectories in Compile).value.exists(d =>
          (d ** "*.scala").get.nonEmpty)
    },
    // make streams dependOn because coursier replaces `update`
    streams in update  := ((streams in update) dependsOn m2repoCheck).value,
    crossPaths         := autoScalaLibrary.value,
    resolvers         ++= sdkPath { p =>
      Seq(SdkLayout.googleRepository(p), SdkLayout.androidRepository(p))
    }.value,
    cleanFiles         += projectLayout.value.bin,
    exportJars         := true,
    unmanagedBase      := projectLayout.value.libs,
    watchSources      ++= Def.task {
      val filter = new SimpleFileFilter({ f =>
        f.isFile && Character.isJavaIdentifierStart(f.getName.charAt(0))
      })
      val layout = projectLayout.value
      val extras = extraResDirectories.value.map(_.getCanonicalFile).distinct
      (layout.testSources +: layout.jni +: layout.res +: extras) flatMap { path =>
        (path ** filter).get }
    }.value
  )
}
