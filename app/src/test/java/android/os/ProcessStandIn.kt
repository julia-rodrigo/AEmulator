// Test stand-in for the parts of android.os.Process the backup code uses: real signals to host processes.
@file:Suppress("PackageDirectoryMismatch", "unused")
package android.os

object Process {
    @JvmStatic fun myPid(): Int = java.io.File("/proc/self").canonicalFile.name.toInt()
    @JvmStatic fun sendSignal(pid: Int, signal: Int) { ProcessBuilder("kill", "-$signal", "$pid").start().waitFor() }
}
