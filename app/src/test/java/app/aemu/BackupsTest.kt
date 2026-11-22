package app.aemu

import android.content.ContextWrapper
import android.os.Environment
import android.system.Os
import app.aemu.core.Backups
import app.aemu.core.Engine
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore
import app.aemu.core.VmPaths
import app.aemu.core.VmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Back up a machine (firmware tree, links, modes, owners, memory card), restore it as a new one, compare. */
class BackupsTest {
    private class Ctx(val base: File) : ContextWrapper(null) {
        override fun getFilesDir() = File(base, "files").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?) = File(base, "ext-files").apply { mkdirs() }
    }

    @Test fun roundTrip() {
        val tmp = Files.createTempDirectory("aemu-backup").toFile()
        Environment.storage = File(tmp, "storage").apply { mkdirs() }
        val ctx = Ctx(tmp)
        val img = GuestImage(id = "abc234", name = "Google Nexus 4", release = "4.4.4", api = 19, brand = "google", model = "Nexus 4",
            skin = "AOSP", engine = Engine.KK, abi = "armeabi-v7a", bootclasspath = "/system/framework/core.jar",
            exports = emptyMap(), dirs = emptyList(), services = emptyList(), sdcardPath = "/storage/emulated/legacy",
            settings = VmSettings(gpu = false, ramMb = 512), createdAt = 1L, sourceName = "occam.tgz")
        ImageStore.save(ctx, img)
        val p = VmPaths(ctx, img.id)
        // firmware tree: files with modes, a link, a game save, runtime dirs that must not travel
        File(p.root, "system/bin").mkdirs()
        File(p.root, "system/bin/sh").writeText("#!shell"); Os.chmod(File(p.root, "system/bin/sh").path, "755".toInt(8))
        Os.symlink("/system/bin/sh", File(p.root, "system/bin/ls").path)
        val save = File(p.root, "data/data/com.com2us.elpise/shared_prefs/save.xml").apply { parentFile!!.mkdirs(); writeText("<level>42</level>") }
        Os.chmod(save.path, "600".toInt(8))
        File(p.root, "dev/socket").mkdirs(); File(p.root, "dev/socket/junk").writeText("x")
        File(p.bin, "binder.log").apply { parentFile!!.mkdirs(); writeText("log") }
        File(p.dir, "props.base").writeText("ro.x=1")
        // owners: the save belongs to uid 10050, its folder too
        val ownerOf = { f: File -> Os.lstat(f.path).let { String.format("%016x %016x", it.st_dev, it.st_ino) } }
        p.owners.writeText("${ownerOf(save)} 0000273a 0000273a\n${ownerOf(save.parentFile!!)} 0000273a 0000273a\n")
        // memory card with the game's downloaded resources
        val card = File(Environment.storage, "AEmulator/Google Nexus 4 (abc234)").apply { mkdirs() }
        File(card, "Android/data/com.com2us.elpise/res.pak").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        File(p.bin, "sdcard.path").writeText(card.path + "\n")

        val file = Backups.create(ctx, img, "Boss fight", { false }) { _, _ -> }
        assertTrue(file.name.endsWith(".aemu") && file.name.contains("Boss fight"))
        assertTrue(file.parentFile == File(Environment.storage, "AEmulator/Backups"))
        val slot = Backups.list(ctx).single()
        assertEquals("Boss fight", slot.label); assertEquals(img.id, slot.machineId)

        val r = Backups.restore(ctx, { file.inputStream() }, { false }) { _, _ -> }
        assertNotEquals(img.id, r.id)
        assertEquals("Google Nexus 4 (Boss fight)", r.name)
        assertEquals(false, r.settings.gpu); assertEquals(512, r.settings.ramMb)
        val q = VmPaths(ctx, r.id)
        val save2 = File(q.root, "data/data/com.com2us.elpise/shared_prefs/save.xml")
        assertEquals("<level>42</level>", save2.readText())
        assertEquals("600".toInt(8), Os.lstat(save2.path).st_mode and 0xfff)
        assertEquals("755".toInt(8), Os.lstat(File(q.root, "system/bin/sh").path).st_mode and 0xfff)
        assertEquals("/system/bin/sh", Os.readlink(File(q.root, "system/bin/ls").path))
        assertEquals("ro.x=1", File(q.dir, "props.base").readText())
        assertTrue(File(q.root, "dev").isDirectory); assertFalse(File(q.root, "dev/socket/junk").exists())
        assertFalse(File(q.bin, "binder.log").exists())
        // owners rewritten for the new inodes
        val rows = q.owners.readLines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("${ownerOf(save2)} 0000273a 0000273a", "${ownerOf(save2.parentFile!!)} 0000273a 0000273a"), rows)
        // memory card restored into the new machine's own folder
        val card2 = app.aemu.core.Sdcard.dirFor(ctx, r)!!
        assertTrue(card2.path.startsWith(File(Environment.storage, "AEmulator").path) && card2.name.endsWith("(${r.id})"))
        assertTrue(File(card2, "Android/data/com.com2us.elpise/res.pak").readBytes().contentEquals(ByteArray(300_000) { (it % 251).toByte() }))
        // the original is untouched
        assertEquals("<level>42</level>", save.readText())
        tmp.deleteRecursively()
    }

    @Test fun refusesWritingOutside() {
        val tmp = Files.createTempDirectory("aemu-evil").toFile()
        Environment.storage = File(tmp, "storage").apply { mkdirs() }
        val ctx = Ctx(tmp)
        val evil = File(tmp, "evil.aemu")
        val head = org.json.JSONObject().put("format", 1).put("label", "x").put("bytes", 10)
            .put("image", GuestImage(id = "zzz", name = "E", release = "4.4", api = 19, brand = "", model = "", skin = "AOSP",
                engine = Engine.KK, abi = "armeabi-v7a", bootclasspath = "", exports = emptyMap(), dirs = emptyList(),
                services = emptyList(), sdcardPath = "/sdcard", settings = VmSettings(), createdAt = 1L).toJson())
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(evil.outputStream())).use { t ->
            fun put(name: String, data: ByteArray) { t.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry(name).apply { size = data.size.toLong() }); t.write(data); t.closeArchiveEntry() }
            put("aemu-backup.json", head.toString().toByteArray())
            put("image/image.json", head.getJSONObject("image").toString().toByteArray())
            put("image/../../escaped.txt", "bad".toByteArray())
            val l = org.apache.commons.compress.archivers.tar.TarArchiveEntry("image/root/out", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
            l.linkName = tmp.path; t.putArchiveEntry(l); t.closeArchiveEntry()
            put("image/root/out/through-link.txt", "bad".toByteArray())
        }
        Backups.restore(ctx, { evil.inputStream() }, { false }) { _, _ -> }
        assertFalse(File(tmp, "escaped.txt").exists())
        assertFalse(File(tmp, "through-link.txt").exists())
        tmp.deleteRecursively()
    }

    @Test fun quickSaveAndPutBack() {
        val tmp = Files.createTempDirectory("aemu-quick").toFile()
        Environment.storage = File(tmp, "storage").apply { mkdirs() }
        val ctx = Ctx(tmp)
        val img = GuestImage(id = "qqq234", name = "Google Nexus 4", release = "4.4.4", api = 19, brand = "google", model = "Nexus 4",
            skin = "AOSP", engine = Engine.KK, abi = "armeabi-v7a", bootclasspath = "", exports = emptyMap(), dirs = emptyList(),
            services = emptyList(), sdcardPath = "/sdcard", settings = VmSettings(), createdAt = 1L)
        ImageStore.save(ctx, img)
        val p = VmPaths(ctx, img.id)
        val sysFile = File(p.root, "system/build.prop").apply { parentFile!!.mkdirs(); writeText("ro.x=1") }
        val dex = File(p.root, "data/dalvik-cache/system@app@Game.apk@classes.dex").apply { parentFile!!.mkdirs(); writeText("DEX") }
        val save = File(p.root, "data/data/com.com2us.elpise/files/save.dat").apply { parentFile!!.mkdirs(); writeText("level 10") }
        val cache = File(p.root, "data/data/com.com2us.elpise/cache/tmp.bin").apply { parentFile!!.mkdirs(); writeText("junk") }
        val ownerOf = { f: File -> Os.lstat(f.path).let { String.format("%016x %016x", it.st_dev, it.st_ino) } }
        p.owners.writeText("${ownerOf(sysFile)} 000003e8 000003e8\n${ownerOf(dex)} 000003e8 000003e8\n${ownerOf(save)} 0000273a 0000273a\n")

        var pauses = 0; var resumes = 0
        val f = Backups.quickSave(ctx, img, "", { pauses++ }, { resumes++ }) {}
        assertEquals(1, pauses); assertEquals(1, resumes)
        assertTrue(f.name.contains("Quick save"))
        val slot = Backups.list(ctx).single()
        assertEquals("data", slot.kind); assertEquals(img.id, slot.machineId)

        // play on: progress changes, a new file appears, the save's file goes
        save.delete()
        File(p.root, "data/data/com.com2us.elpise/files/later.dat").writeText("level 12")
        Backups.restoreData(ctx, img, { f.inputStream() }) { _, _ -> }

        val save2 = File(p.root, "data/data/com.com2us.elpise/files/save.dat")
        assertEquals("level 10", save2.readText())
        assertFalse(File(p.root, "data/data/com.com2us.elpise/files/later.dat").exists())
        assertFalse(File(p.root, "data/data/com.com2us.elpise/cache/tmp.bin").exists())   // caches are not saved
        assertEquals("DEX", File(p.root, "data/dalvik-cache/system@app@Game.apk@classes.dex").readText())
        assertEquals("ro.x=1", sysFile.readText())
        val rows = p.owners.readLines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("${ownerOf(sysFile)} 000003e8 000003e8",
            "${ownerOf(File(p.root, "data/dalvik-cache/system@app@Game.apk@classes.dex"))} 000003e8 000003e8",
            "${ownerOf(save2)} 0000273a 0000273a"), rows)
        // a full restore refuses a quick save
        assertTrue(runCatching { Backups.restore(ctx, { f.inputStream() }, { false }) { _, _ -> } }.isFailure)
        // the guest goes on even when the copy fails
        pauses = 0; resumes = 0
        ImageStore.wipe(File(p.root, "data")); File(p.root, "data").writeText("not a folder")
        runCatching { Backups.quickSave(ctx, img, "", { pauses++ }, { resumes++ }) {} }
        assertEquals(pauses, resumes)
        tmp.deleteRecursively()
    }

    @Test fun backupOfARunningMachinePausesItForItsData() {
        val tmp = Files.createTempDirectory("aemu-live").toFile()
        Environment.storage = File(tmp, "storage").apply { mkdirs() }
        val ctx = Ctx(tmp)
        val img = GuestImage(id = "liv234", name = "Google Nexus 4", release = "4.4.4", api = 19, brand = "google", model = "Nexus 4",
            skin = "AOSP", engine = Engine.KK, abi = "armeabi-v7a", bootclasspath = "", exports = emptyMap(), dirs = emptyList(),
            services = emptyList(), sdcardPath = "/sdcard", settings = VmSettings(), createdAt = 1L)
        ImageStore.save(ctx, img)
        val p = VmPaths(ctx, img.id)
        File(p.root, "system/build.prop").apply { parentFile!!.mkdirs(); writeText("ro.x=1") }
        File(p.root, "data/dalvik-cache/x.dex").apply { parentFile!!.mkdirs(); writeText("DEX") }
        File(p.root, "data/data/com.com2us.elpise/files/save.dat").apply { parentFile!!.mkdirs(); writeText("level 10") }
        // a "guest process" of this machine: its command line names the image folder, like qemu's do
        val guest = ProcessBuilder("sh", "-c", "echo \$\$; sleep 60", "${p.root}/system/bin/app_process").start()
        val pid = guest.inputStream.bufferedReader().readLine().trim().toInt()
        fun state() = File("/proc/$pid/stat").readText().substringAfterLast(") ").first()
        try {
            Thread.sleep(200)
            var stateWhilePaused = '?'
            val f = Backups.create(ctx, img, "live", { false }) { step, _ -> if (step == "pause") stateWhilePaused = state() }
            assertEquals('T', stateWhilePaused)          // stopped while /data was copied
            assertNotEquals('T', state())                 // and going on afterwards
            val r = Backups.restore(ctx, { f.inputStream() }, { false }) { _, _ -> }
            val q = VmPaths(ctx, r.id)
            assertEquals("level 10", File(q.root, "data/data/com.com2us.elpise/files/save.dat").readText())
            assertEquals("DEX", File(q.root, "data/dalvik-cache/x.dex").readText())
            assertEquals("ro.x=1", File(q.root, "system/build.prop").readText())
            // a quick save fits another machine with the same firmware
            val qs = Backups.quickSave(ctx, img, "", { }, { }) {}
            val slot = Backups.list(ctx).first { it.file == qs }
            assertTrue(Backups.fits(slot, r))
            assertFalse(Backups.fits(slot, r.copy(id = "other1", release = "5.1.1")))
        } finally { guest.destroyForcibly(); tmp.deleteRecursively() }
    }
}
