package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusPartial
import com.example.ui.theme.StatusUnsupported
import com.example.viewer.manager.ActiveScreen
import com.example.viewer.manager.UIManager
import com.example.viewer.ui.ContainerInspectorScreen
import com.example.viewer.ui.HomeTabsScreen
import com.example.viewer.ui.Viewer3DScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incomingUri = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null

        setContent {
            val uiManager: UIManager = viewModel()
            val uiState by uiManager.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(incomingUri) {
                if (incomingUri != null) {
                    uiManager.onFileUriSelected(incomingUri)
                }
            }

            MyApplicationTheme(darkTheme = uiState.settings.darkTheme) {
                Unity3DViewerApp(uiManager = uiManager)
            }
        }
    }
}

@Composable
fun Unity3DViewerApp(uiManager: UIManager) {
    val uiState by uiManager.uiState.collectAsStateWithLifecycle()

    // SAF File Picker for .unitypackage, .fbx, .obj, .gltf, .glb, .dae, .3ds, .blend, .zip
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            uiManager.onFileUriSelected(uri)
        }
    }

    // SAF Folder Picker for Unity Project root or Assets/ folder
    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            uiManager.onFolderTreeSelected(treeUri)
        }
    }

    // SAF CreateDocument Launcher for EXPORT MODEL (.glb)
    val exportGlbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { targetUri ->
        if (targetUri != null) {
            uiManager.exportCurrentModelToGlb(targetUri)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState.activeScreen) {
                ActiveScreen.HOME -> {
                    HomeTabsScreen(
                        uiState = uiState,
                        fileManager = uiManager.fileManager,
                        onSelectTab = uiManager::selectHomeTab,
                        onPickFileClick = {
                            openDocumentLauncher.launch(arrayOf("*/*"))
                        },
                        onPickFolderClick = {
                            openFolderLauncher.launch(null)
                        },
                        onOpenSampleAsset = uiManager::openSampleAsset,
                        onOpenRecentItem = uiManager::openRecentItem,
                        onClearRecents = uiManager::clearRecentHistory,
                        onUpdateSettings = uiManager::updateSettings
                    )
                }

                ActiveScreen.CONTAINER_INSPECTOR -> {
                    val report = uiState.containerReport
                    if (report != null) {
                        ContainerInspectorScreen(
                            report = report,
                            selectedFilter = uiState.statusFilter,
                            fileManager = uiManager.fileManager,
                            onSelectFilter = uiManager::setStatusFilter,
                            onEntryClick = uiManager::openContainerAssetEntry,
                            onBack = { uiManager.navigateBack() }
                        )
                    }
                }

                ActiveScreen.VIEWER_3D -> {
                    Viewer3DScreen(
                        uiState = uiState,
                        sceneManager = uiManager.sceneManager,
                        fileManager = uiManager.fileManager,
                        onBack = { uiManager.navigateBack() },
                        onResetView = uiManager::resetCameraView,
                        onToggleFullscreen = uiManager::toggleFullscreen,
                        onToggleGrid = uiManager::toggleGrid,
                        onToggleLighting = uiManager::toggleLighting,
                        onRotateLight = uiManager::rotateLightDirection,
                        onSelectRenderMode = uiManager::setRenderMode,
                        onCycleBackground = uiManager::cycleBackgroundPreset,
                        onCycleLod = uiManager::cycleLodLevel,
                        onToggleInspectorPanel = uiManager::toggleInspectorPanel,
                        onUpdateMaterialApproximation = uiManager::updateMaterialApproximation,
                        onSelectAnimationClip = uiManager::selectAnimationClip,
                        onPlayAnimation = uiManager::playAnimation,
                        onPauseAnimation = uiManager::pauseAnimation,
                        onStopAnimation = uiManager::stopAnimation,
                        onExportGlbClick = {
                            val baseName = uiState.currentModel?.name?.ifBlank { "exported_model" } ?: "exported_model"
                            exportGlbLauncher.launch("$baseName.glb")
                        }
                    )
                }
            }

            // Floating Status / Warning / Error Banner
            AnimatedVisibility(
                visible = uiState.bannerMessage != null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val msg = uiState.bannerMessage.orEmpty()
                val accent = if (uiState.isBannerError) StatusUnsupported else StatusPartial
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, accent, RoundedCornerShape(14.dp))
                        .testTag("status_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            imageVector = if (uiState.isBannerError) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = uiManager::dismissBanner,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup Pesan", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Async Loading Overlay
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = uiState.loadingStatusText.ifBlank { "Memproses aset 3D..." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
