package org.jetbrains.plugins.scala.compiler;

import com.sun.jna.Library;
import com.sun.jna.Native;

interface CLibrary extends Library {
    CLibrary INSTANCE = (CLibrary) Native.loadLibrary("c", CLibrary.class);
    int getpid ();
}
