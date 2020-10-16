package org.jetbrains.plugins.scala.lang.psi.implicits

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiElement, PsiFile, PsiNamedElement, ResolveState}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.{isImplicit, strictlyOrderedByContext}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScCaseClause
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScPatternDefinition}
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveState.ResolveStateExt
import org.jetbrains.plugins.scala.project.ProjectPsiElementExt

private[implicits] final class ImplicitParametersProcessor(override val getPlace: PsiElement,
                                                           override protected val withoutPrecedence: Boolean)
  extends ImplicitProcessor(getPlace, withoutPrecedence) {

  override protected def execute(
    namedElement: PsiNamedElement
  )(implicit
    state: ResolveState
  ): Boolean = {
    if (isImplicit(namedElement) && isAccessible(namedElement)) {
      addResult(
        new ScalaResolveResult(
          namedElement,
          state.substitutorWithThisType,
          importsUsed         = state.importsUsed,
          implicitScopeObject = state.implicitScopeObject
        )
      )
    }

    true
  }

  override def candidatesS: Set[ScalaResolveResult] =
    super.candidatesS.filterNot(c => lowerInFileWithoutType(c) || isContextAncestor(c))

  private def isAccessible(namedElement: PsiNamedElement): Boolean =
    isPredefPriority || ImplicitProcessor.isAccessible(namedElement, getPlace)

  private def lowerInFileWithoutType(c: ScalaResolveResult) = {
    def contextFile(e: PsiElement) = Option(PsiTreeUtil.getContextOfType(e, classOf[PsiFile]))

    def lowerInFile(e: PsiElement) = {
      val resolveFile = contextFile(e)
      val placeFile = contextFile(getPlace)

      resolveFile == placeFile && strictlyOrderedByContext(before = getPlace, after = e, placeFile)
    }

    c.getElement match {
      case fun: ScFunction if fun.returnTypeElement.isEmpty => lowerInFile(fun)
      case pattern @ ScalaPsiUtil.inNameContext(pd: ScPatternDefinition)
          if pd.typeElement.isEmpty =>
        lowerInFile(pattern)
      case _ => false
    }
  }

  private def isContextAncestor(c: ScalaResolveResult): Boolean = {
    val nameContext = ScalaPsiUtil.nameContext(c.element)
    nameContext match {
      case _: ScCaseClause if getPlace.betterMonadicForEnabled => false
      case _                                                   => PsiTreeUtil.isContextAncestor(nameContext, getPlace, false)
    }
  }
}
