package org.jetbrains.plugins.scala.projectHighlighting

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.performance.DownloadingAndImportingTestCase

/**
  * @author mutcianm
  * @since 16.05.17.
  */
abstract class GithubSbtAllProjectHighlightingTest extends DownloadingAndImportingTestCase with AllProjectHighlightingTest {
  override def getProject: Project = myProject

  override def getProjectFixture = codeInsightFixture

  def testHighlighting(): Unit = doAllProjectHighlightingTest()
}
