package com.example.videoenhancer

import android.app.Application
import android.net.Uri
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
    val status: String = "Queued",
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
                id = "${uri}_${System.currentTimeMillis()}",
                uri = uri,
                name = name,
                originalSizeMb = sizeBytes / (1024.0 * 1024.0)
            )
        }
        _queue.update { it + newItems }
        if (!_isProcessing.value) startQueue()
    }

    private fun startQueue() {
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            val context = getApplication<Application>()

            while (true) {
                val nextItem = _queue.value.firstOrNull { it.status == "Queued" } ?: break
                updateItem(nextItem.id) { it.copy(status = "Enhancing") }

                val tempInput = File(context.cacheDir, "in_${System.currentTimeMillis()}.mp4")
                val tempOutput = File(context.cacheDir, "out_1080p_${System.currentTimeMillis()}.mp4")

                try {
                    context.contentResolver.openInputStream(nextItem.uri)?.use { input ->
                        tempInput.outputStream().use { output -> input.copyTo(output) }
                    }

                    val success = enhancer.enhanceVideo(
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

                    if (success && tempOutput.exists()) {
                        val outMb = tempOutput.length() / (1024.0 * 1024.0)
                        saveToOutputFolder(tempOutput, "1080p_${nextItem.name}")
                        updateItem(nextItem.id) {
                            it.copy(status = "Done", progress = 1f, outputSizeMb = outMb)
                        }
                    } else {
                        updateItem(nextItem.id) { it.copy(status = "Cancelled / Failed") }
                    }
                } catch (e: Exception) {
                    updateItem(nextItem.id) { it.copy(status = "Error: ${e.message}") }
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
        val dirUri = _outputDirUri.value ?: return
        val pickedDir = DocumentFile.fromTreeUri(context, dirUri) ?: return
        val newFile = pickedDir.createFile("video/mp4", fileName) ?: return
        context.contentResolver.openOutputStream(newFile.uri)?.use { outStream ->
            sourceFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
        }
    }

    fun cancelAll() {
        enhancer.cancel()
        activeJob?.cancel()
        _queue.update { list ->
            list.map {
                if (it.status == "Queued" || it.status == "Enhancing") it.copy(status = "Cancelled") else it
            }
        }
        _isProcessing.value = false
    }

    private fun updateItem(id: String, transform: (VideoQueueItem) -> VideoQueueItem) {
        _queue.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}