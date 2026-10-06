package com.example.viewer.manager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.viewer.model.BackgroundPreset
import com.example.viewer.model.LodLevel
import com.example.viewer.model.MaterialData
import com.example.viewer.model.Model3DData
import com.example.viewer.model.RecentItem
import com.example.viewer.model.RenderMode
import com.example.viewer.model.SupportStatus
import com.example.viewer.model.UnityAssetEntry
import com.example.viewer.model.UnityContainerReport
import com.example.viewer.model.ViewerSettings
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeTab {
    OPEN_FILE,
    OPEN_FOLDER,
    RECENT,
    SETTINGS
}

enum class ActiveScreen {
    HOME,
    CONTAINER_INSPECTOR,
    VIEWER_3D
}

enum class InspectorPanel {
    NONE,
    MODEL_INFO,
    MATERIALS_AND_TEXTURES
}

data class ViewerUiState(
    val activeScreen: ActiveScreen = ActiveScreen.HOME,
    val selectedHomeTab: HomeTab = HomeTab.OPEN_FILE,
    val isLoading: Boolean = false,
    val loadingStatusText: String = "",
    val currentModel: Model3DData? = null,
    val containerReport: UnityContainerReport? = null,
    val statusFilter: SupportStatus? = null, // null = ALL
    val recentItems: List<RecentItem> = emptyList(),
    val settings: ViewerSettings = ViewerSettings(),
    // 3D Viewer controls
    val renderMode: RenderMode = RenderMode.SOLID,
    val showGrid: Boolean = true,
    val enableLighting: Boolean = true,
    val lightAzimuthDeg: Float = 45f,
    val backgroundPreset: BackgroundPreset = BackgroundPreset.STUDIO_DARK,
    val isFullscreen: Boolean = false,
    val lodLevel: LodLevel = LodLevel.HIGH,
    val activeInspectorPanel: InspectorPanel = InspectorPanel.MODEL_INFO,
    val customMaterialOverride: MaterialData? = null,
    // Animation state
    val selectedClipIndex: Int = 0,
    val animPlaybackState: AnimationPlaybackState = AnimationPlaybackState.STOPPED,
    // Notifications / Errors
    val bannerMessage: String? = null,
    val isBannerError: Boolean = false
)

class UIManager(application: Application) : AndroidViewModel(application) {

    val fileManager = FileManager(application.applicationContext)
    val textureManager = TextureManager()
    val materialManager = MaterialManager()
    val animationManager = AnimationManager()
    val cameraController = CameraController()
    val sceneManager = SceneManager(cameraController, animationManager, textureManager)
    val unityPackageParser = UnityPackageParser(application.applicationContext, fileManager)
    val projectScanner = ProjectScanner(application.applicationContext, fileManager)
    val modelImporter = ModelImporter(textureManager, materialManager)
    val glbExporter = GlbExporter(application.applicationContext)

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.ensureSampleAssetsDir()
            val recents = fileManager.getRecentItems()
            _uiState.update { it.copy(recentItems = recents) }
        }
    }

    fun selectHomeTab(tab: HomeTab) {
        _uiState.update { it.copy(selectedHomeTab = tab, activeScreen = ActiveScreen.HOME) }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value
        return when (current.activeScreen) {
            ActiveScreen.VIEWER_3D -> {
                if (current.isFullscreen) {
                    _uiState.update { it.copy(isFullscreen = false) }
                } else if (current.activeInspectorPanel != InspectorPanel.NONE) {
                    _uiState.update { it.copy(activeInspectorPanel = InspectorPanel.NONE) }
                } else if (current.containerReport != null) {
                    _uiState.update { it.copy(activeScreen = ActiveScreen.CONTAINER_INSPECTOR) }
                } else {
                    _uiState.update { it.copy(activeScreen = ActiveScreen.HOME) }
                }
                true
            }
            ActiveScreen.CONTAINER_INSPECTOR -> {
                _uiState.update { it.copy(activeScreen = ActiveScreen.HOME) }
                true
            }
            ActiveScreen.HOME -> {
                if (current.selectedHomeTab != HomeTab.OPEN_FILE) {
                    _uiState.update { it.copy(selectedHomeTab = HomeTab.OPEN_FILE) }
                    true
                } else {
                    false
                }
            }
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null, isBannerError = false) }
    }

    fun setStatusFilter(filter: SupportStatus?) {
        _uiState.update { it.copy(statusFilter = filter) }
    }

    fun onFileUriSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = fileManager.resolveDisplayName(uri)
            val fileSize = fileManager.resolveFileSize(uri)
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

            if (!fileManager.checkMemorySafety(fileSize)) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "Ukuran model terlalu besar untuk kapasitas RAM perangkat saat ini.",
                        isBannerError = true
                    )
                }
                return@launch
            }

            val (status, detail) = fileManager.classifyExtension(ext)
            if (status == SupportStatus.NOT_SUPPORTED && ext !in FileManager.SUPPORTED_ARCHIVE_EXTENSIONS) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "Format ini belum didukung. ($detail)",
                        isBannerError = true
                    )
                }
                return@launch
            }

            if (ext == "blend") {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "File .blend terdeteksi (PARTIALLY SUPPORTED). Harap ekspor ke .glb atau .fbx dari Blender/Unity untuk preview mesh 3D penuh.",
                        isBannerError = false
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(isLoading = true, loadingStatusText = "Membaca $fileName...", bannerMessage = null)
            }

            try {
                val localFile = fileManager.copyUriToCache(uri, fileName)
                processLocalFile(localFile, fileName, uri.toString(), isSample = false, sampleKey = null)
            } catch (_: OutOfMemoryError) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "Ukuran model terlalu besar untuk kapasitas RAM perangkat saat ini.",
                        isBannerError = true
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.",
                        isBannerError = true
                    )
                }
            }
        }
    }

    fun onFolderTreeSelected(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingStatusText = "Memindai struktur folder Unity Project (Assets/, ProjectSettings/, Packages/)...",
                    bannerMessage = null
                )
            }
            try {
                val report = projectScanner.scanSafFolder(treeUri)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        containerReport = report,
                        statusFilter = null,
                        activeScreen = ActiveScreen.CONTAINER_INSPECTOR
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.",
                        isBannerError = true
                    )
                }
            }
        }
    }

    fun openSampleAsset(sampleKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isLoading = true, loadingStatusText = "Menyiapkan sample asset $sampleKey...", bannerMessage = null)
            }
            val dir = fileManager.ensureSampleAssetsDir()
            val targetFile = when (sampleKey) {
                "rover_glb" -> File(dir, "UnityRover_Animated.glb")
                "drone_obj" -> File(dir, "MechaDrone_PBR.obj")
                "reactor_fbx" -> File(dir, "SciFiReactor_Core.fbx")
                "starter_pkg" -> File(dir, "CyberStation_StarterKit.unitypackage")
                else -> File(dir, "UnityRover_Animated.glb")
            }
            processLocalFile(
                localFile = targetFile,
                displayName = targetFile.name,
                uriOrPath = targetFile.absolutePath,
                isSample = true,
                sampleKey = sampleKey
            )
        }
    }

    fun openRecentItem(item: RecentItem) {
        if (item.isSample && item.sampleKey != null) {
            openSampleAsset(item.sampleKey)
            return
        }
        val f = File(item.pathOrUri)
        if (f.exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(isLoading = true, loadingStatusText = "Membuka ${item.name}...") }
                processLocalFile(f, item.name, item.pathOrUri, isSample = false, sampleKey = null)
            }
        } else {
            try {
                onFileUriSelected(Uri.parse(item.pathOrUri))
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                }
            }
        }
    }

    fun openContainerAssetEntry(entry: UnityAssetEntry) {
        if (entry.supportStatus == SupportStatus.NOT_SUPPORTED) {
            _uiState.update {
                it.copy(
                    bannerMessage = "Format ini belum didukung. (${entry.statusDetail})",
                    isBannerError = true
                )
            }
            return
        }

        val ext = entry.extension.lowercase(Locale.ROOT)
        if (ext == "mat") {
            val matPath = entry.localCachePath
            if (matPath != null) {
                val parsedMat = materialManager.parseUnityMatFile(File(matPath))
                _uiState.update {
                    it.copy(
                        customMaterialOverride = parsedMat,
                        bannerMessage = "Material '${parsedMat.name}' diparsing menggunakan aproksimasi PBR (BaseColor/Metallic/Roughness). Pilih model 3D untuk menerapkannya.",
                        isBannerError = false
                    )
                }
            }
            return
        }

        if (ext !in FileManager.SUPPORTED_3D_EXTENSIONS) {
            _uiState.update {
                it.copy(
                    bannerMessage = "Asset '${entry.name}' (${entry.category}) otomatis dimuat bersama model 3D di dalam package/folder.",
                    isBannerError = false
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isLoading = true, loadingStatusText = "Mengimpor model 3D ${entry.name}...", bannerMessage = null)
            }
            try {
                val localFile = when {
                    entry.localCachePath != null -> File(entry.localCachePath)
                    entry.documentUri != null -> fileManager.copyUriToCache(entry.documentUri, entry.name)
                    else -> null
                }
                if (localFile == null || !localFile.exists()) {
                    _uiState.update {
                        it.copy(isLoading = false, bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                    }
                    return@launch
                }

                val maxTex = _uiState.value.settings.maxTextureResolution
                val result = modelImporter.importModelFile(
                    file = localFile,
                    displayName = entry.name,
                    associatedMaterialPaths = entry.associatedMaterialPaths,
                    associatedTexturePaths = entry.associatedTexturePaths,
                    maxTextureDimension = maxTex
                )
                handleModelImportResult(result, entry.name, localFile.absolutePath, localFile.length(), false, null)
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                }
            }
        }
    }

    private fun processLocalFile(
        localFile: File,
        displayName: String,
        uriOrPath: String,
        isSample: Boolean,
        sampleKey: String?
    ) {
        val ext = displayName.substringAfterLast('.', localFile.extension).lowercase(Locale.ROOT)
        if (ext == "unitypackage") {
            try {
                val report = unityPackageParser.parseUnityPackage(localFile, displayName)
                if (report.entries.isEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = false, bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                    }
                    return
                }
                recordRecent(displayName, localFile.absolutePath, ".unitypackage", localFile.length(), isSample, sampleKey)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        containerReport = report,
                        statusFilter = null,
                        activeScreen = ActiveScreen.CONTAINER_INSPECTOR
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                }
            }
            return
        }

        if (ext == "zip") {
            try {
                val report = unityPackageParser.parseZipArchive(localFile, displayName)
                if (report.entries.isEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = false, bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                    }
                    return
                }
                recordRecent(displayName, localFile.absolutePath, "ZIP", localFile.length(), isSample, sampleKey)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        containerReport = report,
                        statusFilter = null,
                        activeScreen = ActiveScreen.CONTAINER_INSPECTOR
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, bannerMessage = "File tidak dapat dibaca atau kemungkinan rusak.", isBannerError = true)
                }
            }
            return
        }

        val maxTex = _uiState.value.settings.maxTextureResolution
        val result = modelImporter.importModelFile(
            file = localFile,
            displayName = displayName,
            maxTextureDimension = maxTex
        )
        handleModelImportResult(result, displayName, localFile.absolutePath, localFile.length(), isSample, sampleKey)
    }

    private fun handleModelImportResult(
        result: ModelImportResult,
        displayName: String,
        pathOrUri: String,
        sizeBytes: Long,
        isSample: Boolean,
        sampleKey: String?
    ) {
        when (result) {
            is ModelImportResult.Success -> {
                val model = result.model
                cameraController.resetView()
                animationManager.setModelAnimations(model.animations)
                recordRecent(displayName, pathOrUri, model.fileFormat, sizeBytes, isSample, sampleKey)

                val s = _uiState.value.settings
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentModel = model,
                        activeScreen = ActiveScreen.VIEWER_3D,
                        showGrid = s.showGridByDefault,
                        enableLighting = s.enableLightingByDefault,
                        lodLevel = s.defaultLod,
                        customMaterialOverride = null,
                        selectedClipIndex = 0,
                        animPlaybackState = animationManager.playbackState,
                        bannerMessage = model.warningMessage,
                        isBannerError = false
                    )
                }
            }
            is ModelImportResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = result.message,
                        isBannerError = true
                    )
                }
            }
        }
    }

    private fun recordRecent(
        name: String,
        path: String,
        format: String,
        sizeBytes: Long,
        isSample: Boolean,
        sampleKey: String?
    ) {
        val item = RecentItem(
            id = "${name}_${System.currentTimeMillis()}",
            name = name,
            pathOrUri = path,
            format = format,
            sizeBytes = sizeBytes,
            timestampMillis = System.currentTimeMillis(),
            isSample = isSample,
            sampleKey = sampleKey
        )
        fileManager.addRecentItem(item)
        _uiState.update { it.copy(recentItems = fileManager.getRecentItems()) }
    }

    fun clearRecentHistory() {
        fileManager.clearRecentItems()
        _uiState.update { it.copy(recentItems = emptyList()) }
    }

    // 3D Viewer Actions
    fun resetCameraView() {
        cameraController.resetView()
    }

    fun toggleFullscreen() {
        _uiState.update {
            val nextFull = !it.isFullscreen
            it.copy(
                isFullscreen = nextFull,
                activeInspectorPanel = if (nextFull) InspectorPanel.NONE else it.activeInspectorPanel
            )
        }
    }

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun toggleLighting() {
        _uiState.update { it.copy(enableLighting = !it.enableLighting) }
    }

    fun rotateLightDirection() {
        _uiState.update { it.copy(lightAzimuthDeg = (it.lightAzimuthDeg + 45f) % 360f) }
    }

    fun setRenderMode(mode: RenderMode) {
        _uiState.update { it.copy(renderMode = mode) }
    }

    fun cycleBackgroundPreset() {
        val presets = BackgroundPreset.entries
        val currentIdx = presets.indexOf(_uiState.value.backgroundPreset)
        val nextPreset = presets[(currentIdx + 1) % presets.size]
        _uiState.update { it.copy(backgroundPreset = nextPreset) }
    }

    fun cycleLodLevel() {
        val levels = LodLevel.entries
        val currentIdx = levels.indexOf(_uiState.value.lodLevel)
        val nextLod = levels[(currentIdx + 1) % levels.size]
        _uiState.update { it.copy(lodLevel = nextLod) }
    }

    fun toggleInspectorPanel(panel: InspectorPanel) {
        _uiState.update {
            val next = if (it.activeInspectorPanel == panel) InspectorPanel.NONE else panel
            it.copy(activeInspectorPanel = next)
        }
    }

    fun updateMaterialApproximation(metallic: Float, roughness: Float, r: Float, g: Float, b: Float) {
        val base = _uiState.value.customMaterialOverride
            ?: _uiState.value.currentModel?.materials?.firstOrNull()
            ?: materialManager.createDefaultMaterial()
        val updated = base.copy(
            baseColor = floatArrayOf(r, g, b, 1.0f),
            metallic = metallic.coerceIn(0f, 1f),
            roughness = roughness.coerceIn(0.05f, 0.98f),
            isUnityApproximation = true
        )
        _uiState.update { it.copy(customMaterialOverride = updated) }
    }

    // Animation Controls
    fun selectAnimationClip(index: Int) {
        animationManager.selectClip(index)
        _uiState.update {
            it.copy(selectedClipIndex = index, animPlaybackState = animationManager.playbackState)
        }
    }

    fun playAnimation() {
        animationManager.play()
        _uiState.update { it.copy(animPlaybackState = animationManager.playbackState) }
    }

    fun pauseAnimation() {
        animationManager.pause()
        _uiState.update { it.copy(animPlaybackState = animationManager.playbackState) }
    }

    fun stopAnimation() {
        animationManager.stop()
        _uiState.update { it.copy(animPlaybackState = animationManager.playbackState) }
    }

    // Export Model to GLB
    fun exportCurrentModelToGlb(targetUri: Uri) {
        val model = _uiState.value.currentModel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, loadingStatusText = "Mengekspor ${model.name}.glb...") }
            val ok = glbExporter.exportModelToUri(model, targetUri)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    bannerMessage = if (ok) {
                        "Model '${model.name}' berhasil diekspor ke format GLB (Binary glTF 2.0)."
                    } else {
                        "Gagal mengekspor model ke lokasi penyimpanan yang dipilih."
                    },
                    isBannerError = !ok
                )
            }
        }
    }

    // Settings updates
    fun updateSettings(newSettings: ViewerSettings) {
        _uiState.update {
            it.copy(
                settings = newSettings,
                showGrid = newSettings.showGridByDefault,
                enableLighting = newSettings.enableLightingByDefault,
                lodLevel = newSettings.defaultLod
            )
        }
    }
}
