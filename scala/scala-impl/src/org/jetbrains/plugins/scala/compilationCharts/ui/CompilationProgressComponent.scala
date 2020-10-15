package org.jetbrains.plugins.scala.compilationCharts.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{ActionManager, ActionToolbar, AnActionEvent, DefaultActionGroup}
import com.intellij.openapi.project.{DumbAwareAction, Project}
import com.intellij.openapi.util.NlsActions
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.{IdeBorderFactory, SideBorder}
import com.intellij.util.ui.{JBUI, UIUtil}
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.{Icon, JViewport, ScrollPaneConstants}
import org.jetbrains.annotations.Nullable
import org.jetbrains.plugins.scala.compilationCharts.ui.CompilationProgressComponent.{AvailableZooms, DefaultZoomIndex}
import org.jetbrains.plugins.scala.compilationCharts.ui.CompilationProgressPanel.Zoom

import scala.concurrent.duration.DurationInt

/**
 * Main component
 */
class CompilationProgressComponent(project: Project)
  extends BorderLayoutPanel
    with Disposable {

  private var currentZoomIndex: Int = DefaultZoomIndex

  private def currentZoom: Zoom = AvailableZooms(currentZoomIndex)

  private val panel = new CompilationProgressPanel(project, currentZoom)

  private val scrollPane = new JBScrollPane(panel)
  scrollPane.setBackground(UIUtil.getTreeBackground)
  scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
  scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER)
  scrollPane.setBorder(JBUI.Borders.empty)
  scrollPane.getViewport.setScrollMode(JViewport.SIMPLE_SCROLL_MODE)

  addToLeft(createActionToolbar().getComponent)
  addToCenter(scrollPane)

  // TODO dispose?
  override def dispose(): Unit = ()

  private def createActionToolbar(): ActionToolbar = {
    val group = new DefaultActionGroup
    val actions = Seq(
      ZoomInAction,
      ZoomOutAction,
      ResetZoomAction
    )
    actions.foreach(group.add)
    val actionManager = ActionManager.getInstance
    val toolbar = actionManager.createActionToolbar("ScalaCompilationProgress", group, false)
    val border = IdeBorderFactory.createBorder(SideBorder.RIGHT)
    toolbar.getComponent.setBorder(border)
    toolbar
  }

  private abstract class BasicZoomAction(@Nullable @NlsActions.ActionText text: String,
                                         @Nullable @NlsActions.ActionDescription description: String,
                                         icon: Icon)
    extends DumbAwareAction(text, description, icon) {

    final override def update(e: AnActionEvent): Unit =
      e.getPresentation.setEnabled(isEnabled)

    final override def actionPerformed(e: AnActionEvent): Unit = {
      currentZoomIndex = newZoomIndex
      panel.changeZoom(currentZoom)
      CompilationChartsToolWindowFactory.refresh(project)
    }

    protected def isEnabled: Boolean

    protected def newZoomIndex: Int
  }

  // TODO Nls
  // TODO isEnabled

  private object ZoomInAction
    extends BasicZoomAction("zoom in", "ZOOM IN", AllIcons.General.ZoomIn) {

    override protected def isEnabled: Boolean = currentZoomIndex < AvailableZooms.size - 1
    override protected def newZoomIndex: Int = currentZoomIndex + 1
  }

  private object ZoomOutAction
    extends BasicZoomAction("zoom out", "ZOOM OUT", AllIcons.General.ZoomOut) {

    override protected def isEnabled: Boolean = currentZoomIndex > 0
    override protected def newZoomIndex: Int = currentZoomIndex - 1
  }

  private object ResetZoomAction
    extends BasicZoomAction("reset zoom", "RESET ZOOM", AllIcons.General.ActualZoom) {

    override protected def isEnabled: Boolean = currentZoomIndex != DefaultZoomIndex
    override protected def newZoomIndex: Int = DefaultZoomIndex
  }
}

object CompilationProgressComponent {

  private final val DefaultZoomIndex = 4

  private final val AvailableZooms = Seq(
    Zoom(1.minute, 6),
    Zoom(30.second, 4),
    Zoom(10.second, 6),
    Zoom(5.second, 6),
    Zoom(1.second, 5),
    Zoom(500.millis, 6),
    Zoom(250.millis, 4),
  ).sortBy(_.durationStep).reverse
}
