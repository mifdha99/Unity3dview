package com.example.viewer.manager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.viewer.model.SupportStatus
import com.example.viewer.model.UnityAssetEntry
import com.example.viewer.model.UnityContainerReport
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ProjectScanner(
    private val context: Context,
    private val fileManager: FileManager
) {

    /**
     * Scans a folder selected via Android Storage Access Framework (OpenDocumentTree).
     * Detects Unity Project layout (Assets/, ProjectSettings/, Packages/) and prioritizes
     * 3D meshes (FBX, OBJ, GLTF, GLB), textures, and materials while honestly reporting
     * SUPPORTED / PARTIALLY SUPPORTED / NOT SUPPORTED status for Unity-specific files.
     */
    fun scanSafFolder(treeUri: Uri): UnityContainerReport {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderTitle = rootDocId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Unity Project" }

        var hasAssets = false
        var hasProjectSettings = false
        var hasPackages = false
        var assetsDocId: String? = null

        val rootChildren = queryChildren(treeUri, rootDocId)
        for (child in rootChildren) {
            if (child.isDirectory) {
                when (child.name.lowercase(Locale.ROOT)) {
                    "assets" -> {
                        hasAssets = true
                        assetsDocId = child.documentId
                    }
                    "projectsettings" -> hasProjectSettings = true
                    "packages" -> hasPackages = true
                }
            }
        }

        // If user selected the Assets/ folder directly, recognize that too
        if (folderTitle.equals("Assets", ignoreCase = true)) {
            hasAssets = true
        }

        val collectedEntries = mutableListOf<SafFileNode>()
        val startDocId = assetsDocId ?: rootDocId
        val startPrefix = if (assetsDocId != null) "Assets" else ""
        traverseSafTree(
            treeUri = treeUri,
            parentDocId = startDocId,
            relativePrefix = startPrefix,
            depth = 0,
            maxDepth = 5,
            outList = collectedEntries
        )

        // Stage textures and materials to local cache so 3D models opened from the folder can resolve them
        val cacheStageDir = File(
            context.cacheDir,
            "saf_project_${folderTitle.replace(Regex("[^a-zA-Z0-9]"), "_")}"
        ).apply { mkdirs() }

        val stagedMaterials = mutableListOf<String>()
        val stagedTextures = mutableListOf<String>()

        for (node in collectedEntries) {
            val ext = node.extension
            if (ext in setOf("mtl", "mat") || ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS) {
                if (node.sizeBytes in 1..(15 * 1024 * 1024)) {
                    try {
                        val dest = File(cacheStageDir, node.name)
                        context.contentResolver.openInputStream(node.uri)?.use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                        if (ext in setOf("mtl", "mat")) stagedMaterials.add(dest.absolutePath)
                        if (ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS) stagedTextures.add(dest.absolutePath)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val assetEntries = collectedEntries.mapIndexed { idx, node ->
            val ext = node.extension
            val (status, detail) = fileManager.classifyExtension(ext)
            val category = when {
                ext in FileManager.SUPPORTED_3D_EXTENSIONS -> "3D Model"
                ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS -> "Texture"
                ext in setOf("mat", "mtl") -> "Material"
                ext in setOf("unity", "prefab", "asset") -> "Unity Asset"
                else -> "Other File"
            }
            UnityAssetEntry(
                id = "saf_$idx",
                name = node.name,
                relativePath = node.relativePath,
                extension = ext,
                sizeBytes = node.sizeBytes,
                category = category,
                supportStatus = status,
                statusDetail = detail,
                documentUri = node.uri,
                associatedMaterialPaths = stagedMaterials,
                associatedTexturePaths = stagedTextures
            )
        }.sortedWith(
            compareBy<UnityAssetEntry> {
                // Prioritize FBX, OBJ, GLTF, GLB first, then textures/materials, then unsupported
                when (it.extension) {
                    "glb", "gltf", "fbx", "obj", "dae", "3ds" -> 0
                    "png", "jpg", "jpeg", "webp", "tga" -> 1
                    "mat", "mtl" -> 2
                    else -> 3
                }
            }.thenBy { it.relativePath }
        )

        val isUnityProject = hasAssets || hasProjectSettings || hasPackages
        val summary = if (isUnityProject) {
            "Struktur Unity Project terdeteksi (Assets: ${if (hasAssets) "Ya" else "Tidak"}, ProjectSettings: ${if (hasProjectSettings) "Ya" else "Tidak"}, Packages: ${if (hasPackages) "Ya" else "Tidak"}). Ditemukan ${assetEntries.size} file."
        } else {
            "Folder dipindai melalui Android SAF. Ditemukan ${assetEntries.size} file."
        }

        return UnityContainerReport(
            title = folderTitle,
            containerType = if (isUnityProject) "Unity Project Folder" else "Asset Folder",
            hasAssetsFolder = hasAssets,
            hasProjectSettingsFolder = hasProjectSettings,
            hasPackagesFolder = hasPackages,
            entries = assetEntries,
            summaryNote = summary
        )
    }

    private data class SafChild(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val isDirectory: Boolean
    )

    private data class SafFileNode(
        val name: String,
        val relativePath: String,
        val extension: String,
        val sizeBytes: Long,
        val uri: Uri
    )

    private fun traverseSafTree(
        treeUri: Uri,
        parentDocId: String,
        relativePrefix: String,
        depth: Int,
        maxDepth: Int,
        outList: MutableList<SafFileNode>
    ) {
        if (depth > maxDepth || outList.size >= 350) return
        val children = queryChildren(treeUri, parentDocId)
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val relPath = if (relativePrefix.isEmpty()) child.name else "$relativePrefix/${child.name}"
            if (child.isDirectory) {
                // Skip Library/Temp/Logs folders in Unity projects to keep scanning fast
                val lowerDir = child.name.lowercase(Locale.ROOT)
                if (lowerDir !in setOf("library", "temp", "logs", "obj", "userSettings", ".git")) {
                    traverseSafTree(treeUri, child.documentId, relPath, depth + 1, maxDepth, outList)
                }
            } else {
                if ( !child.name.endsWith(".meta", ignoreCase = true)) {
                    val ext = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                    outList.add(
                        SafFileNode(
                            name = child.name,
                            relativePath = relPath,
                            extension = ext,
                            sizeBytes = child.sizeBytes,
                            uri = docUri
                        )
                    )
                }
            }
        }
    }

    private fun queryChildren(treeUri: Uri, parentDocId: String): List<SafChild> {
        val result = mutableListOf<SafChild>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol) ?: continue else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "unnamed" else "unnamed"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    result.add(SafChild(docId, name, mime, size, isDir))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
