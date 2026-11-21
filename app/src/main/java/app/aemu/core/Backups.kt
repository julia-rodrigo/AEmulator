package app.aemu.core

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backups of a whole virtual phone: firmware, everything inside it (apps, their data, game saves), its settings and
 * its memory-card folder, in one file (.aemu: a gzip'd tar). They are kept in Internal storage/AEmulator/Backups,
 * which outlives an uninstall and can be copied to a computer and back; any number of them, one per save.
 *
 * Restoring makes a new machine, so a restore never overwrites what is there. The ownership table (dhd.owners) is
 * keyed by inode, which a copy does not keep: the backup stores the owners by path and the restore writes them anew.
 */
object Backups {
    const val EXT = ".aemu"
    private const val HEADER = "aemu-backup.json"
    private const val OWNERS = "aemu-owners.txt"
    private const val FORMAT = 1

    data class Slot(val file: File, val label: String, val machine: String, val release: String,
                    val machineId: String, val createdAt: Long, val size: Long)

    fun dir(ctx: Context): File {
        val pub = runCatching { File(Environment.getExternalStorageDirectory(), "AEmulator/Backups") }.getOrNull()
        if (pub != null && runCatching { pub.mkdirs(); pub.isDirectory && pub.canWrite() }.getOrDefault(false)) return pub
        return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Backups").apply { mkdirs() }
    }

    fun list(ctx: Context): List<Slot> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().mapNotNull { f ->
            runCatching {
                val h = f.inputStream().use { readHeader(it) }
                val img = h.getJSONObject("image")
                Slot(f, h.optString("label"), img.optString("name"), img.optString("release"), img.optString("id"),
                    h.optLong("createdAt", f.lastModified()), f.length())
            }.getOrNull()
        }.sortedByDescending { it.createdAt }

    private fun readHeader(input: InputStream): JSONObject {
        val tar = TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input, 1 shl 16)))
        val e = tar.nextEntry ?: error("empty file")
        if (e.name != HEADER) error("not an AEmulator backup")
        return JSONObject(String(tar.readBytes(), Charsets.UTF_8))
    }

    private fun key(st: android.system.StructStat) = String.format("%016x %016x", st.st_dev, st.st_ino)

    private fun isLink(f: File) = runCatching { OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)

    /** Writes a backup of [img] (the machine must not be running) and returns its file. */
    fun create(ctx: Context, img: GuestImage, label: String, cancelled: () -> Boolean, progress: (String, Float) -> Unit): File {
        val paths = VmPaths(ctx, img.id)
        val card = Sdcard.hostDir(paths)
        val sys = File(paths.root, "system")
        val sysReal = if (isLink(sys)) sys.canonicalFile else null
        val total = (ImageStore.du(paths.dir) + (sysReal?.let { ImageStore.du(it) } ?: 0) + (card?.let { ImageStore.du(it) } ?: 0)).coerceAtLeast(1)
        val out = dir(ctx)
        if (total / 2 + (100L shl 20) > out.usableSpace) error("not enough space for the backup: up to ${total shr 20} MB, free ${out.usableSpace shr 20} MB")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        val safe = { s: String -> s.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").trim().take(40) }
        val base = listOf(safe(img.name), safe(label), stamp).filter { it.isNotEmpty() }.joinToString(" - ")
        val file = File(out, base + EXT)
        val part = File(out, "$base$EXT.part")
        // owners by inode -> by path, read while the files still have their inodes
        val owners = HashMap<String, String>()
        runCatching {
            paths.owners.forEachLine { l ->
                val t = l.trim().split(Regex("\\s+"))
                if (t.size >= 4) owners["${t[0]} ${t[1]}"] = "${t[2]} ${t[3]}"
            }
        }
        val ownersByPath = StringBuilder()
        var done = 0L
        try {
            val gz = GzipParameters().apply { compressionLevel = 1 }
            TarArchiveOutputStream(GzipCompressorOutputStream(BufferedOutputStream(FileOutputStream(part), 1 shl 16), gz)).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                val head = JSONObject().put("format", FORMAT).put("label", label).put("createdAt", System.currentTimeMillis())
                    .put("bytes", total).put("image", img.toJson()).put("card", card != null)
                val hb = head.toString(2).toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(HEADER).apply { size = hb.size.toLong() })
                tar.write(hb); tar.closeArchiveEntry()

                fun add(f: File, name: String, rootRel: String?) {
                    if (cancelled()) error("cancelled")
                    val st = runCatching { Os.lstat(f.path) }.getOrNull() ?: return
                    if (rootRel != null) owners[key(st)]?.let { ownersByPath.append(rootRel).append('\t').append(it).append('\n') }
                    when {
                        OsConstants.S_ISLNK(st.st_mode) -> {
                            if (rootRel == "system" && sysReal != null) { add(sysReal, name, rootRel); return }
                            val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                            e.linkName = Os.readlink(f.path); e.mode = st.st_mode
                            tar.putArchiveEntry(e); tar.closeArchiveEntry()
                        }
                        OsConstants.S_ISDIR(st.st_mode) -> {
                            tar.putArchiveEntry(TarArchiveEntry("$name/").apply { mode = st.st_mode }); tar.closeArchiveEntry()
                            for (c in f.list().orEmpty().sorted()) {
                                val r = rootRel?.let { if (it.isEmpty()) c else "$it/$c" }
                                // the machine's runtime state is made anew at each start
                                if (rootRel == "" && (c == "dev" || c == "proc")) continue
                                add(File(f, c), "$name/$c", r)
                            }
                        }
                        OsConstants.S_ISREG(st.st_mode) -> {
                            val e = TarArchiveEntry(name).apply { size = st.st_size; mode = st.st_mode; setModTime(st.st_mtime * 1000) }
                            tar.putArchiveEntry(e)
                            f.inputStream().use { i ->
                                val buf = ByteArray(1 shl 16)
                                var left = st.st_size
                                while (left > 0) {
                                    val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (n < 0) break
                                    tar.write(buf, 0, n); left -= n; done += n
                                    if (cancelled()) error("cancelled")
                                }
                                // a file that shrank while read: pad, the size is already in the header
                                while (left > 0) { val n = minOf(buf.size.toLong(), left).toInt(); java.util.Arrays.fill(buf, 0); tar.write(buf, 0, n); left -= n }
                            }
                            tar.closeArchiveEntry()
                            progress(name.substringAfterLast('/'), (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                progress("system", 0f)
                for (c in paths.dir.list().orEmpty().sorted()) {
                    if (c == "run" || c == "snapshots" || c.endsWith(".tmp")) continue
                    add(File(paths.dir, c), "image/$c", if (c == "root") "" else null)
                }
                if (card != null) { progress("memory card", (done.toFloat() / total).coerceIn(0f, 1f)); add(card, "card", null) }
                val ob = ownersByPath.toString().toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(OWNERS).apply { size = ob.size.toLong() })
                tar.write(ob); tar.closeArchiveEntry()
            }
            if (!part.renameTo(file)) error("could not save ${file.name}")
            return file
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** Restores a backup as a new machine and returns it. */
    fun restore(ctx: Context, open: () -> InputStream, cancelled: () -> Boolean, progress: (String, Float) -> Unit): GuestImage {
        val head = open().use { readHeader(it) }
        if (head.optInt("format") > FORMAT) error("this backup was made by a newer AEmulator")
        val src = GuestImage.fromJson(head.getJSONObject("image"))
        val total = head.optLong("bytes", 1).coerceAtLeast(1)
        if (total + (200L shl 20) > ImageStore.imagesDir(ctx).usableSpace)
            error("not enough space: need ${total shr 20} MB, free ${ImageStore.imagesDir(ctx).usableSpace shr 20} MB")
        val id = ImageStore.newId(ctx)
        val paths = VmPaths(ctx, id)
        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(head.optLong("createdAt", System.currentTimeMillis())))
        val label = head.optString("label").ifBlank { stamp }
        val img = src.copy(id = id, name = "${src.name} ($label)".take(80),
            baseId = src.baseId.takeIf { it.isNotEmpty() && ImageStore.get(ctx, it) != null } ?: "",
            createdAt = System.currentTimeMillis(), lastBootMs = 0)
        val card = Sdcard.dirFor(ctx, img)
        var done = 0L
        var owners = ""
        try {
            paths.dir.mkdirs()
            val dirCanon = paths.dir.canonicalPath + "/"
            val cardCanon = card?.canonicalPath?.plus("/")
            val dirModes = ArrayList<Pair<File, Int>>()
            open().use { input ->
                val tar = TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input, 1 shl 16)))
                while (true) {
                    if (cancelled()) error("cancelled")
                    val e = tar.nextEntry ?: break
                    val name = e.name.trimEnd('/')
                    if (name == HEADER) continue
                    if (name == OWNERS) { owners = String(tar.readBytes(), Charsets.UTF_8); continue }
                    if (name.split('/').any { it == ".." }) continue
                    val target = when {
                        name == "image" -> paths.dir
                        name.startsWith("image/") -> File(paths.dir, name.removePrefix("image/"))
                        name == "card" -> card ?: continue
                        name.startsWith("card/") -> File(card ?: continue, name.removePrefix("card/"))
                        else -> continue
                    }
                    // never write outside the new machine or its card folder (through a link in the archive)
                    val parentCanon = (target.parentFile ?: continue).canonicalPath + "/"
                    if (!parentCanon.startsWith(dirCanon) && (cardCanon == null || !parentCanon.startsWith(cardCanon))
                        && target.canonicalPath != paths.dir.canonicalPath && target.path != card?.path) continue
                    when {
                        e.isSymbolicLink -> { target.parentFile?.mkdirs(); target.delete(); runCatching { Os.symlink(e.linkName, target.path) } }
                        e.isDirectory -> { target.mkdirs(); dirModes.add(target to (e.mode and 0xfff)) }
                        e.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { o ->
                                val buf = ByteArray(1 shl 16)
                                while (true) {
                                    val n = tar.read(buf); if (n < 0) break
                                    o.write(buf, 0, n); done += n
                                    if (cancelled()) error("cancelled")
                                }
                            }
                            runCatching { Os.chmod(target.path, e.mode and 0xfff) }
                            if (e.modTime.time > 0) target.setLastModified(e.modTime.time)
                            progress(name.substringAfterLast('/'), (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            // directories last: a read-only one would have refused its own files
            for ((d, m) in dirModes.asReversed()) runCatching { Os.chmod(d.path, m or 0x1c0) }
            File(paths.root, "dev").mkdirs()
            // the owners again, for the inodes of the new files
            val sb = StringBuilder()
            for (l in owners.lineSequence()) {
                val t = l.split('\t'); if (t.size < 2) continue
                val st = runCatching { Os.lstat(File(paths.root, t[0]).path) }.getOrNull() ?: continue
                val ug = t[1].trim().split(' ')
                if (ug.size < 2) continue
                sb.append(key(st)).append(' ').append(ug[0]).append(' ').append(ug[1]).append('\n')
            }
            paths.owners.writeText(sb.toString())
            val saved = img.copy(sizeBytes = ImageStore.du(paths.dir))
            ImageStore.save(ctx, saved)
            return saved
        } catch (t: Throwable) {
            ImageStore.wipe(paths.dir)
            throw t
        }
    }
}
