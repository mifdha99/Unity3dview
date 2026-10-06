package com.example.viewer.manager

import android.content.Context
import com.example.viewer.model.SupportStatus
import com.example.viewer.model.UnityAssetEntry
import com.example.viewer.model.UnityContainerReport
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class UnityPackageParser(
    private val context: Context,
    private val fileManager: FileManager
) {

    /**
     * Extracts and inspects a .unitypackage (GZIP TAR with GUID entries) without modifying the user's file.
     */
    fun parseUnityPackage(packageFile: File, displayTitle: String): UnityContainerReport {
        val extractRoot = File(
            context.cacheDir,
            "unitypkg_${displayTitle.replace(Regex("[^a-zA-Z0-9]"), "_")}_${packageFile.length()}"
        )
        if (extractRoot.exists()) {
            extractRoot.deleteRecursively()
        }
        extractRoot.mkdirs()

        val guidToPathname = mutableMapOf<String, String>()
        val guidToAssetTempFile = mutableMapOf<String, File>()
        val guidToAssetSize = mutableMapOf<String, Long>()

        BufferedInputStream(FileInputStream(packageFile)).use { rawIn ->
            val tarStream: InputStream = try {
                GZIPInputStream(rawIn)
            } catch (_: Exception) {
                // In case the .unitypackage was already uncompressed tar
                FileInputStream(packageFile)
            }
            tarStream.use { tarIn ->
                val header = ByteArray(512)
                while (true) {
                    val readHeader = readFully(tarIn, header, 512)
                    if (readHeader < 512 || isAllZero(header)) break

                    val entryName = parseTarString(header, 0, 100)
                    val prefix = parseTarString(header, 345, 155)
                    val fullEntryName = if (prefix.isNotEmpty()) "$prefix/$entryName" else entryName
                    val entrySize = parseTarOctal(header, 124, 12)
                    val typeFlag = header[156].toInt().toChar()

                    val normalized = fullEntryName.removePrefix("./").trim('/')
                    val parts = normalized.split('/')
                    val guid = parts.firstOrNull().orEmpty()
                    val leaf = parts.lastOrNull().orEmpty()

                    val isDirectory = typeFlag == '5' || normalized.endsWith("/")
                    if (!isDirectory && entrySize >= 0 && guid.isNotEmpty()) {
                        when (leaf) {
                            "pathname" -> {
                                val bytes = readExactBytes(tarIn, entrySize.toInt())
                                val rawPath = String(bytes, StandardCharsets.UTF_8)
                                    .lineSequence()
                                    .firstOrNull()
                                    ?.trim()
                                    ?.replace('\\', '/')
                                    .orEmpty()
                                if (rawPath.isNotEmpty()) {
                                    guidToPathname[guid] = rawPath
                                }
                            }
                            "asset" -> {
                                val tempAsset = File(extractRoot, "${guid}_asset.bin")
                                streamBytesToFile(tarIn, entrySize, tempAsset)
                                guidToAssetTempFile[guid] = tempAsset
                                guidToAssetSize[guid] = entrySize
                            }
                            else -> {
                                skipExactBytes(tarIn, entrySize)
                            }
                        }
                    } else {
                        skipExactBytes(tarIn, entrySize)
                    }

                    val remainder = (entrySize % 512L).toInt()
                    if (remainder > 0) {
                        skipExactBytes(tarIn, (512 - remainder).toLong())
                    }
                }
            }
        }

        // Reconstruct final files with their real Unity pathnames inside extractRoot/workspace
        val workspaceDir = File(extractRoot, "workspace").apply { mkdirs() }
        val materialFiles = mutableListOf<String>()
        val textureFiles = mutableListOf<String>()
        val reconstructedFiles = mutableListOf<Triple<String, String, File>>()

        for ((guid, relPath) in guidToPathname) {
            val tempFile = guidToAssetTempFile[guid] ?: continue
            val cleanRelPath = relPath.trimStart('/')
            val outFile = File(workspaceDir, cleanRelPath)
            outFile.parentFile?.mkdirs()
            tempFile.renameTo(outFile)
            val ext = outFile.extension.lowercase(Locale.ROOT)
            if (ext in setOf("mat", "mtl")) {
                materialFiles.add(outFile.absolutePath)
            } else if (ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS) {
                textureFiles.add(outFile.absolutePath)
            }
            reconstructedFiles.add(Triple(guid, cleanRelPath, outFile))
        }

        val entries = reconstructedFiles.map { (guid, relPath, outFile) ->
            val ext = outFile.extension.lowercase(Locale.ROOT)
            val (status, detail) = fileManager.classifyExtension(ext)
            val category = when {
                ext in FileManager.SUPPORTED_3D_EXTENSIONS -> "3D Model"
                ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS -> "Texture"
                ext in setOf("mat", "mtl") -> "Material"
                ext in setOf("unity", "prefab", "asset") -> "Unity Native"
                else -> "Other Asset"
            }
            UnityAssetEntry(
                id = guid,
                name = outFile.name,
                relativePath = relPath,
                extension = ext,
                sizeBytes = outFile.length(),
                category = category,
                supportStatus = status,
                statusDetail = detail,
                localCachePath = outFile.absolutePath,
                associatedMaterialPaths = materialFiles,
                associatedTexturePaths = textureFiles
            )
        }.sortedWith(
            compareBy<UnityAssetEntry> {
                when (it.supportStatus) {
                    SupportStatus.SUPPORTED -> 0
                    SupportStatus.PARTIALLY_SUPPORTED -> 1
                    SupportStatus.NOT_SUPPORTED -> 2
                }
            }.thenBy { if (it.category == "3D Model") 0 else 1 }.thenBy { it.relativePath }
        )

        val hasAssets = entries.any { it.relativePath.startsWith("Assets/", ignoreCase = true) }
        val hasProjectSettings = entries.any { it.relativePath.startsWith("ProjectSettings/", ignoreCase = true) }
        val hasPackages = entries.any { it.relativePath.startsWith("Packages/", ignoreCase = true) }
        val supportedCount = entries.count { it.supportStatus == SupportStatus.SUPPORTED }
        val model3DCount = entries.count { it.category == "3D Model" }

        return UnityContainerReport(
            title = displayTitle,
            containerType = ".unitypackage",
            hasAssetsFolder = hasAssets,
            hasProjectSettingsFolder = hasProjectSettings,
            hasPackagesFolder = hasPackages,
            entries = entries,
            summaryNote = "Diekstrak secara lokal: ${entries.size} total file ($model3DCount model 3D siap dipreview, $supportedCount didukung penuh)."
        )
    }

    /**
     * Extracts and inspects a .zip file containing 3D assets, materials, and textures.
     */
    fun parseZipArchive(zipFile: File, displayTitle: String): UnityContainerReport {
        val extractRoot = File(
            context.cacheDir,
            "zip_${displayTitle.replace(Regex("[^a-zA-Z0-9]"), "_")}_${zipFile.length()}"
        )
        if (extractRoot.exists()) extractRoot.deleteRecursively()
        extractRoot.mkdirs()

        val extractedFiles = mutableListOf<Pair<String, File>>()
        val materialFiles = mutableListOf<String>()
        val textureFiles = mutableListOf<String>()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val cleanName = entry.name.replace('\\', '/').trimStart('/')
                    if (!cleanName.contains("..") && !cleanName.startsWith("__MACOSX")) {
                        val outFile = File(extractRoot, cleanName)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos, 16 * 1024)
                        }
                        val ext = outFile.extension.lowercase(Locale.ROOT)
                        if (ext in setOf("mat", "mtl")) materialFiles.add(outFile.absolutePath)
                        if (ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS) textureFiles.add(outFile.absolutePath)
                        extractedFiles.add(cleanName to outFile)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val entries = extractedFiles.mapIndexed { idx, (relPath, outFile) ->
            val ext = outFile.extension.lowercase(Locale.ROOT)
            val (status, detail) = fileManager.classifyExtension(ext)
            val category = when {
                ext in FileManager.SUPPORTED_3D_EXTENSIONS -> "3D Model"
                ext in FileManager.SUPPORTED_TEXTURE_EXTENSIONS -> "Texture"
                ext in setOf("mat", "mtl") -> "Material"
                else -> "Other File"
            }
            UnityAssetEntry(
                id = "zip_$idx",
                name = outFile.name,
                relativePath = relPath,
                extension = ext,
                sizeBytes = outFile.length(),
                category = category,
                supportStatus = status,
                statusDetail = detail,
                localCachePath = outFile.absolutePath,
                associatedMaterialPaths = materialFiles,
                associatedTexturePaths = textureFiles
            )
        }.sortedWith(
            compareBy<UnityAssetEntry> {
                when (it.supportStatus) {
                    SupportStatus.SUPPORTED -> 0
                    SupportStatus.PARTIALLY_SUPPORTED -> 1
                    SupportStatus.NOT_SUPPORTED -> 2
                }
            }.thenBy { if (it.category == "3D Model") 0 else 1 }
        )

        val hasAssets = entries.any { it.relativePath.startsWith("Assets/", ignoreCase = true) }
        return UnityContainerReport(
            title = displayTitle,
            containerType = "ZIP 3D Archive",
            hasAssetsFolder = hasAssets,
            hasProjectSettingsFolder = entries.any { it.relativePath.startsWith("ProjectSettings/", ignoreCase = true) },
            hasPackagesFolder = entries.any { it.relativePath.startsWith("Packages/", ignoreCase = true) },
            entries = entries,
            summaryNote = "ZIP diekstrak secara lokal: ${entries.size} file ditemukan."
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val r = input.read(buffer, total, length - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun isAllZero(buffer: ByteArray): Boolean {
        for (b in buffer) {
            if (b.toInt() != 0) return false
        }
        return true
    }

    private fun parseTarString(header: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        val limit = offset + maxLen
        while (end < limit && header[end].toInt() != 0) {
            end++
        }
        return String(header, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun parseTarOctal(header: ByteArray, offset: Int, maxLen: Int): Long {
        val raw = parseTarString(header, offset, maxLen).trim()
        if (raw.isEmpty()) return 0L
        return try {
            raw.toLong(8)
        } catch (_: Exception) {
            0L
        }
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val bos = ByteArrayOutputStream(count.coerceAtLeast(0))
        val buf = ByteArray(4096)
        var remaining = count
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size, remaining))
            if (r < 0) break
            bos.write(buf, 0, r)
            remaining -= r
        }
        return bos.toByteArray()
    }

    private fun streamBytesToFile(input: InputStream, count: Long, target: File) {
        FileOutputStream(target).use { out ->
            val buf = ByteArray(8192)
            var remaining = count
            while (remaining > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                remaining -= r
            }
        }
    }

    private fun skipExactBytes(input: InputStream, count: Long) {
        val buf = ByteArray(4096)
        var remaining = count
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) break
            remaining -= r
        }
    }
}
