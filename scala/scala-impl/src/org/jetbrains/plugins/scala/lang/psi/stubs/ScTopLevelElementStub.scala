package org.jetbrains.plugins.scala.lang.psi.stubs

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StubElement

trait ScTopLevelElementStub[T <: PsiElement] extends StubElement[T] {
  def isTopLevel: Boolean
  def topLevelQualifier: Option[String]
}
