// Test stand-in: "internal storage" is a folder the test chooses (BackupsTest).
@file:Suppress("PackageDirectoryMismatch", "unused")
package android.os

import java.io.File

object Environment {
    const val MEDIA_MOUNTED = "mounted"
    @JvmField var storage: File? = null
    @JvmStatic fun getExternalStorageDirectory(): File? = storage
    @JvmStatic fun getExternalStorageState(): String = MEDIA_MOUNTED
}
