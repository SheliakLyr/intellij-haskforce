package com.haskforce.cabal.query

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.{Project, ProjectManager}
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiFileFactory}
import com.intellij.psi.tree.IElementType

import com.haskforce.cabal.CabalLanguage
import com.haskforce.cabal.lang.psi
import com.haskforce.cabal.lang.psi.{CabalFile, CabalTypes}
import com.haskforce.utils.{NonEmptySet, PQ}

final class CabalQuery(val psiFile: psi.CabalFile) {

  lazy val getVirtualFile = Option(psiFile.getVirtualFile)

  lazy val getFilePath = getVirtualFile.flatMap(f => Option(f.getCanonicalPath))

  lazy val getDirPath = for {
    f <- getVirtualFile
    d <- Option(f.getParent)
    p <- Option(d.getCanonicalPath)
  } yield p

  def getPackageName: Option[String] = for {
    pkgName <- PQ.getChildOfType(psiFile, classOf[psi.PkgName])
    ff <- PQ.getChildOfType(pkgName, classOf[psi.Freeform])
  } yield ff.getText

  /** If 'library' stanza exists, returns it; otherwise, implicitly uses root stanza. */
  def getLibrary: BuildInfo.Library = {
    psiFile.getChildren.collectFirst {
      case c: psi.Library => new BuildInfo.ExplicitLibrary(c)
    }.getOrElse(new BuildInfo.ImplicitLibrary(psiFile))
  }

  def getExecutables: Stream[BuildInfo.Executable] = {
    PQ.streamChildren(psiFile, classOf[psi.Executable]).map(new BuildInfo.Executable(_))
  }

  def getTestSuites: Stream[BuildInfo.TestSuite] = {
    PQ.streamChildren(psiFile, classOf[psi.TestSuite]).map(new BuildInfo.TestSuite(_))
  }

  def getBenchmarks: Stream[BuildInfo.Benchmark] = {
    PQ.streamChildren(psiFile, classOf[psi.Benchmark]).map(new BuildInfo.Benchmark(_))
  }

  def getBuildInfo: Array[BuildInfo] = {
    getLibrary +: psiFile.getChildren.collect {
      case c: psi.Executable => new BuildInfo.Executable(c)
      case c: psi.TestSuite => new BuildInfo.TestSuite(c)
      case c: psi.Benchmark => new BuildInfo.Benchmark(c)
    }
  }

  def findBuildInfoForSourceFile(sourceFilePath: String): Option[BuildInfo] = {
    getDirPath.flatMap(baseDir =>
      CabalQuery.findBuildInfoForSourceFile(getBuildInfo, baseDir, sourceFilePath)
    )
  }

  def findBuildInfoForSourceFile(psiFile: PsiFile): Option[BuildInfo] = {
    Option(psiFile.getVirtualFile).flatMap(findBuildInfoForSourceFile)
  }

  def findBuildInfoForSourceFile(vFile: VirtualFile): Option[BuildInfo] = {
    Option(vFile.getCanonicalPath).flatMap(findBuildInfoForSourceFile)
  }

  /** Determined from 'library' and 'executable' or root stanza; defaults to "." */
  def getSourceRoots: NonEmptySet[String] = {
    getLibrary.getSourceDirs.append(
      getExecutables.map(_.getSourceDirs): _*
    )
  }

  /** Determined from 'test-suite' or 'benchmark' stanzas, if any exist. */
  def getTestSourceRoots: Option[NonEmptySet[String]] = {
    (getTestSuites ++ getBenchmarks).map(_.getSourceDirs).reduceRightOption(
      (s1, s2) => s1.append(s2)
    )
  }
}

object CabalQuery {

  private val LOG = Logger.getInstance(classOf[CabalQuery])

  def fromJavaFile(optProject: Option[Project], file: File): Option[CabalQuery] = {
    val project = optProject.getOrElse(ProjectManager.getInstance().getDefaultProject)
    val text = try {
      new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
    } catch {
      case e: IOException =>
        LOG.warn(s"Could not read CabalFile $file: $e", e)
        return None
    }
    PsiFileFactory.getInstance(project).createFileFromText(
      file.getName, CabalLanguage.INSTANCE, text
    ) match {
      case psiFile: CabalFile => Some(new CabalQuery(psiFile))
      case other =>
        LOG.warn(new AssertionError(s"Expected CabalFile, got: ${other.getClass}"))
        None
    }
  }

  /**
   * Returns the first BuildInfo which has the most specific source dir and
   * contains the specified 'sourcePath' given that 'baseDir' is the root
   * directory of the source dirs.  As such, if two different BuildInfo source dirs
   * could be an ancestor of 'sourcePath', then the first BuildInfo listed
   * with the longest matching source dir wins.
   *
   * Note that this method is provided specifically for testing without the need
   * of having VirtualFile instances which correspond to real files.  In real code,
   * prefer the instance method provided in the 'CabalQuery' class.
   */
  def findBuildInfoForSourceFile
      (infos: Array[BuildInfo],
       baseDir: String,
       sourcePath: String)
      : Option[BuildInfo] = {
    if (!sourcePath.startsWith(baseDir)) return None
    var result: Option[(String, BuildInfo)] = None
    infos.toStream.foreach { info =>
      info.getSourceDirs.foreach { sourceDir =>
        if (FileUtil.isAncestor(FileUtil.join(baseDir, sourceDir), sourcePath, true)) {
          if (!result.exists(_._1.length >= sourceDir.length)) {
            result = Some((sourceDir, info))
          }
        }
      }
    }
    result.map(_._2)
  }

  val defaultSourceRoots = NonEmptySet(".")
}

trait ElementWrapper {
  val el: PsiElement
}

sealed trait Named extends ElementWrapper {

  val NAME_ELEMENT_TYPE: IElementType

  def getName: Option[String] = {
    PQ.getChildNodes(el, NAME_ELEMENT_TYPE).headOption.map(_.getText)
  }
}

sealed trait BuildInfo extends ElementWrapper  {

  val typ: BuildInfo.Type

  val el: PsiElement

  /** Returns all listed extensions. */
  def getExtensions: Set[String] = {
    PQ.streamChildren(el, classOf[psi.impl.ExtensionsImpl]).flatMap(
      PQ.getChildOfType(_, classOf[psi.IdentList])
    ).flatMap(
      PQ.getChildNodes(_, CabalTypes.IDENT).map(_.getText)
    ).toSet
  }

  /** Returns the listed dependencies' package names. */
  def getDependencies: Set[String] = {
    PQ.getChildOfType(el, classOf[psi.BuildDepends]).map(
      _.getPackageNames.toSet
    ).getOrElse(Set.empty)
  }

  def getGhcOptions: Set[String] = {
    PQ.streamChildren(el, classOf[psi.impl.GhcOptionsImpl]).flatMap(_.getValue).toSet
  }

  /** Get hs-source-dirs listed, defaulting to "." if not present. */
  def getSourceDirs: NonEmptySet[String] = {
    NonEmptySet.fromSets[String](
      PQ.streamChildren(el, classOf[psi.impl.SourceDirsImpl]).map(_.getValue.toSet)
    ).getOrElse(CabalQuery.defaultSourceRoots)
  }
}

object BuildInfo {

  sealed trait Type
  object Type {
    case object Library extends Type
    case object Executable extends Type
    case object TestSuite extends Type
    case object Benchmark extends Type
  }

  val LIBRARY_TYPE_NAME = "library"
  sealed trait Library extends BuildInfo {
    override val typ = Type.Library
  }
  /** Library implicitly from root stanza when no 'library' stanza exists. */
  final class ImplicitLibrary(val el: psi.CabalFile) extends Library
  /** Library explicitly listed as 'library'. */
  final class ExplicitLibrary(val el: psi.Library) extends Library

  val EXECUTABLE_TYPE_NAME = "executable"
  final class Executable(val el: psi.Executable) extends BuildInfo with Named {
    override val typ = Type.Executable
    override val NAME_ELEMENT_TYPE = CabalTypes.EXECUTABLE_NAME
  }

  val TEST_SUITE_TYPE_NAME = "test-suite"
  final class TestSuite(val el: psi.TestSuite) extends BuildInfo with Named {
    override val typ = Type.TestSuite
    override val NAME_ELEMENT_TYPE = CabalTypes.TEST_SUITE_NAME
  }

  final class Benchmark(val el: psi.Benchmark) extends BuildInfo with Named {
    override val typ = Type.Benchmark
    override val NAME_ELEMENT_TYPE = CabalTypes.BENCHMARK_NAME
  }
}
