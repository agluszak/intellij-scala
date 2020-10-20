package org.jetbrains.plugins.scala
package debugger

import java.io.File
import java.util.Collections.emptyList
import java.{util => ju}

import com.intellij.debugger.engine._
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.debugger.{MultiRequestPositionManager, NoDataException, PositionManager, SourcePosition}
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi._
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.{CachedValueProvider, CachedValuesManager, PsiTreeUtil}
import com.intellij.util.containers.{ConcurrentIntObjectMap, ContainerUtil}
import com.sun.jdi._
import com.sun.jdi.request.ClassPrepareRequest
import org.jetbrains.annotations.{NotNull, Nullable}
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.debugger.ScalaPositionManager._
import org.jetbrains.plugins.scala.debugger.evaluation.ScalaEvaluatorBuilderUtil
import org.jetbrains.plugins.scala.debugger.evaluation.evaluator.ScalaCompilingEvaluator
import org.jetbrains.plugins.scala.debugger.evaluation.util.DebuggerUtil._
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.macros.MacroDef
import org.jetbrains.plugins.scala.lang.psi.ElementScope
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScConstructorPattern, ScInfixPattern}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameter, ScParameters}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil.toJavaFqn
import org.jetbrains.plugins.scala.macroAnnotations.CachedInUserData

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.reflect.NameTransformer
import scala.util.Try

/**
  * @author ilyas
  */
class ScalaPositionManager(val debugProcess: DebugProcess) extends PositionManager with MultiRequestPositionManager with LocationLineManager {

  protected[debugger] val caches = new ScalaPositionManagerCaches(debugProcess)
  import caches._

  private val debugProcessScope: ElementScope = ElementScope(debugProcess.getProject, debugProcess.getSearchScope)

  ScalaPositionManager.cacheInstance(this)

  override def getAcceptedFileTypes: ju.Set[_ <: FileType] =
    ju.Collections.singleton(ScalaFileType.INSTANCE)

  @Nullable
  override def getSourcePosition(@Nullable location: Location): SourcePosition = {
    if (location == null || debugProcess.getProject.isDisposed || shouldSkip(location))
      return null

    val position =
      for {
        psiFile   <- findPsiFileByLocation(location)
        lineNumber = exactLineNumber(location)
        if lineNumber >= 0
      } yield {
        calcPosition(psiFile, location, lineNumber).getOrElse {
          SourcePosition.createFromLine(psiFile, lineNumber)
        }
      }
    position.getOrThrow(NoDataException.INSTANCE)
  }

  @NotNull
  override def getAllClasses(@NotNull position: SourcePosition): ju.List[ReferenceType] = {
    val file = position.getFile
    val sourceName = file.name

    val topLevelClassNames = inReadAction {
      positionsOnLine(file, position.getLine)
        .flatMap(_.withParents.filterByType[ScTypeDefinition].lastOption)
        .map(_.qualifiedName)
        .distinct
    }

    if (topLevelClassNames.isEmpty) emptyList()
    else {
      val pckgName = toJavaFqn(packageName(topLevelClassNames.head))
      val sourcePaths = topLevelClassNames.map(qName => toJavaFqn(qName).replace('.', File.separatorChar))
      val positionClassName = className(position)

      def correctSourceName(refType: ReferenceType): Boolean = {
        val sourceNames = refType.sourceNames(SCALA_STRATUM)
        sourceNames.contains(sourceName) || positionClassName.exists(sourceNames.contains)
      }

      def correctSourcePath(refType: ReferenceType): Boolean =
        sourcePaths.exists(source => refType.sourcePaths(SCALA_STRATUM).asScala.exists(_.startsWith(source)))

      filterAllClasses(debugProcess) { refType =>
        val samePathAndSourceName =
          if (refType.availableStrata().contains(SCALA_STRATUM))
            correctSourceName(refType) && correctSourcePath(refType)
          else
            refType.name().startsWith(pckgName) &&
              cachedSourceName(refType).contains(sourceName)

        samePathAndSourceName && locationsOfLine(refType, position).size > 0
      }
    }
  }

  @NotNull
  override def locationsOfLine(@NotNull refType: ReferenceType, @NotNull position: SourcePosition): ju.List[Location] = {
    checkForIndyLambdas(refType)

    try {
      inReadAction {
        locationsOfLine(refType, position.getLine, position.getFile.name) ++
          className(position).toList.flatMap(locationsOfLine(refType, position.getLine, _))
      }.asJava
    }
    catch {
      case _: AbsentInformationException => ju.Collections.emptyList()
    }
  }

  override def createPrepareRequest(@NotNull requestor: ClassPrepareRequestor, @NotNull position: SourcePosition): ClassPrepareRequest = {
    throw new IllegalStateException("This class implements MultiRequestPositionManager, corresponding createPrepareRequests version should be used")
  }

  override def createPrepareRequests(requestor: ClassPrepareRequestor, position: SourcePosition): ju.List[ClassPrepareRequest] = {
    val file = position.getFile
    throwIfNotScalaFile(file)

    val requestManager = debugProcess.getRequestsManager
    val sourceNameRequest = requestManager.createClassPrepareRequest(requestor, "")
    sourceNameRequest.addSourceNameFilter(file.name)

    val classNameAsSourceRequest = className(position).map { name =>
      val request = requestManager.createClassPrepareRequest(requestor, "")
      request.addSourceNameFilter(name)
      request
    }

    (sourceNameRequest :: classNameAsSourceRequest.toList).asJava
  }

  private def className(position: SourcePosition): Option[String] = {
    inReadAction {
      positionsOnLine(position.getFile, position.getLine)
        .flatMap(_.parentOfType[ScTypeDefinition])
        .headOption
        .map {
          case o: ScObject => o.name + "$"
          case c => c.name
        }

    }
  }

  private def throwIfNotScalaFile(file: PsiFile): Unit = {
    if (!checkScalaFile(file)) throw NoDataException.INSTANCE
  }

  private def checkScalaFile(file: PsiFile): Boolean = file match {
    case sf: ScalaFile => !sf.isCompiled
    case _ => false
  }

  private def packageName(qName: String): String = {
    val lastDot = qName.lastIndexOf('.')

    if (lastDot < 0) qName
    else qName.substring(0, lastDot)
  }

  protected def nonWhitespaceElement(@NotNull position: SourcePosition): PsiElement = {
    val file = position.getFile
    @tailrec
    def nonWhitespaceInner(element: PsiElement, document: Document): PsiElement = {
      element match {
        case null => null
        case _: PsiWhiteSpace if document.getLineNumber(element.getTextRange.getEndOffset) == position.getLine =>
          val nextElement = file.findElementAt(element.getTextRange.getEndOffset)
          nonWhitespaceInner(nextElement, document)
        case _ => element
      }
    }
    if (!file.isInstanceOf[ScalaFile]) null
    else {
      val firstElement = file.findElementAt(position.getOffset)
      try {
        val document = PsiDocumentManager.getInstance(file.getProject).getDocument(file)
        nonWhitespaceInner(firstElement, document)
      }
      catch {
        case _: Throwable => firstElement
      }
    }
  }

  private def calcPosition(file: PsiFile, location: Location, lineNumber: Int): Option[SourcePosition] = {
    throwIfNotScalaFile(file)

    def isDefaultArgument(method: Method) = {
      val methodName = method.name()
      val lastDollar = methodName.lastIndexOf("$")
      if (lastDollar >= 0) {
        val (start, index) = methodName.splitAt(lastDollar + 1)
        (start.endsWith("$default$"), index)
      }
      else (false, "")
    }

    def findDefaultArg(possiblePositions: Seq[PsiElement], defaultArgIndex: String) : Option[PsiElement] = {
      try {
        val paramNumber = defaultArgIndex.toInt - 1
        possiblePositions.find {
          e =>
            val scParameters = PsiTreeUtil.getParentOfType(e, classOf[ScParameters])
            if (scParameters != null) {
              val param = scParameters.params(paramNumber)
              param.isDefaultParam && param.isAncestorOf(e)
            }
            else false
        }
      } catch {
        case _: Exception => None
      }
    }

    def calcElement(): Option[PsiElement] = {
      val possiblePositions = positionsOnLine(file, lineNumber)
      val currentMethod = location.method()

      lazy val (isDefaultArg, defaultArgIndex) = isDefaultArgument(currentMethod)

      def findPsiElementForIndyLambda(): Option[PsiElement] = {
        val lambdas = lambdasOnLine(file, lineNumber)
        val methods = indyLambdaMethodsOnLine(location.declaringType(), lineNumber)
        val methodsToLambdas = methods.zip(lambdas).toMap
        methodsToLambdas.get(currentMethod)
      }

      def functionExprBody(element: PsiElement): PsiElement = element match {
        case ScFunctionExpr(_, Some(body)) => body
        case _ => element
      }

      if (possiblePositions.size <= 1) {
        possiblePositions.headOption
      }
      else if (isIndyLambda(currentMethod)) {
        findPsiElementForIndyLambda().map(functionExprBody)
      }
      else if (isDefaultArg) {
        findDefaultArg(possiblePositions, defaultArgIndex)
      }
      else if (!isAnonfun(currentMethod)) {
        possiblePositions.find {
          case e: PsiElement if isLambda(e) => false
          case (_: ScExpression) childOf (_: ScParameter) => false
          case _ => true
        }
      }
      else {
        val generatingPsiElem = findElementByReferenceType(location.declaringType())
        possiblePositions
          .find(p => generatingPsiElem.contains(findGeneratingClassOrMethodParent(p)))
          .map(functionExprBody)
      }
    }

    calcElement().filter(_.isValid).map(SourcePosition.createFromElement)
  }

  private def findPsiFileByLocation(location: Location): Option[PsiFile] = {
    val referenceType = location.declaringType()

    if (referenceType.availableStrata().contains(SCALA_STRATUM)) {
      val className = location.sourcePath(SCALA_STRATUM).replace(File.separatorChar, '.')
      findPsiFileByClassName(className)
    }
    else
      getPsiFileByReferenceType(referenceType)
  }

  @Nullable
  private def getPsiFileByReferenceType(refType: ReferenceType): Option[PsiFile] =
    refType match {
      case null    => None
      case refType => findPsiFileByClassName(refType.name())
    }

  private def topLevelClassName(originalQName: String): String = {
    if (originalQName.endsWith(packageSuffix)) originalQName
    else originalQName.replace(packageSuffix, ".").takeWhile(_ != '$')
  }

  private def tryToFindPsiClass(name: String) = {
    val classes = findClassesByQName(name, debugProcessScope, fallbackToProjectScope = true)

    classes.find(!_.isInstanceOf[ScObject])
      .orElse(classes.headOption)
  }

  private def findPsiFileByClassName(className: String): Option[PsiFile] = {
    def withDollarTestName(originalQName: String): Option[String] = {
      val dollarTestSuffix = "$Test" //See SCL-9340
      if (originalQName.endsWith(dollarTestSuffix)) Some(originalQName)
      else if (originalQName.contains(dollarTestSuffix + "$")) {
        val index = originalQName.indexOf(dollarTestSuffix) + dollarTestSuffix.length
        Some(originalQName.take(index))
      }
      else None
    }

    val originalQName = NameTransformer.decode(nonLambdaName(className))
    val nameToSearch =
      withDollarTestName(originalQName).getOrElse(topLevelClassName(originalQName))

    refTypeNameToFileCache.getOrElseUpdate(nameToSearch,
      inReadAction {
        tryToFindPsiClass(nameToSearch)
          .map(_.getNavigationElement.getContainingFile)
      }
    )
  }

  private def nameMatches(elem: PsiElement, refType: ReferenceType): Boolean = {
    val pattern = NamePattern.forElement(elem)
    pattern != null && pattern.matches(refType)
  }

  private def checkForIndyLambdas(refType: ReferenceType): Unit = {
    val psiFile = getPsiFileByReferenceType(refType)

    if (refType.methods().asScala.exists(isIndyLambda)) {
      markCompiledWithIndyLambdas(psiFile.get, true)
    }
  }

  def findElementByReferenceType(refType: ReferenceType): Option[PsiElement] = {
    refTypeToElementCache.get(refType) match {
      case Some(Some(p)) if p.getElement != null => Some(p.getElement)
      case Some(Some(_)) | None =>
        val found = findElementByReferenceTypeInner(refType)
        refTypeToElementCache.update(refType, found.map(_.createSmartPointer))
        found
      case Some(None) => None
    }
  }

  private def findElementByReferenceTypeInner(refType: ReferenceType): Option[PsiElement] = {
    val byName = findPsiClassByQName(refType, debugProcessScope) orElse findByShortName(refType)
    if (byName.isDefined) return byName

    val project = debugProcess.getProject

    val allLocations = Try(refType.allLineLocations().asScala).getOrElse(Seq.empty)

    val refTypeLineNumbers = allLocations.map(checkedLineNumber).filter(_ > 0)
    if (refTypeLineNumbers.isEmpty) return None

    val firstRefTypeLine = refTypeLineNumbers.min
    val lastRefTypeLine = refTypeLineNumbers.max
    val refTypeLines = firstRefTypeLine to lastRefTypeLine

    val file = getPsiFileByReferenceType(refType) match {
      case Some(file) if checkScalaFile(file) => file
      case _ => return None
    }

    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    if (document == null) return None

    def elementLineRange(elem: PsiElement, document: Document) = {
      val startLine = document.getLineNumber(elem.getTextRange.getStartOffset)
      val endLine = document.getLineNumber(elem.getTextRange.getEndOffset)
      startLine to endLine
    }

    def checkLines(elem: PsiElement, document: Document) = {
      val lineRange = elementLineRange(elem, document)
      //intersection, very loose check because sometimes first line for <init> method is after range of the class
      firstRefTypeLine <= lineRange.end && lastRefTypeLine >= lineRange.start
    }

    def isAppropriateCandidate(elem: PsiElement) = {
      checkLines(elem, document) && ScalaEvaluatorBuilderUtil.isGenerateClass(elem) && nameMatches(elem, refType)
    }

    def findCandidates(): Seq[PsiElement] = {
      def findAt(offset: Int): Option[PsiElement] = {
        val startElem = file.findElementAt(offset)
        startElem.parentsInFile.find(isAppropriateCandidate)
      }
      if (lastRefTypeLine - firstRefTypeLine >= 2 && firstRefTypeLine + 1 <= document.getLineCount - 1) {
        val offsetsInTheMiddle = Seq(
          document.getLineEndOffset(firstRefTypeLine),
          document.getLineEndOffset(firstRefTypeLine + 1)
        )
        offsetsInTheMiddle.flatMap(findAt).distinct
      }
      else {
        val firstLinePositions = positionsOnLine(file, firstRefTypeLine)
        val allPositions =
          if (firstRefTypeLine == lastRefTypeLine) firstLinePositions
          else firstLinePositions ++ positionsOnLine(file, lastRefTypeLine)
        allPositions.distinct.filter(isAppropriateCandidate)
      }
    }

    def filterWithSignature(candidates: Seq[PsiElement]): Seq[PsiElement] = {
      val applySignature = refType.methodsByName("apply").asScala.find(m => !m.isSynthetic).map(_.signature())
      if (applySignature.isEmpty) candidates
      else {
        candidates.filter(l => applySignature == lambdaJVMSignature(l))
      }
    }

    val candidates = findCandidates()

    if (candidates.size <= 1) return candidates.headOption

    if (refTypeLines.size > 1) {
      val withExactlySameLines = candidates.filter(elementLineRange(_, document) == refTypeLines)
      if (withExactlySameLines.size == 1) return withExactlySameLines.headOption
    }

    if (candidates.exists(!isLambda(_))) return candidates.headOption

    val filteredWithSignature = filterWithSignature(candidates)
    if (filteredWithSignature.size == 1) return filteredWithSignature.headOption

    val byContainingClasses = filteredWithSignature.groupBy(c => findGeneratingClassOrMethodParent(c.getParent))
    if (byContainingClasses.size > 1) {
      findContainingClass(refType) match {
        case Some(e) => return byContainingClasses.get(e).flatMap(_.headOption)
        case None =>
      }
    }
    filteredWithSignature.headOption
  }

  private def findByShortName(refType: ReferenceType): Option[PsiClass] = {
    val project = debugProcess.getProject

    if (DumbService.getInstance(project).isDumb) return None

    lazy val sourceName = cachedSourceName(refType).getOrElse("")

    def sameFileName(elem: PsiElement) = {
      val containingFile = elem.getContainingFile
      containingFile != null && containingFile.name == sourceName
    }

    val originalQName = NameTransformer.decode(refType.name)
    val withoutSuffix =
      if (originalQName.endsWith(packageSuffix)) originalQName
      else originalQName.replace(packageSuffix, ".").stripSuffix("$").stripSuffix("$class")
    val lastDollar = withoutSuffix.lastIndexOf('$')
    val lastDot = withoutSuffix.lastIndexOf('.')
    val index = Seq(lastDollar, lastDot, 0).max + 1
    val name = withoutSuffix.drop(index)
    val isScalaObject = originalQName.endsWith("$")

    val cacheManager = ScalaShortNamesCacheManager.getInstance(project)
    val classes = cacheManager.getClassesByName(name, GlobalSearchScope.allScope(project)).toSeq

    val inSameFile = classes.filter(c => c.isValid && sameFileName(c))

    if (inSameFile.length == 1) classes.headOption
    else if (inSameFile.length >= 2) {
      if (isScalaObject) inSameFile.find(_.isInstanceOf[ScObject])
      else inSameFile.find(!_.isInstanceOf[ScObject])
    }
    else None
  }

  private def findContainingClass(refType: ReferenceType): Option[PsiElement] = {
    def classesByName(s: String) = {
      val vm = debugProcess.getVirtualMachineProxy
      vm.classesByName(s)
    }

    val fullName = refType.name()
    val containingClassName = DebuggerUtilsEx.getLambdaBaseClassName(fullName) match {
      case baseClassName: String => Some(baseClassName)
      case null =>
        val decoded = NameTransformer.decode(fullName)
        val index = decoded.lastIndexOf("$$")

        if (index < 0) None
        else Some(NameTransformer.encode(decoded.substring(0, index)))
    }

    for {
      name  <- containingClassName
      clazz <- classesByName(name).asScala.headOption
      elem  <- findElementByReferenceType(clazz)
    }
      yield elem
  }

  //typeName can be SomeClass$$Lambda$1.1836643189
  private def nonLambdaName(refTypeName: String): String =
    DebuggerUtilsEx.getLambdaBaseClassName(refTypeName) match {
      case null => refTypeName
      case name => name
    }
}

object ScalaPositionManager {
  /** May exist for classes where some code was inlined.
   *
   *  `location.sourceName(SCALA_STRATUM)` returns source file name, e.g. `Example.scala`
   *
   * `location.sourcePath(SCALA_STRATUM)` returns qualified name of a class where
   *  inlined method was defined as a file system path, e.g. `my/test/Example$`
   */
  final val SCALA_STRATUM = "Scala"

  private val isCompiledWithIndyLambdasKey: Key[java.lang.Boolean] = Key.create("compiled.with.indy.lambdas")

  def isCompiledWithIndyLambdas(file: PsiFile): Boolean = {
    if (file == null)
      return false

    val originalFile = ScalaCompilingEvaluator.originalFile(file)
    originalFile.getUserData(isCompiledWithIndyLambdasKey).toOption.exists(_.booleanValue())
  }

  private def isCheckedForIndyLambdas(file: PsiFile): Boolean = {
    if (file == null)
      return true

    val originalFile = ScalaCompilingEvaluator.originalFile(file)
    originalFile.getUserData(isCompiledWithIndyLambdasKey) != null
  }

  private def markCompiledWithIndyLambdas(file: PsiFile, value: java.lang.Boolean): Unit = {
    val originalFile = ScalaCompilingEvaluator.originalFile(file)
    originalFile.putUserData(isCompiledWithIndyLambdasKey, value)
  }

  private val instances = mutable.HashMap[DebugProcess, ScalaPositionManager]()

  private def cacheInstance(scPosManager: ScalaPositionManager): Unit = {
    val debugProcess = scPosManager.debugProcess

    instances.put(debugProcess, scPosManager)
    debugProcess.addDebugProcessListener(new DebugProcessListener {
      override def processDetached(process: DebugProcess, closedByUser: Boolean): Unit = {
        ScalaPositionManager.instances.remove(process)
        debugProcess.removeDebugProcessListener(this)
      }
    })
  }

  def instance(vm: VirtualMachine): Option[ScalaPositionManager] = instances.collectFirst {
    case (process, manager) if getVM(process).contains(vm) => manager
  }

  def instance(debugProcess: DebugProcess): Option[ScalaPositionManager] = instances.get(debugProcess)

  def instance(mirror: Mirror): Option[ScalaPositionManager] = instance(mirror.virtualMachine())

  private def getVM(debugProcess: DebugProcess) = {
    if (!DebuggerManagerThreadImpl.isManagerThread) None
    else {
      debugProcess.getVirtualMachineProxy match {
        case impl: VirtualMachineProxyImpl => Option(impl.getVirtualMachine)
        case _ => None
      }
    }
  }

  def positionsOnLine(file: PsiFile, lineNumber: Int): Seq[PsiElement] = {
    //stored in `file`, invalidated on `file` change
    @CachedInUserData(file, file)
    def cachedMap: ConcurrentIntObjectMap[Seq[PsiElement]] = ContainerUtil.createConcurrentIntObjectMap()

    if (lineNumber < 0) return Seq.empty

    val scFile: ScalaFile = file match {
      case sf: ScalaFile => sf
      case _ => return Seq.empty
    }

    val map = cachedMap

    Option(map.get(lineNumber))
      .getOrElse(map.cacheOrGet(lineNumber, positionsOnLineInner(scFile, lineNumber)))
  }

  def checkedLineNumber(location: Location): Int =
    try location.lineNumber() - 1
    catch {case _: InternalError => -1}

  def cachedSourceName(refType: ReferenceType): Option[String] = {
    ScalaPositionManager.instance(refType).map(_.caches).flatMap(_.cachedSourceName(refType))
  }

  private def positionsOnLineInner(file: ScalaFile, lineNumber: Int): Seq[PsiElement] = {
    inReadAction {
      val document = PsiDocumentManager.getInstance(file.getProject).getDocument(file)
      if (document == null || lineNumber >= document.getLineCount) return Seq.empty
      val startLine = document.getLineStartOffset(lineNumber)
      val endLine = document.getLineEndOffset(lineNumber)

      def elementsOnTheLine(file: ScalaFile): Seq[PsiElement] = {
        val builder = ArraySeq.newBuilder[PsiElement]
        var elem = file.findElementAt(startLine)

        while (elem != null && elem.getTextOffset <= endLine) {
          elem match {
            case ChildOf(_: ScUnitExpr) | ChildOf(ScBlock()) =>
              builder += elem
            case ElementType(t) if ScalaTokenTypes.WHITES_SPACES_AND_COMMENTS_TOKEN_SET.contains(t) ||
              ScalaTokenTypes.ANY_BRACKETS_TOKEN_SET.contains(t) =>
            case _ =>
              builder += elem
          }
          elem = PsiTreeUtil.nextLeaf(elem, true)
        }
        builder.result()
      }

      def findParent(element: PsiElement): Option[PsiElement] = {
        val parentsOnTheLine = element.withParentsInFile.takeWhile(e => e.getTextOffset > startLine).toIndexedSeq
        val anon = parentsOnTheLine.collectFirst {
          case e if isLambda(e) => e
          case newTd: ScNewTemplateDefinition if generatesAnonClass(newTd) => newTd
        }
        val filteredParents = parentsOnTheLine.reverse.filter {
          case _: ScExpression => true
          case _: ScConstructorPattern | _: ScInfixPattern | _: ScBindingPattern => true
          case callRefId childOf ((ref: ScReferenceExpression) childOf (_: ScMethodCall))
            if ref.nameId == callRefId && ref.getTextRange.getStartOffset < startLine => true
          case _: ScTypeDefinition => true
          case _ => false
        }
        val maxExpressionPatternOrTypeDef =
          filteredParents.find(!_.isInstanceOf[ScBlock]).orElse(filteredParents.headOption)
        Seq(anon, maxExpressionPatternOrTypeDef).flatten.sortBy(_.getTextLength).headOption
      }
      elementsOnTheLine(file).flatMap(findParent).distinct
    }
  }

  def isLambda(element: PsiElement): Boolean = {
    ScalaEvaluatorBuilderUtil.isGenerateAnonfun(element) && !isInsideMacro(element)
  }

  def lambdasOnLine(file: PsiFile, lineNumber: Int): Seq[PsiElement] = {
    positionsOnLine(file, lineNumber).filter(isLambda)
  }

  def isIndyLambda(m: Method): Boolean = {
    val name = m.name()
    name.contains("$anonfun$") && name.charAt(name.length - 1).isDigit
  }

  def isAnonfunType(refType: ReferenceType): Boolean = {
    refType match {
      case ct: ClassType =>
        val supClass = ct.superclass()
        supClass != null && supClass.name().startsWith("scala.runtime.AbstractFunction")
      case _ => false
    }
  }

  def isAnonfun(m: Method): Boolean = {
    isIndyLambda(m) || m.name.startsWith("apply") && isAnonfunType(m.declaringType())
  }

  def indyLambdaMethodsOnLine(refType: ReferenceType, lineNumber: Int): Seq[Method] = {
    def ordinal(m: Method) = {
      val name = m.name()
      val lastDollar = name.lastIndexOf('$')
      Try(name.substring(lastDollar + 1).toInt).getOrElse(-1)
    }

    val all = refType.methods().asScala.iterator.filter(isIndyLambda)
    val onLine = all.filter(m => Try(!m.locationsOfLine(lineNumber + 1).isEmpty).getOrElse(false))
    onLine.toSeq.sortBy(ordinal)
  }

  @tailrec
  def findGeneratingClassOrMethodParent(element: PsiElement): PsiElement = {
    element match {
      case null => null
      case elem if ScalaEvaluatorBuilderUtil.isGenerateClass(elem) || isLambda(elem) => elem
      case elem if isMacroCall(elem) => elem
      case elem => findGeneratingClassOrMethodParent(elem.getParent)
    }
  }

  object InsideMacro {
    def unapply(elem: PsiElement): Option[ScMethodCall] = {
      elem.parentsInFile.collectFirst {
        case mc: ScMethodCall if isMacroCall(mc) => mc
      }
    }
  }

  def isInsideMacro(elem: PsiElement): Boolean = elem.parentsInFile.exists(isMacroCall)

  private def isMacroCall(elem: PsiElement): Boolean = elem match {
    case ScMethodCall(ResolvesTo(MacroDef(_)), _) => true
    case _ => false
  }

  def shouldSkip(location: Location, debugProcess: DebugProcess): Boolean = {
    ScalaPositionManager.instance(debugProcess).forall(_.shouldSkip(location))
  }

  private def getSpecificNameForDebugger(td: ScTypeDefinition): String = {
    val name = td.getQualifiedNameForDebugger

    td match {
      case _: ScObject => s"$name$$"
      case _: ScTrait => s"$name$$class"
      case _ => name
    }
  }

  private class NamePattern(elem: PsiElement) {
    private val containingFile = elem.getContainingFile
    private val sourceName = containingFile.name
    private val isGeneratedForCompilingEvaluator = ScalaCompilingEvaluator.isGenerated(containingFile)
    private var compiledWithIndyLambdas = isCompiledWithIndyLambdas(containingFile)
    private val exactName: Option[String] = {
      elem match {
        case td: ScTypeDefinition if !isLocalClass(td) =>
          Some(getSpecificNameForDebugger(td))
        case _ => None
      }
    }
    private var classJVMNameParts: Seq[String] = _

    private def computeClassJVMNameParts(elem: PsiElement): Seq[String] = {
      if (exactName.isDefined) Seq.empty
      else inReadAction {
        elem match {
          case InsideMacro(call) => computeClassJVMNameParts(call.getParent)
          case _ =>
            val parts = elem.withParentsInFile.flatMap(partsFor)
            parts.toSeq.reverse
        }
      }
    }

    private def partsFor(elem: PsiElement): Seq[String] = {
      elem match {
        case o: ScObject if o.isPackageObject => Seq("package$")
        case td: ScTypeDefinition => Seq(ScalaNamesUtil.toJavaName(td.name))
        case newTd: ScNewTemplateDefinition if generatesAnonClass(newTd) => Seq("$anon")
        case e if ScalaEvaluatorBuilderUtil.isGenerateClass(e) => partsForAnonfun(e)
        case _ => Seq.empty
      }
    }

    private def partsForAnonfun(elem: PsiElement): Seq[String] = {
      val anonfunCount = ScalaEvaluatorBuilderUtil.anonClassCount(elem)
      val lastParts = Seq.fill(anonfunCount - 1)(Seq("$apply", "$anonfun")).flatten
      val containingClass = findGeneratingClassOrMethodParent(elem.getParent)
      val owner = PsiTreeUtil.getParentOfType(elem, classOf[ScFunctionDefinition], classOf[ScTypeDefinition],
        classOf[ScPatternDefinition], classOf[ScVariableDefinition])
      val firstParts =
        if (PsiTreeUtil.isAncestor(owner, containingClass, true)) Seq("$anonfun")
        else owner match {
          case fun: ScFunctionDefinition =>
            val name = if (fun.name == "this") JVMNameUtil.CONSTRUCTOR_NAME else fun.name
            val encoded = NameTransformer.encode(name)
            Seq(s"$$$encoded", "$anonfun")
          case _ => Seq("$anonfun")
        }
      lastParts ++ firstParts
    }

    private def checkParts(name: String): Boolean = {
      var nameTail = name
      updateParts()

      for (part <- classJVMNameParts) {
        val index = nameTail.indexOf(part)
        if (index >= 0) {
          nameTail = nameTail.substring(index + part.length)
        }
        else return false
      }
      nameTail.indexOf("$anon") == -1
    }

    def updateParts(): Unit = {
      val newValue = isCompiledWithIndyLambdas(containingFile)
      if (newValue != compiledWithIndyLambdas || classJVMNameParts == null) {
        compiledWithIndyLambdas = newValue
        classJVMNameParts = computeClassJVMNameParts(elem)
      }
    }

    def matches(refType: ReferenceType): Boolean = {
      val refTypeSourceName = cachedSourceName(refType).getOrElse("")
      if (refTypeSourceName != sourceName && !isGeneratedForCompilingEvaluator) return false

      val name = refType.name()

      exactName match {
        case Some(qName) => qName == name || qName.stripSuffix("$class") == name
        case None => checkParts(name)
      }
    }
  }

  private object NamePattern {
    def forElement(elem: PsiElement): NamePattern = {
      if (elem == null || !ScalaEvaluatorBuilderUtil.isGenerateClass(elem)) return null

      val cacheProvider = new CachedValueProvider[NamePattern] {
        override def compute(): Result[NamePattern] = Result.create(new NamePattern(elem), elem)
      }

      CachedValuesManager.getCachedValue(elem, cacheProvider)
    }
  }

  private[debugger] class ScalaPositionManagerCaches(debugProcess: DebugProcess) {

    debugProcess.addDebugProcessListener(new DebugProcessListener {
      override def processDetached(process: DebugProcess, closedByUser: Boolean): Unit = {
        clear()
        process.removeDebugProcessListener(this)
      }
    })

    val refTypeNameToFileCache: mutable.HashMap[String, Option[PsiFile]] =
      mutable.HashMap[String, Option[PsiFile]]()

    val refTypeToElementCache: mutable.HashMap[ReferenceType, Option[SmartPsiElementPointer[PsiElement]]] =
      mutable.HashMap[ReferenceType, Option[SmartPsiElementPointer[PsiElement]]]()

    val customizedLocationsCache: mutable.HashMap[Location, Int] = mutable.HashMap[Location, Int]()
    val lineToCustomizedLocationCache: mutable.HashMap[(ReferenceType, Int), Seq[Location]] = mutable.HashMap[(ReferenceType, Int), Seq[Location]]()
    val seenRefTypes: mutable.Set[ReferenceType] = mutable.Set[ReferenceType]()
    val sourceNames: mutable.HashMap[ReferenceType, Option[String]] = mutable.HashMap[ReferenceType, Option[String]]()

    def cachedSourceName(refType: ReferenceType): Option[String] =
      sourceNames.getOrElseUpdate(refType, Try(refType.sourceName()).toOption)

    def clear(): Unit = {
      refTypeNameToFileCache.clear()
      refTypeToElementCache.clear()

      customizedLocationsCache.clear()
      lineToCustomizedLocationCache.clear()
      seenRefTypes.clear()
      sourceNames.clear()
    }
  }
}
