package org.jetbrains.plugins.scala.compilationCharts

import java.awt.geom.Rectangle2D
import java.awt.{BasicStroke, Color, Graphics, Graphics2D, Point, Shape}

import scala.annotation.tailrec
import scala.util.Using
import scala.util.Using.Releasable

// TODO better place for this utils?
package object ui {

  implicit object GraphicsReleasable extends Releasable[Graphics] {
    override def release(resource: Graphics): Unit = resource.dispose()
  }

  implicit class GraphicsExt(private val graphics: Graphics2D) extends AnyVal {

    /**
     * Width of the vertical and horizontal lines.
     */
    def linePixels: (Double, Double) = {
      val transform = graphics.getTransform
      val xWidth = 1 / transform.getScaleX
      val yWidth = 1 / transform.getScaleY
      (xWidth, yWidth)
    }

    def doInClip[A](clip: Shape)
                   (action: Graphics2D => A): A =
      Using.resource(graphics.create().asInstanceOf[Graphics2D]) { clipGraphics =>
        clipGraphics.setClip(clip)
        action(clipGraphics)
      }

    def printRect(color: Color): Unit = {
      val clipBounds = graphics.getClipBounds
      graphics.setColor(color)
      graphics.fillRect(clipBounds.x, clipBounds.y, clipBounds.width, clipBounds.height)
    }

    def getTextRect(text: String, align: TextAlign = TextAlign.Center): Rectangle2D = {
      val clipBounds = graphics.getClipBounds
      val fontMetrics = graphics.getFontMetrics(graphics.getFont)
      val stringBounds = fontMetrics.getStringBounds(text, graphics)
      val centeredDeltaX = align match {
        case TextAlign.Center => clipBounds.width / 2 - stringBounds.getBounds.width / 2
        case TextAlign.Left => 0
        case TextAlign.Right => clipBounds.width - stringBounds.getBounds.width
      }
      val x = clipBounds.x + centeredDeltaX
      val y = clipBounds.y + clipBounds.height / 2 + fontMetrics.getAscent * 2 / 5
      new Rectangle2D.Double(x, y, stringBounds.getWidth, stringBounds.getHeight)
    }

    def printText(text: String, color: Color, align: TextAlign = TextAlign.Center): Unit = {
      val textRect = getTextRect(text, align)
      printText(text, color, textRect.getX, textRect.getY)
    }

    def printText(text: String, color: Color, x: Double, y: Double): Unit = {
      graphics.setColor(color)
      graphics.drawString(text, x.toFloat, y.toFloat)
    }

    def printReducedText(text: String, color: Color, align: TextAlign): Rectangle2D = {
      val clipBounds = graphics.getClipBounds
      val fontMetrics = graphics.getFontMetrics(graphics.getFont)

      @tailrec
      def rec(fragment: String, isFull: Boolean): Rectangle2D = if (fragment.nonEmpty) {
        val toPrint = if (isFull) fragment else s"$fragment…"
        val stringBounds = fontMetrics.getStringBounds(toPrint, graphics)
        if (stringBounds.getWidth <= clipBounds.getWidth) {
          graphics.printText(toPrint, color, align)
          stringBounds
        } else {
          rec(fragment.init, isFull = false)
        }
      } else {
        new Rectangle2D.Double(clipBounds.x, clipBounds.y, 0.0, clipBounds.getHeight)
      }

      rec(text, isFull = true)
    }

    def printTextIfFits(text: String, color: Color, align: TextAlign): Unit = {
      val clipBounds = graphics.getClipBounds
      val fontMetrics = graphics.getFontMetrics(graphics.getFont)
      val stringBounds = fontMetrics.getStringBounds(text, graphics)
      if (stringBounds.getWidth <= clipBounds.getWidth)
        graphics.printText(text, color, align)
    }

    def printVerticalLine(point: Point, length: Int, color: Color, dashLength: Option[Float] = None): Unit =
      printLine(point, length, color, dashLength, vertical = true)

    def printHorizontalLine(point: Point, length: Int, color: Color, dashLength: Option[Float] = None): Unit =
      printLine(point, length, color, dashLength, vertical = false)

    private def printLine(point: Point,
                          length: Int,
                          color: Color,
                          dashLength: Option[Float],
                          vertical: Boolean): Unit = {
      val (xBorderPixels, yBorderPixels) = linePixels
      val width = if (vertical) xBorderPixels else yBorderPixels
      val dashes = dashLength.map(Array(_)).orNull
      val stroke = new BasicStroke(width.toFloat, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, dashes, 0)

      val x1 = point.x
      val y1 = point.y
      val x2 = if (vertical) x1 else x1 + length
      val y2 = if (vertical) y1 + length else y1

      Using.resource(graphics.create().asInstanceOf[Graphics2D]) { lineGraphics =>
        lineGraphics.setStroke(stroke)
        lineGraphics.setColor(color)
        lineGraphics.drawLine(x1, y1, x2, y2)
      }
    }
  }
}
