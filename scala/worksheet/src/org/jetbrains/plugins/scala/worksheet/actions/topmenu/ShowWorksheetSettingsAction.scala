package org.jetbrains.plugins.scala.worksheet.actions.topmenu

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import javax.swing.Icon
import org.jetbrains.plugins.scala.actions.ScalaActionUtil
import org.jetbrains.plugins.scala.worksheet.WorksheetBundle
import org.jetbrains.plugins.scala.worksheet.settings.ui.WorksheetSettingsDialog

class ShowWorksheetSettingsAction extends AnAction with TopComponentAction {

  override def genericText: String = WorksheetBundle.message("worksheet.settings.button")

  override def actionIcon: Icon = AllIcons.General.Settings

  override def actionPerformed(e: AnActionEvent): Unit = {
    val fileOpt = ScalaActionUtil.getFileFrom(e).orElse(getSelectedFile(e.getProject))
    fileOpt.foreach { file =>
      val dialog = new WorksheetSettingsDialog(file)
      dialog.show()
    }
  }
}
