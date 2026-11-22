package app.aemu.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore
import app.aemu.core.VmSettings
import app.aemu.importer.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportState(
    val active: Boolean = false,
    val file: String = "",
    val step: String = "",
    val progress: Float = -1f,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val done: GuestImage? = null,
    /** what runs: "import" (firmware, copy), "backup" or "restore" */
    val kind: String = "import",
)

class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val _images = MutableStateFlow<List<GuestImage>>(emptyList())
    val images: StateFlow<List<GuestImage>> = _images
    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import
    private var importer: Importer? = null
    @Volatile private var cloneCancelled = false

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _images.value = withContext(Dispatchers.IO) { ImageStore.list(getApplication()) }
        }
    }

    private fun displayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "firmware"
    }

    fun import(uri: Uri) {
        if (_import.value.active) return
        val ctx = getApplication<Application>()
        val name = displayName(uri)
        // a backup picked in the import dialog is restored, not imported as firmware
        if (name.endsWith(app.aemu.core.Backups.EXT, true)) { restore(name) { ctx.contentResolver.openInputStream(uri) ?: error("cannot open $name") }; return }
        _import.value = ImportState(active = true, file = name, step = "Starting")
        viewModelScope.launch(Dispatchers.IO) {
            val log = ArrayList<String>()
            val imp = Importer(ctx,
                onProgress = { step, p -> _import.value = _import.value.copy(step = step, progress = if (p >= 0) p else _import.value.progress.takeIf { it >= 0 } ?: -1f) },
                log = { s -> log.add(s); _import.value = _import.value.copy(log = log.takeLast(50)) })
            importer = imp
            try {
                // умолчания из настроек приложения поверх того, что подобрал анализатор (экран, частоты)
                val raw = imp.import(uri, name)
                val d = app.aemu.AppPrefs.defaults(ctx)
                val img = raw.copy(settings = raw.settings.copy(gpu = d.gpu, jit = d.jit, netProxy = d.netProxy,
                    showNavBar = d.showNavBar, keepScreenOn = d.keepScreenOn, showLed = d.showLed))
                if (img != raw) ImageStore.save(ctx, img)
                _import.value = _import.value.copy(active = false, done = img, step = "Done", progress = 1f)
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = t.message ?: t.toString())
            } finally {
                importer = null
                refresh()
            }
        }
    }

    fun clone(src: GuestImage, name: String, copyData: Boolean, customSettings: app.aemu.core.VmSettings? = null) {
        if (_import.value.active) return
        val ctx = getApplication<Application>()
        cloneCancelled = false
        _import.value = ImportState(active = true, file = name, step = "Copying")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val img = ImageStore.clone(ctx, src, name, copyData, customSettings, { cloneCancelled }) { s -> _import.value = _import.value.copy(step = "Copying $s") }
                _import.value = _import.value.copy(active = false, done = img, step = "Done", progress = 1f)
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = t.message ?: t.toString())
            } finally { refresh() }
        }
    }

    private val _backups = MutableStateFlow<List<app.aemu.core.Backups.Slot>>(emptyList())
    val backups: StateFlow<List<app.aemu.core.Backups.Slot>> = _backups

    fun refreshBackups() {
        viewModelScope.launch { _backups.value = withContext(Dispatchers.IO) { app.aemu.core.Backups.list(getApplication()) } }
    }

    fun backupsDir(): String = app.aemu.core.Backups.dir(getApplication()).absolutePath

    fun backup(img: GuestImage, label: String) {
        if (_import.value.active) return
        val ctx = getApplication<Application>()
        cloneCancelled = false
        _import.value = ImportState(active = true, file = img.name, step = "Starting", kind = "backup")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = app.aemu.core.Backups.create(ctx, img, label, { cloneCancelled }) { step, p ->
                    _import.value = _import.value.copy(step = step, progress = p)
                }
                _import.value = _import.value.copy(active = false, file = f.name, step = "Done", progress = 1f, done = img)
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = t.message ?: t.toString())
            } finally { refreshBackups() }
        }
    }

    fun restore(slot: app.aemu.core.Backups.Slot) = restore(slot.file.name) { slot.file.inputStream() }

    /** puts a quick save back onto [img] (stopped) */
    fun restoreData(img: GuestImage, slot: app.aemu.core.Backups.Slot) {
        if (_import.value.active) return
        val ctx = getApplication<Application>()
        _import.value = ImportState(active = true, file = slot.file.name, step = "Starting", kind = "restore")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.aemu.core.Backups.restoreData(ctx, img, { slot.file.inputStream() }) { step, p ->
                    _import.value = _import.value.copy(step = step, progress = p)
                }
                _import.value = _import.value.copy(active = false, done = ImageStore.get(ctx, img.id) ?: img, step = "Done", progress = 1f)
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = t.message ?: t.toString())
            } finally { refresh() }
        }
    }

    private fun restore(name: String, open: () -> java.io.InputStream) {
        if (_import.value.active) return
        val ctx = getApplication<Application>()
        cloneCancelled = false
        _import.value = ImportState(active = true, file = name, step = "Starting", kind = "restore")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val img = app.aemu.core.Backups.restore(ctx, open, { cloneCancelled }) { step, p ->
                    _import.value = _import.value.copy(step = step, progress = p)
                }
                _import.value = _import.value.copy(active = false, done = img, step = "Done", progress = 1f)
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = t.message ?: t.toString())
            } finally { refresh() }
        }
    }

    fun deleteBackup(slot: app.aemu.core.Backups.Slot) {
        viewModelScope.launch(Dispatchers.IO) { slot.file.delete(); refreshBackups() }
    }

    fun cancelImport() { importer?.cancelled = true; cloneCancelled = true }

    /** A machine is running (they live in the :vm process); its files must not be changed under it. */
    fun vmRunning(): Boolean {
        val ctx = getApplication<Application>()
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        return am.runningAppProcesses.orEmpty().any { it.processName == ctx.packageName + ":vm" }
    }

    fun resetData(img: GuestImage) {
        viewModelScope.launch(Dispatchers.IO) {
            ImageStore.resetData(getApplication(), img.id)
            refresh()
        }
    }
    fun dismissImport() { _import.value = ImportState() }

    fun delete(img: GuestImage) {
        viewModelScope.launch(Dispatchers.IO) {
            ImageStore.delete(getApplication(), img.id)
            refresh()
        }
    }

    fun rename(img: GuestImage, name: String) = update(img) { it.copy(name = name) }

    fun updateSettings(img: GuestImage, s: VmSettings) = update(img) { it.copy(settings = s) }

    private fun update(img: GuestImage, f: (GuestImage) -> GuestImage) {
        viewModelScope.launch(Dispatchers.IO) {
            ImageStore.update(getApplication(), img.id, f)
            refresh()
        }
    }
}
