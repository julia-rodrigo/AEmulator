// Test stand-ins for android.system on top of the host's real file system (Linux "unix:" attributes), so code that
// uses Os.lstat/symlink/readlink/chmod runs in plain JVM tests (BackupsTest). Only what the tested code uses.
@file:Suppress("PackageDirectoryMismatch", "unused", "PropertyName")
package android.system

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

class StructStat(@JvmField val st_dev: Long, @JvmField val st_ino: Long, @JvmField val st_mode: Int,
                 @JvmField val st_size: Long, @JvmField val st_mtime: Long)

class ErrnoException(functionName: String, @JvmField val errno: Int) : Exception("$functionName failed: $errno")

object OsConstants {
    @JvmField val S_IFMT = "170000".toInt(8)
    @JvmField val S_IFIFO = "10000".toInt(8)
    @JvmStatic fun S_ISLNK(m: Int) = m and S_IFMT == "120000".toInt(8)
    @JvmStatic fun S_ISDIR(m: Int) = m and S_IFMT == "40000".toInt(8)
    @JvmStatic fun S_ISREG(m: Int) = m and S_IFMT == "100000".toInt(8)
}

object Os {
    @JvmStatic fun lstat(p: String): StructStat = try {
        val a = Files.readAttributes(Paths.get(p), "unix:dev,ino,mode,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS)
        StructStat((a["dev"] as Number).toLong(), (a["ino"] as Number).toLong(), a["mode"] as Int,
            (a["size"] as Number).toLong(), (a["lastModifiedTime"] as FileTime).toMillis() / 1000)
    } catch (e: Exception) { throw ErrnoException("lstat", 2) }
    @JvmStatic fun readlink(p: String): String = try { Files.readSymbolicLink(Paths.get(p)).toString() } catch (e: Exception) { throw ErrnoException("readlink", 22) }
    @JvmStatic fun symlink(target: String, link: String) { try { Files.createSymbolicLink(Paths.get(link), Paths.get(target)) } catch (e: Exception) { throw ErrnoException("symlink", 17) } }
    @JvmStatic fun chmod(p: String, mode: Int) { try { Files.setAttribute(Paths.get(p), "unix:mode", mode) } catch (e: Exception) { throw ErrnoException("chmod", 1) } }
}
