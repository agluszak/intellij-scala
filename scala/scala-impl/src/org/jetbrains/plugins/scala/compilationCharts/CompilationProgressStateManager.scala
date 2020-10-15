package org.jetbrains.plugins.scala.compilationCharts

import com.intellij.openapi.components.{Service, ServiceManager}
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.compiler.CompilationUnitId

object CompilationProgressStateManager {

  def get(project: Project): CompilationProgressState =
    mutableState(project).state

  def update(project: Project, newState: CompilationProgressState): CompilationProgressState = {
    mutableState(project).state = newState
    newState
  }

  def erase(project: Project): CompilationProgressState =
    update(project, Map.empty)

  private def mutableState(project: Project): MutableState =
    ServiceManager.getService(project, classOf[MutableState])

  @Service
  private final class MutableState {
    var state: CompilationProgressState = //Map.empty
      Map( // TODO Map.empty
        (CompilationUnitId("core",false),CompilationProgressInfo(104202365550027L,Some(104222016823168L),104222016823168L,1.0)),
        (CompilationUnitId("cli",false),CompilationProgressInfo(104222894263677L,Some(104224510650809L),104224510650809L,1.0)),
        (CompilationUnitId("core",true),CompilationProgressInfo(104222897655473L,Some(104225900294717L),104225900294717L,1.0)),
        (CompilationUnitId("gui",false),CompilationProgressInfo(104222900049260L,Some(104226178368695L),104226178368695L,1.0))
      )
  }
}
