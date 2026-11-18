package com.example.videoenhancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnhancerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancerScreen(vm: MainViewModel = viewModel()) {
    val preset by vm.selectedPreset.collectAsState()
    val strictLock by vm.strictSizeLock.collectAsState()
    val outputDir by vm.outputDirUri.collectAsState()
    val queue by vm.queue.collectAsState()
    val isProcessing by vm.isProcessing.collectAsState()
    var dropdownExpanded by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addVideos(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.setOutputDirectory(uri)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("FFmpeg 1080p Video Enhancer", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { videoPicker.launch(arrayOf("video/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("Add 360p Videos") }

            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (outputDir == null) "Select Output Folder" else "Folder Ready ✓")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = !dropdownExpanded }
        ) {
            OutlinedTextField(
                value = preset.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Enhancement Preset (360p -> 1080p)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                EnhancementPreset.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.displayName) },
                        onClick = {
                            vm.setPreset(item)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Lock Size Anti-Bengkak (Bitrate <= Original)", fontSize = 13.sp)
            Switch(checked = strictLock, onCheckedChange = { vm.setStrictSizeLock(it) })
        }

        Text(
            text = preset.ffmpegArgs.joinToString(" "),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.startEnhance() },
                enabled = !isProcessing && queue.isNotEmpty(),
                modifier = Modifier.weight(1.3f)
            ) {
                Text(if (isProcessing) "Processing..." else "▶ Start Enhance")
            }

            OutlinedButton(
                onClick = { vm.cancelAll() },
                enabled = isProcessing,
                modifier = Modifier.weight(0.7f)
            ) {
                Text("Cancel")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(queue, key = { it.id }) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (item.status != "Enhancing") {
                                TextButton(
                                    onClick = { vm.removeItem(item.id) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text("✕", fontSize = 14.sp)
                                }
                            }
                        }

                        val sizeText = if (item.outputSizeMb != null) {
                            String.format("Original: %.1f MB -> Enhanced 1080p: %.1f MB", item.originalSizeMb, item.outputSizeMb)
                        } else {
                            String.format("Original: %.1f MB", item.originalSizeMb)
                        }
                        Text(sizeText, fontSize = 13.sp)
                        Text(
                            "Status: ${item.status} (${(item.progress * 100).toInt()}%) • Elapsed ${formatSec(item.elapsedSec)} • ETA ${formatSec(item.etaSec)}",
                            fontSize = 12.sp
                        )
                        if (!item.errorDetail.isNullOrBlank()) {
                            Text(
                                text = "Log: ${item.errorDetail}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun formatSec(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return String.format("%02d:%02d", m, s)
}