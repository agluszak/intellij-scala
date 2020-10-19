package org.jetbrains.plugins.scala
package lang
package psi
package stubs
package elements

import com.intellij.lang.{ASTNode, Language}
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.{IndexSink, StubElement, StubInputStream, StubOutputStream}
import org.jetbrains.plugins.scala.extensions.ObjectExt
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScFunctionDeclaration, ScFunctionDefinition, ScMacroDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScGivenAlias
import org.jetbrains.plugins.scala.lang.psi.impl.statements.{ScFunctionDeclarationImpl, ScFunctionDefinitionImpl, ScMacroDefinitionImpl}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScGivenAliasImpl
import org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScFunctionStubImpl

/**
  * User: Alexander Podkhalyuzin
  * Date: 14.10.2008
  */
abstract class ScFunctionElementType[Fun <: ScFunction](debugName: String,
                                                        language: Language = ScalaLanguage.INSTANCE)
  extends ScStubElementType[ScFunctionStub[Fun], Fun](debugName, language) {

  override def serialize(stub: ScFunctionStub[Fun], dataStream: StubOutputStream): Unit = {
    dataStream.writeName(stub.getName)
    dataStream.writeBoolean(stub.isDeclaration)
    dataStream.writeNames(stub.annotations)
    dataStream.writeOptionName(stub.typeText)
    dataStream.writeOptionName(stub.bodyText)
    dataStream.writeBoolean(stub.hasAssign)
    dataStream.writeOptionName(stub.implicitConversionParameterClass)
    dataStream.writeBoolean(stub.isLocal)
    dataStream.writeNames(stub.implicitClassNames)
    dataStream.writeBoolean(stub.isTopLevel)
    dataStream.writeOptionName(stub.topLevelQualifier)
  }

  override def deserialize(dataStream: StubInputStream, parent: StubElement[_ <: PsiElement]) =
    new ScFunctionStubImpl(
      parent,
      this,
      name                             = dataStream.readNameString,
      isDeclaration                    = dataStream.readBoolean,
      annotations                      = dataStream.readNames,
      typeText                         = dataStream.readOptionName,
      bodyText                         = dataStream.readOptionName,
      hasAssign                        = dataStream.readBoolean,
      implicitConversionParameterClass = dataStream.readOptionName,
      isLocal                          = dataStream.readBoolean,
      implicitClassNames               = dataStream.readNames,
      isTopLevel                       = dataStream.readBoolean,
      topLevelQualifier                = dataStream.readOptionName
    )

  override def createStubImpl(function: Fun,
                              parentStub: StubElement[_ <: PsiElement]): ScFunctionStub[Fun] = {

    val returnTypeElement = function.returnTypeElement

    val returnTypeText = returnTypeElement.map(_.getText)

    val maybeDefinition = function.asOptionOfUnsafe[ScFunctionDefinition]

    val bodyText = returnTypeText match {
      case Some(_) => None
      case None =>
        maybeDefinition.flatMap(_.body)
          .map(_.getText)
    }

    val annotations = function.annotations
      .map(_.annotationExpr.constructorInvocation.typeElement)
      .asStrings { text =>
        text.substring(text.lastIndexOf('.') + 1)
      }

    val implicitConversionParamClass =
      if (function.isImplicitConversion) ScImplicitStub.conversionParamClass(function)
      else None

    new ScFunctionStubImpl(
      parentStub,
      this,
      name                             = function.name,
      isDeclaration                    = function.isInstanceOf[ScFunctionDeclaration],
      annotations                      = annotations,
      typeText                         = returnTypeText,
      bodyText                         = bodyText,
      hasAssign                        = maybeDefinition.exists(_.hasAssign),
      implicitConversionParameterClass = implicitConversionParamClass,
      isLocal                          = function.containingClass == null,
      implicitClassNames               = ScImplicitStub.implicitClassNames(function, function.returnTypeElement),
      isTopLevel                       = function.isTopLevel,
      topLevelQualifier                = function.topLevelQualifier
    )
  }

  override def indexStub(stub: ScFunctionStub[Fun], sink: IndexSink): Unit = {
    import index.ScalaIndexKeys._
    sink.occurrences(METHOD_NAME_KEY, stub.getName)

    if (stub.isTopLevel)
      stub.topLevelQualifier.foreach(
        sink.fqnOccurence(TOP_LEVEL_FUNCTION_BY_PKG_KEY, _)
      )

    stub.indexImplicits(sink)
  }
}

object FunctionDeclaration extends ScFunctionElementType[ScFunctionDeclaration]("function declaration") {

  override def createElement(node: ASTNode) = new ScFunctionDeclarationImpl(null, null, node)

  override def createPsi(stub: ScFunctionStub[ScFunctionDeclaration]) = new ScFunctionDeclarationImpl(stub, this, null)
}

object FunctionDefinition extends ScFunctionElementType[ScFunctionDefinition]("function definition") {

  override def createElement(node: ASTNode) = new ScFunctionDefinitionImpl(null, null, node)

  override def createPsi(stub: ScFunctionStub[ScFunctionDefinition]) = new ScFunctionDefinitionImpl(stub, this, null)
}

object MacroDefinition extends ScFunctionElementType[ScMacroDefinition]("macro definition") {

  override def createElement(node: ASTNode) = new ScMacroDefinitionImpl(null, null, node)

  override def createPsi(stub: ScFunctionStub[ScMacroDefinition]) = new ScMacroDefinitionImpl(stub, this, null)
}

object GivenAlias extends ScFunctionElementType[ScGivenAlias]("given alias") {
  override def createElement(node: ASTNode): ScGivenAlias = new ScGivenAliasImpl(null, null, node)

  override def createPsi(stub: ScFunctionStub[ScGivenAlias]): ScGivenAlias = new ScGivenAliasImpl(stub, this, null)
}