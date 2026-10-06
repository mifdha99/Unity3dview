package com.example.viewer.model

import android.graphics.Bitmap
import android.net.Uri

enum class SupportStatus(val label: String) {
    SUPPORTED("SUPPORTED"),
    PARTIALLY_SUPPORTED("PARTIALLY SUPPORTED"),
    NOT_SUPPORTED("NOT SUPPORTED")
}

enum class RenderMode(val label: String) {
    SOLID("Solid"),
    WIREFRAME("Wireframe"),
    SOLID_WIREFRAME("Solid + Wire")
}

enum class BackgroundPreset(
    val label: String,
    val topColor: FloatArray,
    val bottomColor: FloatArray,
    val gridColor: FloatArray
) {
    STUDIO_DARK(
        "Studio Dark",
        floatArrayOf(0.06f, 0.08f, 0.12f, 1f),
        floatArrayOf(0.03f, 0.04f, 0.06f, 1f),
        floatArrayOf(0.20f, 0.27f, 0.36f, 0.55f)
    ),
    UNITY_SKYBOX(
        "Unity Sky",
        floatArrayOf(0.14f, 0.24f, 0.38f, 1f),
        floatArrayOf(0.08f, 0.11f, 0.16f, 1f),
        floatArrayOf(0.35f, 0.55f, 0.75f, 0.45f)
    ),
    BLUEPRINT(
        "Blueprint",
        floatArrayOf(0.04f, 0.15f, 0.28f, 1f),
        floatArrayOf(0.02f, 0.08f, 0.16f, 1f),
        floatArrayOf(0.0f, 0.75f, 1.0f, 0.45f)
    ),
    CHARCOAL(
        "Charcoal",
        floatArrayOf(0.16f, 0.17f, 0.19f, 1f),
        floatArrayOf(0.09f, 0.10f, 0.11f, 1f),
        floatArrayOf(0.38f, 0.40f, 0.44f, 0.50f)
    )
}

enum class LodLevel(val label: String, val ratio: Float) {
    HIGH("LOD 0 (100%)", 1.0f),
    MEDIUM("LOD 1 (50%)", 0.5f),
    LOW("LOD 2 (25%)", 0.25f)
}

data class MaterialData(
    val name: String,
    val baseColor: FloatArray = floatArrayOf(0.78f, 0.82f, 0.88f, 1.0f),
    val metallic: Float = 0.15f,
    val roughness: Float = 0.45f,
    val emission: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f),
    val normalMapName: String? = null,
    val diffuseTextureName: String? = null,
    val isUnityApproximation: Boolean = false,
    val sourceShaderName: String = "Standard PBR"
)

data class TextureData(
    val name: String,
    val format: String,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?,
    val fileSizeBytes: Long = 0L
)

data class KeyframeSample(
    val timeSeconds: Float,
    val translation: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotationEulerDeg: FloatArray = floatArrayOf(0f, 0f, 0f),
    val scale: FloatArray = floatArrayOf(1f, 1f, 1f)
)

data class AnimationClipData(
    val name: String,
    val durationSeconds: Float,
    val keyframes: List<KeyframeSample>,
    val targetSubMeshIndex: Int = -1 // -1 means whole model or primary animated node
)

data class SubMeshData(
    val name: String,
    val positions: FloatArray, // x, y, z
    val normals: FloatArray,   // nx, ny, nz
    val uvs: FloatArray,       // u, v
    val indices: IntArray,     // triangle indices
    val materialIndex: Int = 0
)

data class Model3DData(
    val name: String,
    val fileFormat: String,
    val fileSizeBytes: Long,
    val vertexCount: Int,
    val triangleCount: Int,
    val subMeshes: List<SubMeshData>,
    val materials: List<MaterialData>,
    val textures: List<TextureData>,
    val animations: List<AnimationClipData>,
    val missingTextures: List<String> = emptyList(),
    val warningMessage: String? = null,
    val sourceDescription: String = "Local File",
    val boundingRadius: Float = 1.0f
)

data class UnityAssetEntry(
    val id: String,
    val name: String,
    val relativePath: String,
    val extension: String,
    val sizeBytes: Long,
    val category: String,
    val supportStatus: SupportStatus,
    val statusDetail: String,
    val localCachePath: String? = null,
    val documentUri: Uri? = null,
    val associatedMaterialPaths: List<String> = emptyList(),
    val associatedTexturePaths: List<String> = emptyList()
)

data class UnityContainerReport(
    val title: String,
    val containerType: String, // ".unitypackage", "Unity Project Folder", "ZIP Archive"
    val hasAssetsFolder: Boolean,
    val hasProjectSettingsFolder: Boolean,
    val hasPackagesFolder: Boolean,
    val entries: List<UnityAssetEntry>,
    val summaryNote: String
)

data class RecentItem(
    val id: String,
    val name: String,
    val pathOrUri: String,
    val format: String,
    val sizeBytes: Long,
    val timestampMillis: Long,
    val isSample: Boolean = false,
    val sampleKey: String? = null
)

data class ViewerSettings(
    val darkTheme: Boolean = true,
    val showGridByDefault: Boolean = true,
    val enableLightingByDefault: Boolean = true,
    val autoCenterAndNormalize: Boolean = true,
    val maxTextureResolution: Int = 1024,
    val defaultLod: LodLevel = LodLevel.HIGH
)
