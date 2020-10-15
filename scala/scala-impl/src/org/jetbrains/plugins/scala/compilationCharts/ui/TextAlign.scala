package org.jetbrains.plugins.scala.compilationCharts.ui

sealed trait TextAlign

object TextAlign {

  final case object Center extends TextAlign
  final case object Left extends TextAlign
  final case object Right extends TextAlign
}
