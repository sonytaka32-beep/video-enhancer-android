package com.example.videoenhancer

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class VideoQueueItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val originalSizeMb: Double,
    val status: String = "Ready",
    val errorDetail: String? = null,
    val progress: Float = 0f,
    val elapsedSec: Long = 0L,
    val etaSec: Long = 0L,
    val outputSizeMb: Double? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val enhancer = VideoEnhancer()
    private var activeJob: Job? = null

    private val _selectedPreset = MutableStateFlow(EnhancementPreset.CRISP_1080P_ANTI_BLOAT)
    val selectedPreset = _selectedPreset.asStateFlow()

    private val _strictSizeLock = MutableStateFlow(true)
    val strictSizeLock = _strictSizeLock.asStateFlow()

    private val _outputDirUri = MutableStateFlow<Uri?>(null)
    val outputDirUri = _outputDirUri.asStateFlow()

    private val _queue = MutableStateFlow<List<VideoQueueItem>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    fun setPreset(preset: EnhancementPreset) {
        _selectedPreset.value = preset
    }

    fun setStrictSizeLock(locked: Boolean) {
        _strictSizeLock.value = locked
    }

    fun setOutputDirectory(uri: Uri) {
        _outputDirUri.value = uri
    }

    fun addVideos(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val newItems = uris.map { uri ->
            var name = "video_${System.currentTimeMillis()}.mp4"
            var sizeBytes = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
            VideoQueueItem(
                id = "${uri}_${System.nanoTime()}",
                uri = uri,
                name = name,
                originalSizeMb = sizeBytes / (1024.0 * 1024.0),
                status = "Ready"
            )
        }
        _queue.update { it + newItems }
    }

    fun removeItem(id: String) {
        _queue.update { list -> list.filterNot { it.id == id && it.status != "Enhancing" } }
    }

    fun startEnhance() {
        if (_isProcessing.value) return
        val hasReady = _queue.value.any { it.status == "Ready" }
        if (!hasReady) {
            // Jika semua item berstatus Failed/Cancelled, reset jadi Ready agar bisa langsung diulang
            _queue.update { list ->
                list.map {
                    if (it.status == "Failed" || it.status == "Cancelled") {
                        it.copy(status = "Ready", errorDetail = null, progress = 0f, elapsedSec = 0L, etaSec = 0L)
                    } else it
                }
            }
        }
        if (_queue.value.none { it.status == "Ready" }) return

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            val context = getApplication<Application>()

            while (true) {
                val nextItem = _queue.value.firstOrNull { it.status == "Ready" } ?: break
                updateItem(nextItem.id) {
                    it.copy(status = "Enhancing", errorDetail = null, progress = 0f)
                }

                val tempInput = File(context.cacheDir, "in_${System.currentTimeMillis()}.mp4")
                val tempOutput = File(context.cacheDir, "out_1080p_${System.currentTimeMillis()}.mp4")

                try {
                    context.contentResolver.openInputStream(nextItem.uri)?.use { input ->
                        tempInput.outputStream().use { output -> input.copyTo(output) }
                    }

                    val result = enhancer.enhanceVideo(
                        inputPath = tempInput.absolutePath,
                        outputPath = tempOutput.absolutePath,
                        preset = _selectedPreset.value,
                        strictSizeLock = _strictSizeLock.value
                    ) { progress, elapsedMs ->
                        val elapsedSec = elapsedMs / 1000
                        val etaSec = if (progress > 0.01f) {
                            ((elapsedSec / progress) - elapsedSec).toLong().coerceAtLeast(0L)
                        } else 0L
                        updateItem(nextItem.id) {
                            it.copy(progress = progress, elapsedSec = elapsedSec, etaSec = etaSec)
                        }
                    }

                    if (result.success && tempOutput.exists() && tempOutput.length() > 0L) {
                        val outMb = tempOutput.length() / (1024.0 * 1024.0)
                        saveToOutputFolder(tempOutput, "1080p_${nextItem.name}")
                        updateItem(nextItem.id) {
                            it.copy(status = "Done", progress = 1f, outputSizeMb = outMb)
                        }
                    } else if (result.isCancelled) {
                        updateItem(nextItem.id) { it.copy(status = "Cancelled") }
                    } else {
                        updateItem(nextItem.id) {
                            it.copy(status = "Failed", errorDetail = result.errorDetail)
                        }
                    }
                } catch (e: Exception) {
                    updateItem(nextItem.id) {
                        it.copy(status = "Failed", errorDetail = e.message)
                    }
                } finally {
                    tempInput.delete()
                    tempOutput.delete()
                }
            }
            _isProcessing.value = false
        }
    }

    private fun saveToOutputFolder(sourceFile: File, fileName: String) {
        val context = getApplication<Application>()
        val dirUri = _outputDirUri.value
        if (dirUri != null) {
            val pickedDir = DocumentFile.fromTreeUri(context, dirUri)
            val newFile = pickedDir?.createFile("video/mp4", fileName)
            if (newFile != null) {
                context.contentResolver.openOutputStream(newFile.uri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
                }
                return
            }
        }
        // Fallback otomatis ke folder Movies/VideoEnhancer jika folder output belum dipilih
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "VideoEnhancer"
        )
        moviesDir.mkdirs()
        sourceFile.copyTo(File(moviesDir, fileName), overwrite = true)
    }

    fun cancelAll() {
        enhancer.cancel()
        activeJob?.cancel()
        _queue.update { list ->
            list.map {
                if (it.status == "Ready" || it.status == "Enhancing") it.copy(status = "Cancelled") else it
            }
        }
        _isProcessing.value = false
    }

    private fun updateItem(id: String, transform: (VideoQueueItem) -> VideoQueueItem) {
        _queue.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}