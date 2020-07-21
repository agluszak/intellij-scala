package org.jetbrains.plugins.scala.compiler

import com.intellij.openapi.util.registry.Registry

object AsyncProf {

  final val Desktop = "/Users/artyom.semyonov/Desktop"
  final val ProfilerDir = s"$Desktop/async-profiler-1.7.1-macos-x64"
  final val ProfilerAgent = s"$ProfilerDir/build/libasyncProfiler.so"
  final val ProfilerScript = s"$ProfilerDir/profiler.sh"

  final val FlameDir = s"$Desktop/flamegraphs"
  final val Interval = 10000000

  def jpsFlame: String =
    s"$FlameDir/jps.svg"

  def serverFlame: String =
    s"$FlameDir/server.svg"

  def profileServer(action: => Unit): Unit = {
    import sys.process._
    val pid: Int = CLibrary.INSTANCE.getpid()
    val startCode = s"$ProfilerScript start -i $Interval $pid".!
    if (startCode != 0) throw new IllegalStateException(s"Start code: $startCode")
    try {
      action
    } finally {
      val stopCode = s"$ProfilerScript stop -f $serverFlame $pid".!
      if (stopCode != 0) throw new IllegalStateException(s"Stop code: $stopCode")
    }
  }
}
