package org.jetbrains.plugins.scala.compilationCharts.ui

import java.awt.geom.Rectangle2D
import java.awt.{Color, Dimension, Graphics, Graphics2D, Point}

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.UIUtil.FontColor
import org.jetbrains.plugins.scala.compilationCharts.ui.CompilationProgressPanel.Zoom
import org.jetbrains.plugins.scala.compilationCharts.{CompilationProgressInfo, CompilationProgressState, CompilationProgressStateManager}
import org.jetbrains.plugins.scala.compiler.{CompilationUnitId, ScalaCompileServerSettings}

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationLong, FiniteDuration}

class CompilationProgressPanel(project: Project, defaultZoom: Zoom)
  extends JBPanelWithEmptyText
    with Disposable {

  import org.jetbrains.plugins.scala.compilationCharts.ui.CompilationProgressPanel._

  private var currentZoom: Zoom = defaultZoom

  setBackground(BackgroundColor)

  override def dispose(): Unit = ()

  override def paintComponent(g: Graphics): Unit = {
    val graphics = g.asInstanceOf[Graphics2D]

    val state = CompilationProgressStateManager.get(project)
    val segments = calculateSegments(state)
    val segmentGroups = groupSegments(segments)

    graphics.printRect(BackgroundColor)
    segmentGroups.flatten.map(_.to).maxOption.foreach { progressDuration =>
      val parallelism = getCompileServerParallelism
      val maxDuration = progressDuration + DurationStepsAhead * currentZoom.durationStep
      val preferredWidth = currentZoom.toPixels(maxDuration) + LeftAxisWidth + RightAxisWidth

      printDurationMarks(graphics, preferredWidth)
      segmentGroups.zipWithIndex.foreach { case (group, row) =>
        group.foreach(printSegment(_, row + 1, graphics))
      }
      printProgressLine(graphics, progressDuration)
      printLeftAxis(graphics, parallelism)
      printRightAxis(graphics)
      printTopAxis(graphics)
      printBottomAxis(graphics)
      printMaxParallelismLine(graphics, parallelism)

      setPreferredSize(new Dimension(preferredWidth, TotalHeight))
      revalidate()
    }
  }

  def changeZoom(newZoom: Zoom): Unit =
    currentZoom = newZoom

  private def printSegment(segment: Segment,
                           row: Int,
                           graphics: Graphics2D): Unit = {
    val Segment(CompilationUnitId(moduleName, testScope), from, to, progress) = segment
    val (xBorderPixels, yBorderPixels) = graphics.linePixels

    val x = currentZoom.toPixels(from) + LeftAxisWidth
    val y = TotalHeight - RowHeight * row - BottomAxisHeight + yBorderPixels
    val width = currentZoom.toPixels(to - from) - xBorderPixels
    val height = RowHeight - yBorderPixels
    val clip = new Rectangle2D.Double(x, y, width, height)
    val color = ColorUtil.mix(
      BackgroundColor,
      if (testScope) TestModuleColor else ModuleColor,
      ModuleColorAlpha
    )
    val moduleNameRect = graphics.doInClip(clip) { segmentGraphics =>
      segmentGraphics.printRect(color)
      segmentGraphics.printReducedText(s" $moduleName", TextColor, TextAlign.Left)
    }

    if (progress < 1.0) {
      val progressString = s"${math.round(progress * 100)}% "
      val progressStringClip = new Rectangle2D.Double(
        clip.getX + moduleNameRect.getWidth,
        clip.getY,
        clip.getWidth - moduleNameRect.getWidth,
        clip.getHeight
      )
      graphics.doInClip(progressStringClip)(_.printTextIfFits(progressString, TextColor, TextAlign.Right))
    }
  }

  private def printProgressLine(graphics: Graphics2D,
                                compilationDuration: FiniteDuration): Unit = {
    val linePoint = new Point(currentZoom.toPixels(compilationDuration) + LeftAxisWidth, TopAxisHeight)
    graphics.printVerticalLine(linePoint, RowCount * RowHeight + DashLength, TextColor)
  }

  private def printDurationMarks(graphics: Graphics2D, preferredWidth: Int): Unit = {
    val clipBounds = graphics.getClipBounds
    val minX = clipBounds.x + LeftAxisWidth
    val maxX = clipBounds.x + clipBounds.width - RightAxisWidth

    def printLine(x: Int): Unit = {
      val point = new Point(x, TopAxisHeight)
      val length = RowCount * RowHeight + DashLength
      graphics.printVerticalLine(point, length, InactiveTextColor, dashLength = Some(DashLength.toFloat))
    }

    def printLabel(x: Int, value: FiniteDuration): Unit = {
      val halfOfWidth = clipBounds.getWidth
      val clip = new Rectangle2D.Double(
        x - halfOfWidth,
        TotalHeight - BottomAxisHeight + DashLength,
        2 * halfOfWidth,
        RowHeight
      )
      val text = stringify(value)
      val rect = graphics.doInClip(clip)(_.getTextRect(text))
      if (minX <= rect.getX && rect.getX + rect.getWidth <= maxX)
        graphics.printText(text, TextColor, rect.getX, rect.getY)
    }

    val from = LeftAxisWidth
    val to = math.max(clipBounds.width, preferredWidth)
    val step = currentZoom.toPixels(currentZoom.durationStep)
    Range(from, to, step).zipWithIndex.foreach { case (x, i) =>
      graphics.printVerticalLine(new Point(x, TotalHeight - BottomAxisHeight), DashLength, TextColor)
      if (i % currentZoom.durationLabelPeriod == 0) {
        printLine(x)
        printLabel(x, i * currentZoom.durationStep)
      }
    }
  }
}

object CompilationProgressPanel {

  final case class Zoom(durationStep: FiniteDuration,
                        durationLabelPeriod: Int) {

    private lazy val scale = 3.5e7 / durationStep.toNanos

    def toPixels(duration: FiniteDuration): Int = (scale * duration.toMillis).round.toInt
  }

  private final val TextColor = UIUtil.getLabelFontColor(FontColor.NORMAL)
  private final val InactiveTextColor = UIUtil.getInactiveTextColor
  private final val BackgroundColor = UIUtil.getTreeBackground
  private final val TestModuleColor = new Color(98, 181, 67)
  private final val ModuleColor = new Color(64, 182, 224)
  private final val ModuleColorAlpha = 0.5

  private final val RowCount = 10 // TODO should it depends on max available parallelism settings?

  private final val RowHeight = (new JBTable().getRowHeight * 1.5).round.toInt
  private final val LeftAxisWidth = RowHeight
  private final val RightAxisWidth = RowHeight
  private final val TopAxisHeight = RowHeight * 2
  private final val BottomAxisHeight = RowHeight * 2
  private final val DashLength = RowHeight / 5

  private final val TotalHeight = RowCount * RowHeight + BottomAxisHeight + TopAxisHeight

  private final val DurationStepsAhead = 2

  private def printLeftAxis(graphics: Graphics2D, parallelism: Int): Unit = {
    val clipBounds = graphics.getClipBounds

    val backgroundClip = new Rectangle2D.Double(
      clipBounds.x,
      clipBounds.y,
      LeftAxisWidth,
      TotalHeight
    )
    graphics.doInClip(backgroundClip)(_.printRect(BackgroundColor))

    val linePoint = new Point(clipBounds.x + LeftAxisWidth, clipBounds.y + TopAxisHeight)
    val lineLength = RowCount * RowHeight + DashLength
    graphics.printVerticalLine(linePoint, lineLength, TextColor)

    for (row <- 1 to RowCount) {
      val textClip = new Rectangle2D.Double(
        clipBounds.x,
        TotalHeight - RowHeight * row - BottomAxisHeight,
        LeftAxisWidth,
        RowHeight
      )
      val color = if (row <= parallelism) TextColor else InactiveTextColor
      graphics.doInClip(textClip)(_.printText(row.toString, color))
    }
  }

  private def printRightAxis(graphics: Graphics2D): Unit = {
    val clipBounds = graphics.getClipBounds

    val backgroundClip = new Rectangle2D.Double(
      clipBounds.x + clipBounds.width - RightAxisWidth,
      clipBounds.y,
      RightAxisWidth,
      TotalHeight
    )
    graphics.doInClip(backgroundClip)(_.printRect(BackgroundColor))

    val linePoint = new Point(clipBounds.x + clipBounds.width - LeftAxisWidth, clipBounds.y + TopAxisHeight)
    val lineLength = RowCount * RowHeight + DashLength
    graphics.printVerticalLine(linePoint, lineLength, TextColor)
  }

  private def printTopAxis(graphics: Graphics2D): Unit = {
    val clipBounds = graphics.getClipBounds

    val linePoint = new Point(clipBounds.x + LeftAxisWidth, clipBounds.y + TopAxisHeight)
    val lineLength = clipBounds.width - LeftAxisWidth - RightAxisWidth
    graphics.printHorizontalLine(linePoint, lineLength, TextColor)
  }

  private def printBottomAxis(graphics: Graphics2D): Unit = {
    val clipBounds = graphics.getClipBounds

    val linePoint = new Point(clipBounds.x + LeftAxisWidth, clipBounds.y + TotalHeight - BottomAxisHeight)
    val lineLength = clipBounds.width - LeftAxisWidth - RightAxisWidth
    graphics.printHorizontalLine(linePoint, lineLength, TextColor)
  }

  // TODO What to show if Compile server is disabled?
  private def printMaxParallelismLine(graphics: Graphics2D, parallelism: Int): Unit = {
    val clipBounds = graphics.getClipBounds

    val x = clipBounds.x + LeftAxisWidth - DashLength
    val y = clipBounds.y + TotalHeight - RowHeight * parallelism - BottomAxisHeight
    val point = new Point(x, y)
    val length = clipBounds.width - LeftAxisWidth - RightAxisWidth + DashLength
    graphics.printHorizontalLine(point, length, TextColor, dashLength = Some(DashLength.toFloat))
  }

  private def getCompileServerParallelism: Int = {
    val settings = ScalaCompileServerSettings.getInstance
    if (settings.COMPILE_SERVER_PARALLEL_COMPILATION) settings.COMPILE_SERVER_PARALLELISM else 1
  }

  private def stringify(duration: FiniteDuration): String = {
    val minutes = duration.toMinutes
    val seconds = duration.toSeconds % 60
    val minutesStr = Option(minutes).filter(_ > 0).map(_.toString + "m")
    val secondsStr = Option(seconds).filter(_ > 0).map(_.toString + "s")
    val result = Seq(minutesStr, secondsStr).flatten.mkString(" ")
    if (result.nonEmpty) result else "0s"
  }

  private final case class Segment(unitId: CompilationUnitId,
                                   from: FiniteDuration,
                                   to: FiniteDuration,
                                   progress: Double)

  private def calculateSegments(state: CompilationProgressState): Seq[Segment] = {
    val sortedState = state.toSeq.sortBy(_._2.startTime)
    sortedState.headOption.map(_._2.startTime) match {
      case Some(minTime) =>
        val maxTime = state.values.flatMap { info =>
          Seq(info.startTime, info.updateTime) ++ info.finishTime.toSeq
        }.max
        sortedState.map { case (unitId, CompilationProgressInfo(startTime, finishTime, _, progress)) =>
          val from = (startTime - minTime).nanos
          val to = (finishTime.getOrElse(maxTime) - minTime).nanos
          Segment(
            unitId = unitId,
            from = from,
            to = to,
            progress = progress
          )
        }
      case None =>
        Seq.empty
    }
  }

  private def groupSegments(intervals: Seq[Segment]): Seq[Seq[Segment]] = {

    @tailrec
    def rec(groups: Seq[Seq[Segment]],
            segments: Seq[Segment]): Seq[Seq[Segment]] = segments match {
      case Seq() => groups
      case Seq(interval, remainIntervals@_*) => rec(insert(groups, interval), remainIntervals)
    }

    def insert(groups: Seq[Seq[Segment]],
               interval: Segment): Seq[Seq[Segment]] = groups match {
      case Seq() =>
        Seq(Seq(interval))
      case Seq(group, remainGroups@_*) =>
        if (group.last.to < interval.from)
          (group :+ interval) +: remainGroups
        else
          group +: insert(remainGroups, interval)
    }

    rec(Seq.empty, intervals)
  }
}