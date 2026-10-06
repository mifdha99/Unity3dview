package com.example.viewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.StatusPartial
import com.example.ui.theme.StatusSupported
import com.example.viewer.manager.AnimationPlaybackState
import com.example.viewer.manager.FileManager
import com.example.viewer.manager.InspectorPanel
import com.example.viewer.manager.SceneManager
import com.example.viewer.manager.ViewerUiState
import com.example.viewer.model.MaterialData
import com.example.viewer.model.Model3DData
import com.example.viewer.model.RenderMode
import java.util.Locale

@Composable
fun Viewer3DScreen(
    uiState: ViewerUiState,
    sceneManager: SceneManager,
    fileManager: FileManager,
    onBack: () -> Unit,
    onResetView: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleLighting: () -> Unit,
    onRotateLight: () -> Unit,
    onSelectRenderMode: (RenderMode) -> Unit,
    onCycleBackground: () -> Unit,
    onCycleLod: () -> Unit,
    onToggleInspectorPanel: (InspectorPanel) -> Unit,
    onUpdateMaterialApproximation: (Float, Float, Float, Float, Float) -> Unit,
    onSelectAnimationClip: (Int) -> Unit,
    onPlayAnimation: () -> Unit,
    onPauseAnimation: () -> Unit,
    onStopAnimation: () -> Unit,
    onExportGlbClick: () -> Unit
) {
    BackHandler(onBack = onBack)

    val model = uiState.currentModel ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Header Bar (hidden in fullscreen mode)
        AnimatedVisibility(visible = !uiState.isFullscreen) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("viewer_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.name.ifBlank { "Unknown" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${model.fileFormat.ifBlank { "Unknown" }} • ${model.vertexCount} verts • ${model.triangleCount} tris",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onResetView,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("reset_view_button")
                        ) {
                            Icon(
                                Icons.Default.CenterFocusStrong,
                                contentDescription = "Reset View",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RESET VIEW", style = MaterialTheme.typography.labelSmall)
                        }

                        Button(
                            onClick = onExportGlbClick,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("export_model_button")
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Export Model to GLB",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("GLB", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Center 3D Interactive Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Viewport3DView(
                sceneManager = sceneManager,
                model = model,
                renderMode = uiState.renderMode,
                showGrid = uiState.showGrid,
                enableLighting = uiState.enableLighting,
                lightAzimuthDeg = uiState.lightAzimuthDeg,
                backgroundPreset = uiState.backgroundPreset,
                lodLevel = uiState.lodLevel,
                overrideMaterial = uiState.customMaterialOverride,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("opengl_3d_viewport")
            )

            // Top-left touch gesture HUD badge + Fullscreen exit button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "1 Jari: Rotate • 2 Jari: Zoom/Pan • ${uiState.renderMode.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.isFullscreen) {
                        Button(
                            onClick = onResetView,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.65f))
                        ) {
                            Text("RESET VIEW", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.60f))
                            .size(40.dp)
                            .testTag("fullscreen_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle Fullscreen",
                            tint = Color.White
                        )
                    }
                }
            }

            // Animation Control Overlay Bar (ONLY shown when model has animations, hidden otherwise per Rule #7)
            if (model.animations.isNotEmpty() && !uiState.isFullscreen) {
                AnimationControlBar(
                    model = model,
                    selectedClipIndex = uiState.selectedClipIndex,
                    playbackState = uiState.animPlaybackState,
                    onSelectClip = onSelectAnimationClip,
                    onPlay = onPlayAnimation,
                    onPause = onPauseAnimation,
                    onStop = onStopAnimation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                )
            }
        }

        // Collapsible Model Information / Material Inspector Panel
        AnimatedVisibility(visible = !uiState.isFullscreen && uiState.activeInspectorPanel != InspectorPanel.NONE) {
            when (uiState.activeInspectorPanel) {
                InspectorPanel.MODEL_INFO -> ModelInformationPanel(
                    model = model,
                    fileManager = fileManager,
                    onClose = { onToggleInspectorPanel(InspectorPanel.NONE) }
                )
                InspectorPanel.MATERIALS_AND_TEXTURES -> MaterialAndTexturePanel(
                    model = model,
                    activeMaterial = uiState.customMaterialOverride ?: model.materials.firstOrNull() ?: MaterialData("Default"),
                    onUpdateMaterial = onUpdateMaterialApproximation,
                    onClose = { onToggleInspectorPanel(InspectorPanel.NONE) }
                )
                InspectorPanel.NONE -> {}
            }
        }

        // Bottom Viewer Toolbar
        AnimatedVisibility(visible = !uiState.isFullscreen) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Solid vs Wireframe Mode buttons
                    ViewerToolChip(
                        icon = Icons.Default.ViewInAr,
                        label = "Solid",
                        active = uiState.renderMode == RenderMode.SOLID,
                        testTag = "mode_solid_button",
                        onClick = { onSelectRenderMode(RenderMode.SOLID) }
                    )
                    ViewerToolChip(
                        icon = Icons.Default.GridOn,
                        label = "Wireframe",
                        active = uiState.renderMode == RenderMode.WIREFRAME,
                        testTag = "mode_wireframe_button",
                        onClick = { onSelectRenderMode(RenderMode.WIREFRAME) }
                    )
                    ViewerToolChip(
                        icon = Icons.Default.Layers,
                        label = "Solid+Wire",
                        active = uiState.renderMode == RenderMode.SOLID_WIREFRAME,
                        testTag = "mode_solid_wire_button",
                        onClick = { onSelectRenderMode(RenderMode.SOLID_WIREFRAME) }
                    )
                    ViewerToolChip(
                        icon = Icons.Default.GridOn,
                        label = if (uiState.showGrid) "Grid: ON" else "Grid: OFF",
                        active = uiState.showGrid,
                        testTag = "toggle_grid_button",
                        onClick = onToggleGrid
                    )
                    ViewerToolChip(
                        icon = Icons.Default.LightMode,
                        label = if (uiState.enableLighting) "Light: ON" else "Unlit",
                        active = uiState.enableLighting,
                        testTag = "toggle_lighting_button",
                        onClick = onToggleLighting
                    )
                    ViewerToolChip(
                        icon = Icons.Default.LightMode,
                        label = "Sun ${uiState.lightAzimuthDeg.toInt()}°",
                        active = false,
                        testTag = "rotate_light_button",
                        onClick = onRotateLight
                    )
                    ViewerToolChip(
                        icon = Icons.Default.Wallpaper,
                        label = uiState.backgroundPreset.label,
                        active = false,
                        testTag = "cycle_bg_button",
                        onClick = onCycleBackground
                    )
                    ViewerToolChip(
                        icon = Icons.Default.Layers,
                        label = uiState.lodLevel.label,
                        active = false,
                        testTag = "cycle_lod_button",
                        onClick = onCycleLod
                    )
                    ViewerToolChip(
                        icon = Icons.Default.Info,
                        label = "Model Info",
                        active = uiState.activeInspectorPanel == InspectorPanel.MODEL_INFO,
                        testTag = "panel_model_info_button",
                        onClick = { onToggleInspectorPanel(InspectorPanel.MODEL_INFO) }
                    )
                    ViewerToolChip(
                        icon = Icons.Default.Palette,
                        label = "Materials",
                        active = uiState.activeInspectorPanel == InspectorPanel.MATERIALS_AND_TEXTURES,
                        testTag = "panel_materials_button",
                        onClick = { onToggleInspectorPanel(InspectorPanel.MATERIALS_AND_TEXTURES) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimationControlBar(
    model: Model3DData,
    selectedClipIndex: Int,
    playbackState: AnimationPlaybackState,
    onSelectClip: (Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "ANIMATION CLIPS (${model.animations.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallTransportButton(
                        icon = Icons.Default.PlayArrow,
                        label = "PLAY",
                        active = playbackState == AnimationPlaybackState.PLAYING,
                        testTag = "anim_play_button",
                        onClick = onPlay
                    )
                    SmallTransportButton(
                        icon = Icons.Default.Pause,
                        label = "PAUSE",
                        active = playbackState == AnimationPlaybackState.PAUSED,
                        testTag = "anim_pause_button",
                        onClick = onPause
                    )
                    SmallTransportButton(
                        icon = Icons.Default.Stop,
                        label = "STOP",
                        active = playbackState == AnimationPlaybackState.STOPPED,
                        testTag = "anim_stop_button",
                        onClick = onStop
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                model.animations.forEachIndexed { idx, clip ->
                    FilterChip(
                        selected = idx == selectedClipIndex,
                        onClick = { onSelectClip(idx) },
                        label = {
                            Text(
                                text = "${clip.name} (${String.format(Locale.US, "%.1fs", clip.durationSeconds)})",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallTransportButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(3.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun ModelInformationPanel(
    model: Model3DData,
    fileManager: FileManager,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MODEL INFORMATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Panel", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            val materialsText = if (model.materials.isNotEmpty()) {
                "${model.materials.size} (${model.materials.joinToString { it.name }})"
            } else "Unknown"

            val texturesText = when {
                model.textures.isNotEmpty() -> "${model.textures.size} (${model.textures.joinToString { "${it.name} [${it.format}]" }})"
                model.missingTextures.isNotEmpty() -> "Missing (${model.missingTextures.joinToString()}) — Fallback Material Active"
                else -> "None / Procedural Material"
            }

            val animationsText = if (model.animations.isNotEmpty()) {
                "${model.animations.size} (${model.animations.joinToString { it.name }})"
            } else "None"

            InfoGridRow("Model name", model.name.ifBlank { "Unknown" })
            InfoGridRow("File format", model.fileFormat.ifBlank { "Unknown" })
            InfoGridRow("Vertices", if (model.vertexCount > 0) model.vertexCount.toString() else "Unknown")
            InfoGridRow("Triangles", if (model.triangleCount > 0) model.triangleCount.toString() else "Unknown")
            InfoGridRow("Materials", materialsText)
            InfoGridRow("Textures", texturesText)
            InfoGridRow("Animations", animationsText)
            InfoGridRow("File size", if (model.fileSizeBytes > 0) fileManager.formatFileSize(model.fileSizeBytes) else "Unknown")
        }
    }
}

@Composable
private fun InfoGridRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MaterialAndTexturePanel(
    model: Model3DData,
    activeMaterial: MaterialData,
    onUpdateMaterial: (Float, Float, Float, Float, Float) -> Unit,
    onClose: () -> Unit
) {
    val r = activeMaterial.baseColor.getOrElse(0) { 0.8f }
    val g = activeMaterial.baseColor.getOrElse(1) { 0.8f }
    val b = activeMaterial.baseColor.getOrElse(2) { 0.8f }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MATERIAL & TEXTURE INSPECTOR",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${activeMaterial.name} • ${activeMaterial.sourceShaderName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Material Panel", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Catatan: Aproksimasi material menggunakan Base Color, Metallic, Roughness, Normal Map & Emission (tidak mengklaim 100% identik dengan shader internal Unity Editor).",
                style = MaterialTheme.typography.labelSmall,
                color = StatusPartial
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Metallic: ${String.format(Locale.US, "%.2f", activeMaterial.metallic)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(105.dp))
                Slider(
                    value = activeMaterial.metallic,
                    onValueChange = { onUpdateMaterial(it, activeMaterial.roughness, r, g, b) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Roughness: ${String.format(Locale.US, "%.2f", activeMaterial.roughness)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(105.dp))
                Slider(
                    value = activeMaterial.roughness,
                    onValueChange = { onUpdateMaterial(activeMaterial.metallic, it, r, g, b) },
                    valueRange = 0.05f..0.98f,
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Preset Base Color Approximation:", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val swatches = listOf(
                    "Studio Silver" to floatArrayOf(0.80f, 0.84f, 0.90f),
                    "Unity Cyan" to floatArrayOf(0.12f, 0.75f, 0.96f),
                    "Amber Gold" to floatArrayOf(0.95f, 0.65f, 0.18f),
                    "Emerald" to floatArrayOf(0.16f, 0.82f, 0.55f),
                    "Crimson" to floatArrayOf(0.92f, 0.28f, 0.32f)
                )
                swatches.forEach { (label, rgb) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(rgb[0], rgb[1], rgb[2]))
                            .clickable {
                                onUpdateMaterial(activeMaterial.metallic, activeMaterial.roughness, rgb[0], rgb[1], rgb[2])
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (model.textures.isNotEmpty()) {
                    "Textures Loaded: " + model.textures.joinToString { "${it.name} (${it.width}x${it.height} ${it.format})" }
                } else {
                    "Textures: Fallback Material Active (PNG/JPG/JPEG/WEBP/TGA supported)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (model.textures.isNotEmpty()) StatusSupported else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ViewerToolChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
    val border = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = contentColor)
    }
}
