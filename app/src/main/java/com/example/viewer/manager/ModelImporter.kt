package com.example.viewer.manager

import android.util.Base64
import com.example.viewer.model.AnimationClipData
import com.example.viewer.model.MaterialData
import com.example.viewer.model.Model3DData
import com.example.viewer.model.SubMeshData
import com.example.viewer.model.TextureData
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONObject

sealed class ModelImportResult {
    data class Success(val model: Model3DData) : ModelImportResult()
    data class Error(val message: String) : ModelImportResult()
}

class ModelImporter(
    private val textureManager: TextureManager,
    private val materialManager: MaterialManager
) {

    fun importModelFile(
        file: File,
        displayName: String = file.name,
        associatedMaterialPaths: List<String> = emptyList(),
        associatedTexturePaths: List<String> = emptyList(),
        maxTextureDimension: Int = 1024
    ): ModelImportResult {
        if (!file.exists() || !file.canRead() || file.length() == 0L) {
            return ModelImportResult.Error("File tidak dapat dibaca atau kemungkinan rusak.")
        }

        val ext = displayName.substringAfterLast('.', file.extension).lowercase(Locale.ROOT)
        if (ext !in FileManager.SUPPORTED_3D_EXTENSIONS) {
            return ModelImportResult.Error("Format ini belum didukung.")
        }

        return try {
            val rawModel = when (ext) {
                "glb" -> parseGlb(file, displayName, maxTextureDimension)
                "gltf" -> parseGltf(file, displayName, maxTextureDimension)
                "obj" -> parseObj(file, displayName, associatedMaterialPaths, associatedTexturePaths, maxTextureDimension)
                "fbx" -> parseFbx(file, displayName, associatedMaterialPaths, associatedTexturePaths, maxTextureDimension)
                "dae" -> parseDae(file, displayName)
                "3ds" -> parse3ds(file, displayName)
                else -> return ModelImportResult.Error("Format ini belum didukung.")
            }

            if (rawModel == null || rawModel.subMeshes.isEmpty() || rawModel.vertexCount == 0) {
                ModelImportResult.Error("File tidak dapat dibaca atau kemungkinan rusak.")
            } else {
                val enriched = attachSiblingAssets(
                    rawModel,
                    file.parentFile,
                    associatedMaterialPaths,
                    associatedTexturePaths,
                    maxTextureDimension
                )
                ModelImportResult.Success(normalizeModelBounds(enriched))
            }
        } catch (_: OutOfMemoryError) {
            ModelImportResult.Error("Ukuran model terlalu besar untuk kapasitas RAM perangkat saat ini.")
        } catch (_: Exception) {
            ModelImportResult.Error("File tidak dapat dibaca atau kemungkinan rusak.")
        }
    }

    private fun parseGlb(file: File, displayName: String, maxTexDim: Int): Model3DData? {
        val bytes = file.readBytes()
        if (bytes.size < 20) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != 0x46546C67) return null // "glTF"
        val version = buf.int
        if (version != 2) return null
        val totalLength = buf.int
        if (totalLength > bytes.size) return null

        var jsonString: String? = null
        var binChunk: ByteArray? = null

        while (buf.remaining() >= 8) {
            val chunkLen = buf.int
            val chunkType = buf.int
            if (chunkLen < 0 || chunkLen > buf.remaining()) break
            val chunkBytes = ByteArray(chunkLen)
            buf.get(chunkBytes)
            if (chunkType == 0x4E4F534A) { // JSON
                jsonString = String(chunkBytes, StandardCharsets.UTF_8).trim()
            } else if (chunkType == 0x004E4942) { // BIN
                binChunk = chunkBytes
            }
        }
        val json = JSONObject(jsonString ?: return null)
        return parseGltfJsonAndBuffers(json, binChunk, file.parentFile, displayName, "GLB (Binary glTF 2.0)", file.length(), maxTexDim)
    }

    private fun parseGltf(file: File, displayName: String, maxTexDim: Int): Model3DData? {
        val jsonText = file.readText()
        val json = JSONObject(jsonText)
        var binBuffer: ByteArray? = null
        val buffersArr = json.optJSONArray("buffers")
        if (buffersArr != null && buffersArr.length() > 0) {
            val firstBuf = buffersArr.getJSONObject(0)
            val uri = firstBuf.optString("uri", "")
            if (uri.startsWith("data:")) {
                val base64Part = uri.substringAfter("base64,", "")
                if (base64Part.isNotEmpty()) {
                    binBuffer = Base64.decode(base64Part, Base64.DEFAULT)
                }
            } else if (uri.isNotEmpty()) {
                val extFile = File(file.parentFile, uri)
                if (extFile.exists()) {
                    binBuffer = extFile.readBytes()
                }
            }
        }
        return parseGltfJsonAndBuffers(json, binBuffer, file.parentFile, displayName, "GLTF 2.0", file.length(), maxTexDim)
    }

    private fun parseGltfJsonAndBuffers(
        json: JSONObject,
        binChunk: ByteArray?,
        parentDir: File?,
        displayName: String,
        formatLabel: String,
        fileSizeBytes: Long,
        maxTexDim: Int
    ): Model3DData? {
        val accessors = json.optJSONArray("accessors") ?: return null
        val bufferViews = json.optJSONArray("bufferViews") ?: return null
        val meshes = json.optJSONArray("meshes") ?: return null
        val bin = binChunk ?: return null

        fun readFloatAccessor(accIdx: Int, componentsPerElem: Int): FloatArray? {
            if (accIdx < 0 || accIdx >= accessors.length()) return null
            val acc = accessors.getJSONObject(accIdx)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            val count = acc.optInt("count", 0)
            val byteOffset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val stride = bv.optInt("byteStride", componentsPerElem * 4)
            val out = FloatArray(count * componentsPerElem)
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val elemStart = byteOffset + i * stride
                if (elemStart + componentsPerElem * 4 > bin.size) break
                for (c in 0 until componentsPerElem) {
                    out[i * componentsPerElem + c] = bb.getFloat(elemStart + c * 4)
                }
            }
            return out
        }

        fun readIndexAccessor(accIdx: Int): IntArray? {
            if (accIdx < 0 || accIdx >= accessors.length()) return null
            val acc = accessors.getJSONObject(accIdx)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            val count = acc.optInt("count", 0)
            val compType = acc.optInt("componentType", 5123)
            val byteOffset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val out = IntArray(count)
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                when (compType) {
                    5121 -> { // UNSIGNED_BYTE
                        val pos = byteOffset + i
                        if (pos < bin.size) out[i] = bin[pos].toInt() and 0xFF
                    }
                    5123 -> { // UNSIGNED_SHORT
                        val pos = byteOffset + i * 2
                        if (pos + 2 <= bin.size) out[i] = bb.getShort(pos).toInt() and 0xFFFF
                    }
                    5125 -> { // UNSIGNED_INT
                        val pos = byteOffset + i * 4
                        if (pos + 4 <= bin.size) out[i] = bb.getInt(pos)
                    }
                }
            }
            return out
        }

        val materials = mutableListOf<MaterialData>()
        val matsArr = json.optJSONArray("materials")
        if (matsArr != null) {
            for (i in 0 until matsArr.length()) {
                val mObj = matsArr.getJSONObject(i)
                val mName = mObj.optString("name", "Material_$i")
                val pbr = mObj.optJSONObject("pbrMetallicRoughness")
                val baseColor = floatArrayOf(0.8f, 0.82f, 0.86f, 1.0f)
                var metallic = 0.2f
                var roughness = 0.45f
                if (pbr != null) {
                    val bcf = pbr.optJSONArray("baseColorFactor")
                    if (bcf != null && bcf.length() >= 3) {
                        baseColor[0] = bcf.optDouble(0, 0.8).toFloat()
                        baseColor[1] = bcf.optDouble(1, 0.8).toFloat()
                        baseColor[2] = bcf.optDouble(2, 0.8).toFloat()
                        baseColor[3] = bcf.optDouble(3, 1.0).toFloat()
                    }
                    metallic = pbr.optDouble("metallicFactor", 0.2).toFloat()
                    roughness = pbr.optDouble("roughnessFactor", 0.45).toFloat()
                }
                val emArr = mObj.optJSONArray("emissiveFactor")
                val emission = if (emArr != null && emArr.length() >= 3) {
                    floatArrayOf(
                        emArr.optDouble(0, 0.0).toFloat(),
                        emArr.optDouble(1, 0.0).toFloat(),
                        emArr.optDouble(2, 0.0).toFloat()
                    )
                } else floatArrayOf(0f, 0f, 0f)
                materials.add(
                    MaterialData(
                        name = mName,
                        baseColor = baseColor,
                        metallic = metallic,
                        roughness = roughness,
                        emission = emission,
                        sourceShaderName = "glTF 2.0 PBR Metallic-Roughness"
                    )
                )
            }
        }
        if (materials.isEmpty()) {
            materials.add(materialManager.createDefaultMaterial())
        }

        val loadedTextures = mutableListOf<TextureData>()
        val missingTextures = mutableListOf<String>()
        val imagesArr = json.optJSONArray("images")
        if (imagesArr != null) {
            for (i in 0 until imagesArr.length()) {
                val imgObj = imagesArr.getJSONObject(i)
                val imgName = imgObj.optString("name", "Texture_$i")
                val uri = imgObj.optString("uri", "")
                val bvIdx = imgObj.optInt("bufferView", -1)
                val mime = imgObj.optString("mimeType", "image/png").substringAfter('/')
                if (bvIdx >= 0 && bvIdx < bufferViews.length()) {
                    val bv = bufferViews.getJSONObject(bvIdx)
                    val offset = bv.optInt("byteOffset", 0)
                    val len = bv.optInt("byteLength", 0)
                    if (offset >= 0 && offset + len <= bin.size && len > 0) {
                        val imgBytes = bin.copyOfRange(offset, offset + len)
                        val tex = textureManager.loadTextureFromBytes(imgName, mime, imgBytes, maxTexDim)
                        if (tex != null) loadedTextures.add(tex)
                    }
                } else if (uri.isNotEmpty() && !uri.startsWith("data:")) {
                    val texFile = if (parentDir != null) File(parentDir, uri) else null
                    if (texFile != null && texFile.exists()) {
                        val tex = textureManager.loadTextureFromFile(texFile, maxTexDim)
                        if (tex != null) loadedTextures.add(tex) else missingTextures.add(uri)
                    } else {
                        missingTextures.add(uri)
                    }
                }
            }
        }

        val subMeshes = mutableListOf<SubMeshData>()
        var totalVertices = 0
        var totalTriangles = 0

        for (m in 0 until meshes.length()) {
            val meshObj = meshes.getJSONObject(m)
            val meshName = meshObj.optString("name", "Mesh_$m")
            val prims = meshObj.optJSONArray("primitives") ?: continue
            for (p in 0 until prims.length()) {
                val prim = prims.getJSONObject(p)
                val attrs = prim.optJSONObject("attributes") ?: continue
                val posAcc = attrs.optInt("POSITION", -1)
                val normAcc = attrs.optInt("NORMAL", -1)
                val uvAcc = attrs.optInt("TEXCOORD_0", -1)
                val idxAcc = prim.optInt("indices", -1)
                val matIdx = prim.optInt("material", 0).coerceIn(0, materials.size - 1)

                val positions = readFloatAccessor(posAcc, 3) ?: continue
                val vCount = positions.size / 3
                if (vCount < 3) continue
                val indices = readIndexAccessor(idxAcc) ?: IntArray(vCount) { it }
                val normals = readFloatAccessor(normAcc, 3) ?: computeVertexNormals(positions, indices)
                val uvs = readFloatAccessor(uvAcc, 2) ?: FloatArray(vCount * 2)

                totalVertices += vCount
                totalTriangles += indices.size / 3
                subMeshes.add(
                    SubMeshData(
                        name = "$meshName#$p",
                        positions = positions,
                        normals = normals,
                        uvs = uvs,
                        indices = indices,
                        materialIndex = matIdx
                    )
                )
            }
        }

        val animations = mutableListOf<AnimationClipData>()
        val animsArr = json.optJSONArray("animations")
        if (animsArr != null) {
            for (a in 0 until animsArr.length()) {
                val aObj = animsArr.getJSONObject(a)
                val aName = aObj.optString("name", "Clip_${a + 1}").ifEmpty { "Clip_${a + 1}" }
                val dur = aObj.optDouble("duration", 2.0).toFloat().coerceAtLeast(0.4f)
                animations.add(
                    AnimationClipData(
                        name = aName,
                        durationSeconds = dur,
                        keyframes = AnimationManager.buildKeyframesForNamedClip(aName, dur)
                    )
                )
            }
        }

        return Model3DData(
            name = displayName.substringBeforeLast('.'),
            fileFormat = formatLabel,
            fileSizeBytes = fileSizeBytes,
            vertexCount = totalVertices,
            triangleCount = totalTriangles,
            subMeshes = subMeshes,
            materials = materials,
            textures = loadedTextures,
            animations = animations,
            missingTextures = missingTextures,
            warningMessage = if (missingTextures.isNotEmpty()) {
                "Model berhasil dibuka, tetapi beberapa texture tidak ditemukan."
            } else null
        )
    }

    private fun parseObj(
        file: File,
        displayName: String,
        associatedMaterials: List<String>,
        associatedTextures: List<String>,
        maxTexDim: Int
    ): Model3DData? {
        val rawPos = mutableListOf<Float>()
        val rawNorm = mutableListOf<Float>()
        val rawUv = mutableListOf<Float>()

        val outPos = mutableListOf<Float>()
        val outNorm = mutableListOf<Float>()
        val outUv = mutableListOf<Float>()
        val outIndices = mutableListOf<Int>()
        val vertexCache = HashMap<String, Int>()

        val referencedMtlFiles = mutableListOf<String>()
        var objectName = displayName.substringBeforeLast('.')

        BufferedReader(FileReader(file)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val tokens = trimmed.split(Regex("\\s+"))
                    when (tokens[0]) {
                        "o", "g" -> if (tokens.size >= 2) objectName = tokens[1]
                        "mtllib" -> if (tokens.size >= 2) referencedMtlFiles.add(tokens.drop(1).joinToString(" "))
                        "v" -> if (tokens.size >= 4) {
                            rawPos.add(tokens[1].toFloatOrNull() ?: 0f)
                            rawPos.add(tokens[2].toFloatOrNull() ?: 0f)
                            rawPos.add(tokens[3].toFloatOrNull() ?: 0f)
                        }
                        "vn" -> if (tokens.size >= 4) {
                            rawNorm.add(tokens[1].toFloatOrNull() ?: 0f)
                            rawNorm.add(tokens[2].toFloatOrNull() ?: 1f)
                            rawNorm.add(tokens[3].toFloatOrNull() ?: 0f)
                        }
                        "vt" -> if (tokens.size >= 3) {
                            rawUv.add(tokens[1].toFloatOrNull() ?: 0f)
                            rawUv.add(1.0f - (tokens[2].toFloatOrNull() ?: 0f))
                        }
                        "f" -> if (tokens.size >= 4) {
                            val faceVerts = IntArray(tokens.size - 1)
                            for (i in 1 until tokens.size) {
                                val key = tokens[i]
                                val existing = vertexCache[key]
                                if (existing != null) {
                                    faceVerts[i - 1] = existing
                                } else {
                                    val sub = key.split('/')
                                    val vIdxRaw = sub.getOrNull(0)?.toIntOrNull() ?: 1
                                    val vtIdxRaw = sub.getOrNull(1)?.toIntOrNull() ?: 0
                                    val vnIdxRaw = sub.getOrNull(2)?.toIntOrNull() ?: 0

                                    val pCount = rawPos.size / 3
                                    val vIdx = (if (vIdxRaw < 0) pCount + vIdxRaw else vIdxRaw - 1).coerceIn(0, maxOf(0, pCount - 1))
                                    outPos.add(rawPos.getOrElse(vIdx * 3) { 0f })
                                    outPos.add(rawPos.getOrElse(vIdx * 3 + 1) { 0f })
                                    outPos.add(rawPos.getOrElse(vIdx * 3 + 2) { 0f })

                                    if (vtIdxRaw != 0 && rawUv.isNotEmpty()) {
                                        val uvCount = rawUv.size / 2
                                        val vtIdx = (if (vtIdxRaw < 0) uvCount + vtIdxRaw else vtIdxRaw - 1).coerceIn(0, uvCount - 1)
                                        outUv.add(rawUv[vtIdx * 2])
                                        outUv.add(rawUv[vtIdx * 2 + 1])
                                    } else {
                                        outUv.add(0f); outUv.add(0f)
                                    }

                                    if (vnIdxRaw != 0 && rawNorm.isNotEmpty()) {
                                        val nCount = rawNorm.size / 3
                                        val vnIdx = (if (vnIdxRaw < 0) nCount + vnIdxRaw else vnIdxRaw - 1).coerceIn(0, nCount - 1)
                                        outNorm.add(rawNorm[vnIdx * 3])
                                        outNorm.add(rawNorm[vnIdx * 3 + 1])
                                        outNorm.add(rawNorm[vnIdx * 3 + 2])
                                    }

                                    val newIndex = (outPos.size / 3) - 1
                                    vertexCache[key] = newIndex
                                    faceVerts[i - 1] = newIndex
                                }
                            }
                            // Fan triangulation for triangles, quads, and polygons
                            for (t in 1 until faceVerts.size - 1) {
                                outIndices.add(faceVerts[0])
                                outIndices.add(faceVerts[t])
                                outIndices.add(faceVerts[t + 1])
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
        }

        if (outPos.isEmpty() || outIndices.isEmpty()) return null
        val posArray = outPos.toFloatArray()
        val idxArray = outIndices.toIntArray()
        val normArray = if (outNorm.size == posArray.size) {
            outNorm.toFloatArray()
        } else {
            computeVertexNormals(posArray, idxArray)
        }
        val uvArray = outUv.toFloatArray()

        val materials = mutableListOf<MaterialData>()
        val parent = file.parentFile
        for (mtlName in referencedMtlFiles) {
            val mtlFile = if (parent != null) File(parent, mtlName) else null
            if (mtlFile != null && mtlFile.exists()) {
                materials.addAll(materialManager.parseMtlFile(mtlFile))
            }
        }
        val subMesh = SubMeshData(
            name = objectName,
            positions = posArray,
            normals = normArray,
            uvs = uvArray,
            indices = idxArray,
            materialIndex = 0
        )

        return Model3DData(
            name = displayName.substringBeforeLast('.'),
            fileFormat = "OBJ (Wavefront)",
            fileSizeBytes = file.length(),
            vertexCount = posArray.size / 3,
            triangleCount = idxArray.size / 3,
            subMeshes = listOf(subMesh),
            materials = materials.ifEmpty { listOf(materialManager.createDefaultMaterial()) },
            textures = emptyList(),
            animations = emptyList()
        )
    }

    private fun parseFbx(
        file: File,
        displayName: String,
        associatedMaterials: List<String>,
        associatedTextures: List<String>,
        maxTexDim: Int
    ): Model3DData? {
        val headerBytes = ByteArray(27.coerceAtMost(file.length().toInt()))
        file.inputStream().use { it.read(headerBytes) }
        val headerStr = String(headerBytes, StandardCharsets.US_ASCII)
        return if (headerStr.startsWith("Kaydara FBX Binary")) {
            parseBinaryFbx(file, displayName)
        } else {
            parseAsciiFbx(file, displayName)
        }
    }

    private fun parseAsciiFbx(file: File, displayName: String): Model3DData? {
        val text = file.readText()
        val vertBlockMatch = Regex("""Vertices:\s*\*\d+\s*\{\s*a:\s*([^}]+)\}""").find(text) ?: return null
        val polyBlockMatch = Regex("""PolygonVertexIndex:\s*\*\d+\s*\{\s*a:\s*([^}]+)\}""").find(text) ?: return null

        val rawVerts = vertBlockMatch.groupValues[1]
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
        val rawPoly = polyBlockMatch.groupValues[1]
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toIntArray()

        if (rawVerts.size < 9 || rawPoly.size < 3) return null
        return buildModelFromFbxArrays(file, displayName, "FBX (ASCII)", rawVerts, rawPoly, text)
    }

    private fun parseBinaryFbx(file: File, displayName: String): Model3DData? {
        val bytes = file.readBytes()
        if (bytes.size < 64) return null
        // Scan binary FBX stream for "Vertices" and "PolygonVertexIndex" property arrays
        val verticesDoubles = findFbxBinaryNumericArray(bytes, "Vertices")
        val polyInts = findFbxBinaryIntArray(bytes, "PolygonVertexIndex")
        if (verticesDoubles == null || polyInts == null || verticesDoubles.size < 9 || polyInts.size < 3) {
            return null
        }
        return buildModelFromFbxArrays(file, displayName, "FBX (Binary)", verticesDoubles, polyInts, "")
    }

    private fun buildModelFromFbxArrays(
        file: File,
        displayName: String,
        formatLabel: String,
        rawVerts: FloatArray,
        rawPoly: IntArray,
        asciiContent: String
    ): Model3DData {
        val indices = mutableListOf<Int>()
        val currentPolygon = mutableListOf<Int>()
        val maxVertIdx = (rawVerts.size / 3) - 1

        for (idx in rawPoly) {
            if (idx < 0) {
                val decoded = (-idx) - 1
                currentPolygon.add(decoded.coerceIn(0, maxVertIdx))
                if (currentPolygon.size >= 3) {
                    for (t in 1 until currentPolygon.size - 1) {
                        indices.add(currentPolygon[0])
                        indices.add(currentPolygon[t])
                        indices.add(currentPolygon[t + 1])
                    }
                }
                currentPolygon.clear()
            } else {
                currentPolygon.add(idx.coerceIn(0, maxVertIdx))
            }
        }

        val idxArray = indices.toIntArray()
        val normals = computeVertexNormals(rawVerts, idxArray)
        val vCount = rawVerts.size / 3
        val uvs = FloatArray(vCount * 2) { i ->
            if (i % 2 == 0) (rawVerts[(i / 2) * 3] + 1f) * 0.5f else (rawVerts[(i / 2) * 3 + 2] + 1f) * 0.5f
        }

        val materials = mutableListOf<MaterialData>()
        if (asciiContent.isNotEmpty()) {
            val diffMatch = Regex("""P:\s*"DiffuseColor",\s*"Color"[^,]*,[^,]*,([0-9.]+),([0-9.]+),([0-9.]+)""").find(asciiContent)
            if (diffMatch != null) {
                val r = diffMatch.groupValues[1].toFloatOrNull() ?: 0.8f
                val g = diffMatch.groupValues[2].toFloatOrNull() ?: 0.6f
                val b = diffMatch.groupValues[3].toFloatOrNull() ?: 0.2f
                materials.add(
                    MaterialData(
                        name = "FBX_Material",
                        baseColor = floatArrayOf(r, g, b, 1.0f),
                        metallic = 0.45f,
                        roughness = 0.35f,
                        sourceShaderName = "FBX Phong/PBR"
                    )
                )
            }
        }
        if (materials.isEmpty()) materials.add(materialManager.createDefaultMaterial("FBX_Surface"))

        val animations = mutableListOf<AnimationClipData>()
        if (asciiContent.isNotEmpty()) {
            Regex("""AnimationStack:\s*\d+,\s*"AnimStack::([^"]+)"""").findAll(asciiContent).forEach { m ->
                val clipName = m.groupValues[1].trim()
                if (clipName.isNotEmpty()) {
                    animations.add(
                        AnimationClipData(
                            name = clipName,
                            durationSeconds = 2.0f,
                            keyframes = AnimationManager.buildKeyframesForNamedClip(clipName, 2.0f)
                        )
                    )
                }
            }
        }

        return Model3DData(
            name = displayName.substringBeforeLast('.'),
            fileFormat = formatLabel,
            fileSizeBytes = file.length(),
            vertexCount = vCount,
            triangleCount = idxArray.size / 3,
            subMeshes = listOf(
                SubMeshData(
                    name = displayName.substringBeforeLast('.'),
                    positions = rawVerts,
                    normals = normals,
                    uvs = uvs,
                    indices = idxArray,
                    materialIndex = 0
                )
            ),
            materials = materials,
            textures = emptyList(),
            animations = animations
        )
    }

    private fun findFbxBinaryNumericArray(bytes: ByteArray, nodeName: String): FloatArray? {
        val needle = nodeName.toByteArray(StandardCharsets.US_ASCII)
        val idx = indexOfBytes(bytes, needle)
        if (idx < 0 || idx + needle.size + 13 >= bytes.size) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (scan in (idx + needle.size) until minOf(bytes.size - 13, idx + needle.size + 32)) {
            val typeChar = bytes[scan].toInt().toChar()
            if (typeChar == 'd' || typeChar == 'f') {
                val arrLen = bb.getInt(scan + 1)
                val encoding = bb.getInt(scan + 5)
                val compLen = bb.getInt(scan + 9)
                if (arrLen in 3..500_000 && encoding in 0..1 && compLen >= 0 && scan + 13 + compLen <= bytes.size) {
                    val elemBytes = if (typeChar == 'd') 8 else 4
                    val rawPayload = if (encoding == 1) {
                        inflateZlib(bytes, scan + 13, compLen, arrLen * elemBytes) ?: continue
                    } else {
                        bytes.copyOfRange(scan + 13, scan + 13 + arrLen * elemBytes)
                    }
                    val pBuf = ByteBuffer.wrap(rawPayload).order(ByteOrder.LITTLE_ENDIAN)
                    return FloatArray(arrLen) { i ->
                        if (typeChar == 'd') pBuf.getDouble(i * 8).toFloat() else pBuf.getFloat(i * 4)
                    }
                }
            }
        }
        return null
    }

    private fun findFbxBinaryIntArray(bytes: ByteArray, nodeName: String): IntArray? {
        val needle = nodeName.toByteArray(StandardCharsets.US_ASCII)
        val idx = indexOfBytes(bytes, needle)
        if (idx < 0 || idx + needle.size + 13 >= bytes.size) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (scan in (idx + needle.size) until minOf(bytes.size - 13, idx + needle.size + 32)) {
            val typeChar = bytes[scan].toInt().toChar()
            if (typeChar == 'i') {
                val arrLen = bb.getInt(scan + 1)
                val encoding = bb.getInt(scan + 5)
                val compLen = bb.getInt(scan + 9)
                if (arrLen in 3..1_000_000 && encoding in 0..1 && compLen >= 0 && scan + 13 + compLen <= bytes.size) {
                    val rawPayload = if (encoding == 1) {
                        inflateZlib(bytes, scan + 13, compLen, arrLen * 4) ?: continue
                    } else {
                        bytes.copyOfRange(scan + 13, scan + 13 + arrLen * 4)
                    }
                    val pBuf = ByteBuffer.wrap(rawPayload).order(ByteOrder.LITTLE_ENDIAN)
                    return IntArray(arrLen) { i -> pBuf.getInt(i * 4) }
                }
            }
        }
        return null
    }

    private fun inflateZlib(src: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(src, offset, length)
            val bos = ByteArrayOutputStream(expectedSize)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                bos.write(buf, 0, count)
            }
            inflater.end()
            bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..(haystack.size - needle.size)) {
            if (haystack[i] == (needle.size.toByte())) {
                for (j in needle.indices) {
                    if (haystack[i + 1 + j] != needle[j]) continue@outer
                }
                return i + 1
            }
        }
        return -1
    }

    private fun parseDae(file: File, displayName: String): Model3DData? {
        val xml = file.readText()
        val floatArrays = Regex("""<float_array[^>]*>([^<]+)</float_array>""")
            .findAll(xml)
            .map { m -> m.groupValues[1].trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }.toFloatArray() }
            .filter { it.size >= 9 }
            .toList()
        if (floatArrays.isEmpty()) return null
        val positions = floatArrays.first()
        val pMatch = Regex("""<p>([^<]+)</p>""").find(xml)
        val vCount = positions.size / 3
        val indices = if (pMatch != null) {
            val rawInts = pMatch.groupValues[1].trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            val step = (rawInts.size / (vCount.coerceAtLeast(1))).coerceIn(1, 3)
            IntArray(rawInts.size / step) { i -> rawInts[i * step].coerceIn(0, vCount - 1) }
        } else {
            IntArray(vCount) { it }
        }
        val normals = computeVertexNormals(positions, indices)
        return Model3DData(
            name = displayName.substringBeforeLast('.'),
            fileFormat = "DAE (COLLADA)",
            fileSizeBytes = file.length(),
            vertexCount = vCount,
            triangleCount = indices.size / 3,
            subMeshes = listOf(
                SubMeshData(
                    name = displayName.substringBeforeLast('.'),
                    positions = positions,
                    normals = normals,
                    uvs = FloatArray(vCount * 2),
                    indices = indices
                )
            ),
            materials = listOf(materialManager.createDefaultMaterial("COLLADA_Material")),
            textures = emptyList(),
            animations = emptyList()
        )
    }

    private fun parse3ds(file: File, displayName: String): Model3DData? {
        val bytes = file.readBytes()
        if (bytes.size < 16) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if ((bb.getShort(0).toInt() and 0xFFFF) != 0x4D4D) return null

        var positions: FloatArray? = null
        var indices: IntArray? = null

        var offset = 6
        while (offset + 6 <= bytes.size) {
            val chunkId = bb.getShort(offset).toInt() and 0xFFFF
            val chunkLen = bb.getInt(offset + 2)
            if (chunkLen < 6 || offset + chunkLen > bytes.size) break
            when (chunkId) {
                0x3D3D, 0x4100 -> offset += 6
                0x4000 -> {
                    var strEnd = offset + 6
                    while (strEnd < bytes.size && bytes[strEnd].toInt() != 0) strEnd++
                    offset = strEnd + 1
                }
                0x4110 -> { // Vertices list
                    val count = bb.getShort(offset + 6).toInt() and 0xFFFF
                    val posArr = FloatArray(count * 3)
                    var p = offset + 8
                    for (i in 0 until count) {
                        if (p + 12 > bytes.size) break
                        posArr[i * 3] = bb.getFloat(p)
                        posArr[i * 3 + 1] = bb.getFloat(p + 8)
                        posArr[i * 3 + 2] = bb.getFloat(p + 4)
                        p += 12
                    }
                    positions = posArr
                    offset += chunkLen
                }
                0x4120 -> { // Faces list
                    val count = bb.getShort(offset + 6).toInt() and 0xFFFF
                    val idxArr = IntArray(count * 3)
                    var p = offset + 8
                    for (i in 0 until count) {
                        if (p + 8 > bytes.size) break
                        idxArr[i * 3] = bb.getShort(p).toInt() and 0xFFFF
                        idxArr[i * 3 + 1] = bb.getShort(p + 2).toInt() and 0xFFFF
                        idxArr[i * 3 + 2] = bb.getShort(p + 4).toInt() and 0xFFFF
                        p += 8
                    }
                    indices = idxArr
                    offset += chunkLen
                }
                else -> offset += chunkLen
            }
        }

        val pos = positions ?: return null
        val idx = indices ?: return null
        val normals = computeVertexNormals(pos, idx)
        return Model3DData(
            name = displayName.substringBeforeLast('.'),
            fileFormat = "3DS (Autodesk 3D Studio)",
            fileSizeBytes = file.length(),
            vertexCount = pos.size / 3,
            triangleCount = idx.size / 3,
            subMeshes = listOf(
                SubMeshData(
                    name = displayName.substringBeforeLast('.'),
                    positions = pos,
                    normals = normals,
                    uvs = FloatArray((pos.size / 3) * 2),
                    indices = idx
                )
            ),
            materials = listOf(materialManager.createDefaultMaterial("3DS_Material")),
            textures = emptyList(),
            animations = emptyList()
        )
    }

    private fun attachSiblingAssets(
        model: Model3DData,
        parentDir: File?,
        associatedMaterials: List<String>,
        associatedTextures: List<String>,
        maxTexDim: Int
    ): Model3DData {
        val loadedMaterials = model.materials.toMutableList()
        val loadedTextures = model.textures.toMutableList()
        val missingTextures = model.missingTextures.toMutableList()

        // Discover Unity .mat files or .mtl files from associated paths
        for (matPath in associatedMaterials) {
            val f = File(matPath)
            if (f.exists()) {
                if (f.extension.equals("mat", ignoreCase = true)) {
                    val unityMat = materialManager.parseUnityMatFile(f)
                    if (loadedMaterials.size == 1 && loadedMaterials[0].name.startsWith("Default")) {
                        loadedMaterials[0] = unityMat
                    } else if (loadedMaterials.none { it.name == unityMat.name }) {
                        loadedMaterials.add(unityMat)
                    }
                } else if (f.extension.equals("mtl", ignoreCase = true)) {
                    val mtls = materialManager.parseMtlFile(f)
                    for (m in mtls) {
                        if (loadedMaterials.none { it.name == m.name }) loadedMaterials.add(m)
                    }
                }
            }
        }

        // Check textures referenced by materials or present in the same directory / package
        val candidateTexFiles = mutableMapOf<String, File>()
        parentDir?.listFiles()?.forEach { f ->
            if (f.isFile && f.extension.lowercase(Locale.ROOT) in FileManager.SUPPORTED_TEXTURE_EXTENSIONS) {
                candidateTexFiles[f.name.lowercase(Locale.ROOT)] = f
            }
        }
        associatedTextures.forEach { path ->
            val f = File(path)
            if (f.exists()) {
                candidateTexFiles[f.name.lowercase(Locale.ROOT)] = f
            }
        }

        for (mat in loadedMaterials) {
            val refTex = mat.diffuseTextureName
            if (!refTex.isNullOrEmpty()) {
                val found = candidateTexFiles[refTex.lowercase(Locale.ROOT)]
                if (found != null) {
                    if (loadedTextures.none { it.name.equals(found.name, ignoreCase = true) }) {
                        textureManager.loadTextureFromFile(found, maxTexDim)?.let { loadedTextures.add(it) }
                    }
                } else {
                    missingTextures.add(refTex)
                }
            }
        }

        // Also load up to 4 sibling textures if none were explicitly linked yet
        if (loadedTextures.isEmpty() && candidateTexFiles.isNotEmpty()) {
            candidateTexFiles.values.take(4).forEach { texFile ->
                textureManager.loadTextureFromFile(texFile, maxTexDim)?.let { loadedTextures.add(it) }
            }
        }

        val warning = if (missingTextures.isNotEmpty()) {
            "Model berhasil dibuka, tetapi beberapa texture tidak ditemukan."
        } else {
            model.warningMessage
        }

        return model.copy(
            materials = loadedMaterials,
            textures = loadedTextures,
            missingTextures = missingTextures.distinct(),
            warningMessage = warning
        )
    }

    private fun normalizeModelBounds(model: Model3DData): Model3DData {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (sub in model.subMeshes) {
            val pos = sub.positions
            for (i in 0 until pos.size / 3) {
                val x = pos[i * 3]
                val y = pos[i * 3 + 1]
                val z = pos[i * 3 + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            }
        }

        if (minX > maxX) return model
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val spanX = maxX - minX
        val spanY = maxY - minY
        val spanZ = maxZ - minZ
        val maxSpan = max(max(spanX, spanY), spanZ).coerceAtLeast(0.0001f)
        val scale = 2.2f / maxSpan

        val normalizedSubs = model.subMeshes.map { sub ->
            val newPos = FloatArray(sub.positions.size)
            for (i in 0 until sub.positions.size / 3) {
                newPos[i * 3] = (sub.positions[i * 3] - cx) * scale
                // Place bottom of model near ground plane Y = 0
                newPos[i * 3 + 1] = (sub.positions[i * 3 + 1] - minY) * scale
                newPos[i * 3 + 2] = (sub.positions[i * 3 + 2] - cz) * scale
            }
            sub.copy(positions = newPos)
        }
        return model.copy(subMeshes = normalizedSubs, boundingRadius = 1.4f)
    }

    companion object {
        fun computeVertexNormals(positions: FloatArray, indices: IntArray): FloatArray {
            val normals = FloatArray(positions.size)
            val vCount = positions.size / 3
            val triCount = indices.size / 3
            for (t in 0 until triCount) {
                val i0 = indices[t * 3].coerceIn(0, vCount - 1)
                val i1 = indices[t * 3 + 1].coerceIn(0, vCount - 1)
                val i2 = indices[t * 3 + 2].coerceIn(0, vCount - 1)

                val ax = positions[i0 * 3]; val ay = positions[i0 * 3 + 1]; val az = positions[i0 * 3 + 2]
                val bx = positions[i1 * 3]; val by = positions[i1 * 3 + 1]; val bz = positions[i1 * 3 + 2]
                val cx = positions[i2 * 3]; val cy = positions[i2 * 3 + 1]; val cz = positions[i2 * 3 + 2]

                val ux = bx - ax; val uy = by - ay; val uz = bz - az
                val vx = cx - ax; val vy = cy - ay; val vz = cz - az

                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx

                normals[i0 * 3] += nx; normals[i0 * 3 + 1] += ny; normals[i0 * 3 + 2] += nz
                normals[i1 * 3] += nx; normals[i1 * 3 + 1] += ny; normals[i1 * 3 + 2] += nz
                normals[i2 * 3] += nx; normals[i2 * 3 + 1] += ny; normals[i2 * 3 + 2] += nz
            }
            for (i in 0 until vCount) {
                val nx = normals[i * 3]
                val ny = normals[i * 3 + 1]
                val nz = normals[i * 3 + 2]
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 0.00001f) {
                    normals[i * 3] = nx / len
                    normals[i * 3 + 1] = ny / len
                    normals[i * 3 + 2] = nz / len
                } else {
                    normals[i * 3] = 0f
                    normals[i * 3 + 1] = 1f
                    normals[i * 3 + 2] = 0f
                }
            }
            return normals
        }
    }
}
