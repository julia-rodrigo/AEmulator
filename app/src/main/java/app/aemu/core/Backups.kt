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
                    val machineId: String, val createdAt: Long, val size: Long,
                    /** "full" (the whole phone, restored as a new machine) or "data" (a quick save of its /data) */
                    val kind: String = "full")

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
                    h.optLong("createdAt", f.lastModified()), f.length(), h.optString("kind", "full"))
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
        if (head.optString("kind", "full") == "data") error("this is a quick save: open Backups on its machine to put it back")
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

    // ------------------------------------------------------------------ quick saves (the phone keeps running)

    /** what a quick save leaves out of /data: compiled code made anew, and app caches */
    private fun quickSkip(rel: String): Boolean {
        if (rel == "data/dalvik-cache" || rel.startsWith("data/dalvik-cache/")) return true
        val t = rel.split('/')
        // data/data/<package>/cache and code_cache
        return t.size == 4 && t[0] == "data" && t[1] == "data" && (t[3] == "cache" || t[3] == "code_cache")
    }

    /** copies a tree keeping links and modes; [own] gets each copied path (relative to the guest root) and its stat */
    private fun copyTree(from: File, to: File, rel: String, skip: (String) -> Boolean, own: (String, android.system.StructStat) -> Unit) {
        val st = runCatching { Os.lstat(from.path) }.getOrNull() ?: return
        if (skip(rel)) return
        own(rel, st)
        when {
            OsConstants.S_ISLNK(st.st_mode) -> runCatching { Os.symlink(Os.readlink(from.path), to.path) }
            OsConstants.S_ISDIR(st.st_mode) -> {
                to.mkdirs()
                for (c in from.list().orEmpty()) copyTree(File(from, c), File(to, c), "$rel/$c", skip, own)
                runCatching { Os.chmod(to.path, (st.st_mode and 0xfff) or 0x1c0) }
            }
            OsConstants.S_ISREG(st.st_mode) -> {
                from.inputStream().use { i -> to.outputStream().use { i.copyTo(it, 1 shl 16) } }
                runCatching { Os.chmod(to.path, st.st_mode and 0xfff) }
                to.setLastModified(st.st_mtime * 1000)
            }
        }
    }

    /**
     * Quick save of a running machine: [pause] stops the guest, its /data (without compiled code and caches) is copied
     * aside, [resume] lets it go on, and the copy is packed into a backup in the background. Restored with [restoreData].
     */
    fun quickSave(ctx: Context, img: GuestImage, label: String, pause: () -> Unit, resume: () -> Unit, progress: (String) -> Unit): File {
        val paths = VmPaths(ctx, img.id)
        val stage = File(ImageStore.imagesDir(ctx).parentFile, "quicksave-${img.id}")
        ImageStore.wipe(stage)
        val owners = HashMap<String, String>()
        runCatching {
            paths.owners.forEachLine { l ->
                val t = l.trim().split(Regex("\\s+"))
                if (t.size >= 4) owners["${t[0]} ${t[1]}"] = "${t[2]} ${t[3]}"
            }
        }
        val ownersByPath = StringBuilder()
        progress("pause")
        pause()
        try {
            copyTree(File(paths.root, "data"), File(stage, "data"), "data", ::quickSkip) { rel, st ->
                owners[key(st)]?.let { ownersByPath.append(rel).append('\t').append(it).append('\n') }
            }
        } catch (t: Throwable) {
            ImageStore.wipe(stage); throw t
        } finally { resume() }
        progress("pack")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        val safe = { x: String -> x.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").trim().take(40) }
        val base = listOf(safe(img.name), safe(label.ifBlank { "Quick save" }), stamp).filter { it.isNotEmpty() }.joinToString(" - ")
        val out = dir(ctx)
        val file = File(out, base + EXT)
        val part = File(out, "$base$EXT.part")
        try {
            TarArchiveOutputStream(GzipCompressorOutputStream(BufferedOutputStream(FileOutputStream(part), 1 shl 16),
                GzipParameters().apply { compressionLevel = 1 })).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                val head = JSONObject().put("format", FORMAT).put("kind", "data").put("label", label.ifBlank { "Quick save" })
                    .put("createdAt", System.currentTimeMillis()).put("bytes", ImageStore.du(stage)).put("image", img.toJson())
                val hb = head.toString(2).toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(HEADER).apply { size = hb.size.toLong() }); tar.write(hb); tar.closeArchiveEntry()
                fun add(f: File, name: String) {
                    val st = runCatching { Os.lstat(f.path) }.getOrNull() ?: return
                    when {
                        OsConstants.S_ISLNK(st.st_mode) -> {
                            val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK); e.linkName = Os.readlink(f.path); e.mode = st.st_mode
                            tar.putArchiveEntry(e); tar.closeArchiveEntry()
                        }
                        OsConstants.S_ISDIR(st.st_mode) -> {
                            tar.putArchiveEntry(TarArchiveEntry("$name/").apply { mode = st.st_mode }); tar.closeArchiveEntry()
                            for (c in f.list().orEmpty().sorted()) add(File(f, c), "$name/$c")
                        }
                        OsConstants.S_ISREG(st.st_mode) -> {
                            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = st.st_size; mode = st.st_mode; setModTime(st.st_mtime * 1000) })
                            f.inputStream().use { it.copyTo(tar, 1 shl 16) }
                            tar.closeArchiveEntry()
                        }
                    }
                }
                add(File(stage, "data"), "data")
                val ob = ownersByPath.toString().toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(OWNERS).apply { size = ob.size.toLong() }); tar.write(ob); tar.closeArchiveEntry()
            }
            if (!part.renameTo(file)) error("could not save ${file.name}")
            return file
        } catch (t: Throwable) {
            part.delete(); throw t
        } finally { ImageStore.wipe(stage) }
    }

    /**
     * Puts a quick save back onto [img] (which must not be running): its /data is replaced by the saved one (compiled
     * code stays), and the ownership table keeps the rows of the other files and gets the saved owners anew.
     */
    fun restoreData(ctx: Context, img: GuestImage, open: () -> InputStream, progress: (String, Float) -> Unit) {
        val head = open().use { readHeader(it) }
        if (head.optString("kind") != "data") error("not a quick save")
        val paths = VmPaths(ctx, img.id)
        val stage = File(ImageStore.imagesDir(ctx).parentFile, "quickrestore-${img.id}")
        ImageStore.wipe(stage); stage.mkdirs()
        val total = head.optLong("bytes", 1).coerceAtLeast(1)
        var done = 0L
        var owners = ""
        try {
            val stageCanon = stage.canonicalPath + "/"
            val dirModes = ArrayList<Pair<File, Int>>()
            open().use { input ->
                val tar = TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input, 1 shl 16)))
                while (true) {
                    val e = tar.nextEntry ?: break
                    val name = e.name.trimEnd('/')
                    if (name == HEADER) continue
                    if (name == OWNERS) { owners = String(tar.readBytes(), Charsets.UTF_8); continue }
                    if (name != "data" && !name.startsWith("data/")) continue
                    if (name.split('/').any { it == ".." }) continue
                    val target = File(stage, name)
                    val parentCanon = (target.parentFile ?: continue).canonicalPath + "/"
                    if (!parentCanon.startsWith(stageCanon) && target.canonicalPath + "/" != stageCanon + "data/") continue
                    when {
                        e.isSymbolicLink -> { target.parentFile?.mkdirs(); runCatching { Os.symlink(e.linkName, target.path) } }
                        e.isDirectory -> { target.mkdirs(); dirModes.add(target to (e.mode and 0xfff)) }
                        e.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { o -> val buf = ByteArray(1 shl 16)
                                while (true) { val n = tar.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n } }
                            runCatching { Os.chmod(target.path, e.mode and 0xfff) }
                            if (e.modTime.time > 0) target.setLastModified(e.modTime.time)
                            progress(name.substringAfterLast('/'), (done.toFloat() / total).coerceIn(0f, 0.95f))
                        }
                    }
                }
            }
            for ((d, m) in dirModes.asReversed()) runCatching { Os.chmod(d.path, m or 0x1c0) }
            if (!File(stage, "data").isDirectory) error("the save holds no data")
            progress("swap", 0.96f)
            // owner rows of the files that stay (everything outside /data), by their current inodes
            val keep = HashSet<String>()
            fun walk(f: File, rel: String) {
                val st = runCatching { Os.lstat(f.path) }.getOrNull() ?: return
                keep.add(key(st))
                if (OsConstants.S_ISDIR(st.st_mode)) for (c in f.list().orEmpty()) {
                    val r = if (rel.isEmpty()) c else "$rel/$c"
                    if (r == "data" || r == "dev" || r == "proc") continue
                    walk(File(f, c), r)
                }
            }
            walk(paths.root, "")
            // the compiled code under /data stays with the machine (it is not in the save) and keeps its rows
            File(paths.root, "data/dalvik-cache").takeIf { it.exists() }?.let { walk(it, "data/dalvik-cache") }
            val data = File(paths.root, "data")
            val dalvik = File(data, "dalvik-cache")
            val newData = File(stage, "data")
            if (dalvik.exists()) { File(newData, "dalvik-cache").let { ImageStore.wipe(it) }; dalvik.renameTo(File(newData, "dalvik-cache")) }
            val old = File(stage, "old-data")
            if (data.exists() && !data.renameTo(old)) error("could not move the current data aside")
            if (!newData.renameTo(data)) { old.renameTo(data); error("could not put the saved data in place") }
            ImageStore.wipe(old)
            val sb = StringBuilder()
            runCatching {
                paths.owners.forEachLine { l ->
                    val t = l.trim().split(Regex("\\s+"))
                    if (t.size >= 4 && "${t[0]} ${t[1]}" in keep) sb.append(l.trim()).append('\n')
                }
            }
            for (l in owners.lineSequence()) {
                val t = l.split('\t'); if (t.size < 2) continue
                val st = runCatching { Os.lstat(File(paths.root, t[0]).path) }.getOrNull() ?: continue
                val ug = t[1].trim().split(' '); if (ug.size < 2) continue
                sb.append(key(st)).append(' ').append(ug[0]).append(' ').append(ug[1]).append('\n')
            }
            paths.owners.writeText(sb.toString())
            ImageStore.get(ctx, img.id)?.let { ImageStore.save(ctx, it.copy(sizeBytes = ImageStore.du(paths.dir))) }
            progress("done", 1f)
        } finally { ImageStore.wipe(stage) }
    }
}
