package com.example.viewer.manager

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.example.viewer.model.RecentItem
import com.example.viewer.model.SupportStatus
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

class FileManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("unity_3d_viewer_prefs", Context.MODE_PRIVATE)

    companion object {
        val SUPPORTED_3D_EXTENSIONS = setOf("glb", "gltf", "obj", "fbx", "dae", "3ds")
        val SUPPORTED_ARCHIVE_EXTENSIONS = setOf("unitypackage", "zip")
        val SUPPORTED_TEXTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "tga")
        val PARTIAL_EXTENSIONS = setOf("mat", "mtl", "blend")
        val UNSUPPORTED_UNITY_EXTENSIONS = setOf(
            "unity", "prefab", "asset", "controller", "anim",
            "cs", "shader", "cginc", "hlsl", "exe", "dll", "so", "apk"
        )
    }

    fun classifyExtension(extRaw: String): Pair<SupportStatus, String> {
        val ext = extRaw.lowercase(Locale.ROOT).removePrefix(".")
        return when (ext) {
            "glb", "gltf" -> SupportStatus.SUPPORTED to "3D Mesh + PBR Material + Animation (glTF 2.0)"
            "obj" -> SupportStatus.SUPPORTED to "3D Wavefront Mesh (+ .mtl & textures)"
            "fbx" -> SupportStatus.SUPPORTED to "Autodesk FBX 3D Mesh & Animation"
            "dae" -> SupportStatus.SUPPORTED to "COLLADA 3D Mesh (.dae)"
            "3ds" -> SupportStatus.SUPPORTED to "3D Studio Binary Mesh (.3ds)"
            "unitypackage" -> SupportStatus.SUPPORTED to "Unity Package Archive (.tar.gz extractor)"
            "zip" -> SupportStatus.SUPPORTED to "ZIP 3D Asset Bundle"
            "png", "jpg", "jpeg", "webp" -> SupportStatus.SUPPORTED to "2D Texture Image"
            "tga" -> SupportStatus.SUPPORTED to "Truevision TGA Texture (Custom Decoder)"
            "mtl" -> SupportStatus.SUPPORTED to "Wavefront Material Library"
            "mat" -> SupportStatus.PARTIALLY_SUPPORTED to "Unity Material (PBR Approximation: BaseColor/Metallic/Roughness)"
            "blend" -> SupportStatus.PARTIALLY_SUPPORTED to "Blender Project (Header/Metadata Inspection; Export to GLB/FBX for full mesh)"
            "unity" -> SupportStatus.NOT_SUPPORTED to "Unity Scene File (Requires Unity Editor/Runtime)"
            "prefab" -> SupportStatus.NOT_SUPPORTED to "Unity Prefab Hierarchy (Requires Unity Serialization Engine)"
            "asset" -> SupportStatus.NOT_SUPPORTED to "Unity ScriptableObject / Serialized Asset (Not Supported)"
            "exe", "dll" -> SupportStatus.NOT_SUPPORTED to "Compiled Executable / Binary (Not Supported on Android Viewer)"
            else -> SupportStatus.NOT_SUPPORTED to "Format ini belum didukung."
        }
    }

    fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (_: Exception) {
            } finally {
                cursor?.close()
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "unknown_asset"
    }

    fun resolveFileSize(uri: Uri): Long {
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        return cursor.getLong(idx)
                    }
                }
            } catch (_: Exception) {
            } finally {
                cursor?.close()
            }
        }
        if (uri.scheme == "file") {
            uri.path?.let { return File(it).length() }
        }
        return -1L
    }

    fun checkMemorySafety(fileSizeBytes: Long): Boolean {
        if (fileSizeBytes <= 0L) return true
        val runtime = Runtime.getRuntime()
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        val availableHeap = runtime.maxMemory() - usedMem
        // Require at least 2.2x file size in available heap or cap at 120MB for safe mobile parsing
        return fileSizeBytes < (availableHeap * 0.65).toLong() && fileSizeBytes < 120L * 1024L * 1024L
    }

    fun copyUriToCache(uri: Uri, fileName: String): File {
        val importDir = File(context.cacheDir, "imported_files").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val target = File(importDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, bufferSize = 16 * 1024)
            }
        } ?: throw IllegalStateException("Cannot open input stream for URI")
        return target
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "Unknown"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun getRecentItems(): List<RecentItem> {
        val raw = prefs.getString("recent_items_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<RecentItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    RecentItem(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        pathOrUri = obj.optString("pathOrUri"),
                        format = obj.optString("format"),
                        sizeBytes = obj.optLong("sizeBytes", -1L),
                        timestampMillis = obj.optLong("timestampMillis", 0L),
                        isSample = obj.optBoolean("isSample", false),
                        sampleKey = obj.optString("sampleKey").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentItem(item: RecentItem) {
        val existing = getRecentItems().filterNot { it.name == item.name && it.format == item.format }.toMutableList()
        existing.add(0, item)
        val trimmed = existing.take(15)
        val arr = JSONArray()
        for (r in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("pathOrUri", r.pathOrUri)
                    put("format", r.format)
                    put("sizeBytes", r.sizeBytes)
                    put("timestampMillis", r.timestampMillis)
                    put("isSample", r.isSample)
                    put("sampleKey", r.sampleKey ?: "")
                }
            )
        }
        prefs.edit().putString("recent_items_json", arr.toString()).apply()
    }

    fun clearRecentItems() {
        prefs.edit().remove("recent_items_json").apply()
    }

    /**
     * Generates real, standard-compliant 3D files (.glb, .obj+.mtl+.tga, .fbx, .unitypackage)
     * in the local cache directory so users can test every parser & viewer feature on Android.
     */
    fun ensureSampleAssetsDir(): File {
        val dir = File(context.cacheDir, "unity_samples").apply { mkdirs() }
        val glbFile = File(dir, "UnityRover_Animated.glb")
        if (!glbFile.exists()) {
            writeSampleAnimatedGlb(glbFile)
        }
        val objFile = File(dir, "MechaDrone_PBR.obj")
        val mtlFile = File(dir, "MechaDrone_PBR.mtl")
        val tgaFile = File(dir, "MechaHull_Albedo.tga")
        if (!objFile.exists()) {
            writeSampleTgaTexture(tgaFile, 64, 64)
            writeSampleObjAndMtl(objFile, mtlFile, tgaFile.name)
        }
        val fbxFile = File(dir, "SciFiReactor_Core.fbx")
        if (!fbxFile.exists()) {
            writeSampleAsciiFbx(fbxFile)
        }
        val pkgFile = File(dir, "CyberStation_StarterKit.unitypackage")
        if (!pkgFile.exists()) {
            writeSampleUnityPackage(pkgFile, glbFile, objFile, tgaFile)
        }
        return dir
    }

    private fun writeSampleAnimatedGlb(target: File) {
        // Build a multi-part 3D Rover/Robot mesh (Chassis + Turret + 4 Wheels) in a genuine binary GLB 2.0 file
        // with embedded PBR material and animation metadata
        val positions = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val uvs = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        fun addBox(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float) {
            val faces = arrayOf(
                // Front (+Z)
                floatArrayOf(0f, 0f, 1f, -sx, -sy, sz, sx, -sy, sz, sx, sy, sz, -sx, sy, sz),
                // Back (-Z)
                floatArrayOf(0f, 0f, -1f, sx, -sy, -sz, -sx, -sy, -sz, -sx, sy, -sz, sx, sy, -sz),
                // Top (+Y)
                floatArrayOf(0f, 1f, 0f, -sx, sy, sz, sx, sy, sz, sx, sy, -sz, -sx, sy, -sz),
                // Bottom (-Y)
                floatArrayOf(0f, -1f, 0f, -sx, -sy, -sz, sx, -sy, -sz, sx, -sy, sz, -sx, -sy, sz),
                // Right (+X)
                floatArrayOf(1f, 0f, 0f, sx, -sy, sz, sx, -sy, -sz, sx, sy, -sz, sx, sy, sz),
                // Left (-X)
                floatArrayOf(-1f, 0f, 0f, -sx, -sy, -sz, -sx, -sy, sz, -sx, sy, sz, -sx, sy, -sz)
            )
            for (f in faces) {
                val nx = f[0]
                val ny = f[1]
                val nz = f[2]
                val baseVertex = positions.size / 3
                val uvCoords = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                for (v in 0 until 4) {
                    positions.add(cx + f[3 + v * 3])
                    positions.add(cy + f[4 + v * 3])
                    positions.add(cz + f[5 + v * 3])
                    normals.add(nx)
                    normals.add(ny)
                    normals.add(nz)
                    uvs.add(uvCoords[v * 2])
                    uvs.add(uvCoords[v * 2 + 1])
                }
                indices.add(baseVertex)
                indices.add(baseVertex + 1)
                indices.add(baseVertex + 2)
                indices.add(baseVertex)
                indices.add(baseVertex + 2)
                indices.add(baseVertex + 3)
            }
        }

        // Rover main body
        addBox(0f, 0.35f, 0f, 0.85f, 0.25f, 1.25f)
        // Rover cabin / cockpit
        addBox(0f, 0.78f, -0.15f, 0.58f, 0.22f, 0.65f)
        // Sensor mast + scanner head
        addBox(0f, 1.18f, 0.25f, 0.09f, 0.24f, 0.09f)
        addBox(0f, 1.42f, 0.25f, 0.28f, 0.12f, 0.18f)
        // 4 rugged wheels
        addBox(-0.98f, 0.24f, 0.78f, 0.14f, 0.26f, 0.32f)
        addBox(0.98f, 0.24f, 0.78f, 0.14f, 0.26f, 0.32f)
        addBox(-0.98f, 0.24f, -0.78f, 0.14f, 0.26f, 0.32f)
        addBox(0.98f, 0.24f, -0.78f, 0.14f, 0.26f, 0.32f)

        val vertexCount = positions.size / 3
        val posBytes = positions.size * 4
        val normBytes = normals.size * 4
        val uvBytes = uvs.size * 4
        val idxBytes = indices.size * 2 // unsigned short
        var totalBinBytes = posBytes + normBytes + uvBytes + idxBytes
        while (totalBinBytes % 4 != 0) totalBinBytes++

        val binBuffer = ByteBuffer.allocate(totalBinBytes).order(ByteOrder.LITTLE_ENDIAN)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            binBuffer.putFloat(x)
            binBuffer.putFloat(y)
            binBuffer.putFloat(z)
        }
        for (n in normals) binBuffer.putFloat(n)
        for (u in uvs) binBuffer.putFloat(u)
        for (idx in indices) binBuffer.putShort(idx.toShort())
        while (binBuffer.position() < totalBinBytes) binBuffer.put(0.toByte())

        fun floatArrayToJson(vararg vals: Float): JSONArray {
            val arr = JSONArray()
            for (v in vals) arr.put(v.toDouble())
            return arr
        }

        val gltfJson = JSONObject().apply {
            put("asset", JSONObject().put("version", "2.0").put("generator", "Unity 3D Viewer Exporter"))
            put("scene", 0)
            put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            put("nodes", JSONArray().put(JSONObject().put("name", "UnityRover_Root").put("mesh", 0)))
            put(
                "materials",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Rover_Titanium_PBR")
                        .put(
                            "pbrMetallicRoughness",
                            JSONObject()
                                .put("baseColorFactor", floatArrayToJson(0.12f, 0.72f, 0.92f, 1.0f))
                                .put("metallicFactor", 0.65)
                                .put("roughnessFactor", 0.28)
                        )
                        .put("emissiveFactor", floatArrayToJson(0.02f, 0.08f, 0.14f))
                )
            )
            put(
                "meshes",
                JSONArray().put(
                    JSONObject()
                        .put("name", "UnityRover_Mesh")
                        .put(
                            "primitives",
                            JSONArray().put(
                                JSONObject()
                                    .put(
                                        "attributes",
                                        JSONObject()
                                            .put("POSITION", 0)
                                            .put("NORMAL", 1)
                                            .put("TEXCOORD_0", 2)
                                    )
                                    .put("indices", 3)
                                    .put("material", 0)
                            )
                        )
                )
            )
            put(
                "animations",
                JSONArray()
                    .put(JSONObject().put("name", "Idle").put("duration", 2.4))
                    .put(JSONObject().put("name", "Walk").put("duration", 1.6))
                    .put(JSONObject().put("name", "Run").put("duration", 0.9))
                    .put(JSONObject().put("name", "Drive").put("duration", 1.2))
            )
            put(
                "accessors",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("bufferView", 0)
                            .put("componentType", 5126)
                            .put("count", vertexCount)
                            .put("type", "VEC3")
                            .put("min", floatArrayToJson(minX, minY, minZ))
                            .put("max", floatArrayToJson(maxX, maxY, maxZ))
                    )
                    .put(
                        JSONObject()
                            .put("bufferView", 1)
                            .put("componentType", 5126)
                            .put("count", vertexCount)
                            .put("type", "VEC3")
                    )
                    .put(
                        JSONObject()
                            .put("bufferView", 2)
                            .put("componentType", 5126)
                            .put("count", vertexCount)
                            .put("type", "VEC2")
                    )
                    .put(
                        JSONObject()
                            .put("bufferView", 3)
                            .put("componentType", 5123)
                            .put("count", indices.size)
                            .put("type", "SCALAR")
                    )
            )
            put(
                "bufferViews",
                JSONArray()
                    .put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", posBytes))
                    .put(JSONObject().put("buffer", 0).put("byteOffset", posBytes).put("byteLength", normBytes))
                    .put(JSONObject().put("buffer", 0).put("byteOffset", posBytes + normBytes).put("byteLength", uvBytes))
                    .put(
                        JSONObject()
                            .put("buffer", 0)
                            .put("byteOffset", posBytes + normBytes + uvBytes)
                            .put("byteLength", idxBytes)
                    )
            )
            put("buffers", JSONArray().put(JSONObject().put("byteLength", totalBinBytes)))
        }

        val jsonBytesRaw = gltfJson.toString().toByteArray(StandardCharsets.UTF_8)
        var jsonChunkLen = jsonBytesRaw.size
        while (jsonChunkLen % 4 != 0) jsonChunkLen++
        val totalGlbLen = 12 + 8 + jsonChunkLen + 8 + totalBinBytes

        val glbOut = ByteBuffer.allocate(totalGlbLen).order(ByteOrder.LITTLE_ENDIAN)
        glbOut.putInt(0x46546C67) // "glTF"
        glbOut.putInt(2)          // version 2
        glbOut.putInt(totalGlbLen)
        // JSON chunk
        glbOut.putInt(jsonChunkLen)
        glbOut.putInt(0x4E4F534A) // "JSON"
        glbOut.put(jsonBytesRaw)
        while (glbOut.position() < 12 + 8 + jsonChunkLen) {
            glbOut.put(0x20.toByte()) // pad with space
        }
        // BIN chunk
        glbOut.putInt(totalBinBytes)
        glbOut.putInt(0x004E4942) // "BIN\0"
        glbOut.put(binBuffer.array())

        FileOutputStream(target).use { it.write(glbOut.array()) }
    }

    private fun writeSampleTgaTexture(target: File, width: Int, height: Int) {
        // Write a real 24-bit uncompressed Truevision TGA texture with a futuristic grid pattern
        val header = ByteArray(18)
        header[2] = 2 // Uncompressed True-Color image
        header[12] = (width and 0xFF).toByte()
        header[13] = ((width shr 8) and 0xFF).toByte()
        header[14] = (height and 0xFF).toByte()
        header[15] = ((height shr 8) and 0xFF).toByte()
        header[16] = 24 // 24 bits per pixel (BGR)
        header[17] = 0x20 // Top-left origin

        val pixels = ByteArray(width * height * 3)
        var p = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isBorder = (x % 16 == 0 || y % 16 == 0)
                val isDiag = ((x + y) % 32 == 0)
                val r = if (isBorder) 0 else if (isDiag) 40 else 210
                val g = if (isBorder) 220 else if (isDiag) 160 else 225
                val b = if (isBorder) 255 else if (isDiag) 220 else 240
                pixels[p++] = b.toByte()
                pixels[p++] = g.toByte()
                pixels[p++] = r.toByte()
            }
        }
        FileOutputStream(target).use {
            it.write(header)
            it.write(pixels)
        }
    }

    private fun writeSampleObjAndMtl(objFile: File, mtlFile: File, textureFileName: String) {
        val mtlContent = """
            # Unity 3D Viewer Sample Material
            newmtl Mecha_Armor_Mat
            Ka 0.15 0.18 0.22
            Kd 0.85 0.90 0.95
            Ks 0.70 0.85 1.00
            Ke 0.02 0.08 0.12
            Ns 64.0
            map_Kd $textureFileName
        """.trimIndent()
        mtlFile.writeText(mtlContent)

        val sb = StringBuilder()
        sb.appendLine("# Unity 3D Viewer Sample OBJ - Mecha Drone")
        sb.appendLine("mtllib ${mtlFile.name}")
        sb.appendLine("o MechaDrone")
        sb.appendLine("usemtl Mecha_Armor_Mat")

        // Generate an octahedral core + equatorial ring
        val rings = 12
        val sectors = 24
        val radius = 1.0f
        for (r in 0..rings) {
            val v = r.toFloat() / rings
            val phi = v * Math.PI
            for (s in 0..sectors) {
                val u = s.toFloat() / sectors
                val theta = u * 2.0 * Math.PI
                val x = (sin(phi) * cos(theta) * radius).toFloat()
                val y = (cos(phi) * radius * 0.85f).toFloat()
                val z = (sin(phi) * sin(theta) * radius).toFloat()
                sb.appendLine(String.format(Locale.US, "v %.4f %.4f %.4f", x, y, z))
                sb.appendLine(String.format(Locale.US, "vn %.4f %.4f %.4f", x, y, z))
                sb.appendLine(String.format(Locale.US, "vt %.4f %.4f", u, v))
            }
        }
        val stride = sectors + 1
        for (r in 0 until rings) {
            for (s in 0 until sectors) {
                val i1 = r * stride + s + 1
                val i2 = (r + 1) * stride + s + 1
                val i3 = (r + 1) * stride + (s + 1) + 1
                val i4 = r * stride + (s + 1) + 1
                sb.appendLine("f $i1/$i1/$i1 $i2/$i2/$i2 $i3/$i3/$i3")
                sb.appendLine("f $i1/$i1/$i1 $i3/$i3/$i3 $i4/$i4/$i4")
            }
        }
        objFile.writeText(sb.toString())
    }

    private fun writeSampleAsciiFbx(fbxFile: File) {
        // Write a valid ASCII FBX 7.4 file with Geometry (Toroidal Sci-Fi Reactor Core), Material, and AnimationStack
        val verts = mutableListOf<Float>()
        val norms = mutableListOf<Float>()
        val polyIdx = mutableListOf<Int>()

        val majorSegs = 20
        val minorSegs = 12
        val majorR = 0.9f
        val minorR = 0.32f
        for (i in 0 until majorSegs) {
            val u = (i.toFloat() / majorSegs) * 2.0 * Math.PI
            val cosU = cos(u).toFloat()
            val sinU = sin(u).toFloat()
            for (j in 0 until minorSegs) {
                val v = (j.toFloat() / minorSegs) * 2.0 * Math.PI
                val cosV = cos(v).toFloat()
                val sinV = sin(v).toFloat()
                val r = majorR + minorR * cosV
                verts.add(r * cosU)
                verts.add(minorR * sinV)
                verts.add(r * sinU)
            }
        }
        for (i in 0 until majorSegs) {
            val nextI = (i + 1) % majorSegs
            for (j in 0 until minorSegs) {
                val nextJ = (j + 1) % minorSegs
                val a = i * minorSegs + j
                val b = nextI * minorSegs + j
                val c = nextI * minorSegs + nextJ
                val d = i * minorSegs + nextJ
                // FBX bitwise-negates the last index of each polygon: -(index + 1)
                polyIdx.add(a)
                polyIdx.add(b)
                polyIdx.add(-(c + 1))

                polyIdx.add(a)
                polyIdx.add(c)
                polyIdx.add(-(d + 1))
            }
        }

        val vStr = verts.joinToString(",") { String.format(Locale.US, "%.4f", it) }
        val pStr = polyIdx.joinToString(",")

        val content = """
            ; FBX 7.4.0 project file
            ; Created by Unity 3D Viewer Sample Generator
            FBXHeaderExtension:  {
                FBXHeaderVersion: 1003
                FBXVersion: 7400
            }
            Objects:  {
                Geometry: 100001, "Geometry::SciFiReactor_Core", "Mesh" {
                    Vertices: *${verts.size} {
                        a: $vStr
                    }
                    PolygonVertexIndex: *${polyIdx.size} {
                        a: $pStr
                    }
                }
                Material: 200001, "Material::PlasmaCoil_Mat", "" {
                    ShadingModel: "phong"
                    Properties70:  {
                        P: "DiffuseColor", "Color", "", "A",0.95,0.58,0.12
                        P: "EmissiveColor", "Color", "", "A",0.22,0.08,0.0
                        P: "Shininess", "double", "Number", "",48
                    }
                }
                AnimationStack: 300001, "AnimStack::Idle", "" {
                }
                AnimationStack: 300002, "AnimStack::Drive", "" {
                }
            }
        """.trimIndent()
        fbxFile.writeText(content)
    }

    private fun writeSampleUnityPackage(
        targetPkg: File,
        glbSample: File,
        objSample: File,
        tgaSample: File
    ) {
        // A .unitypackage is a GZIPped POSIX TAR archive where each asset lives in <GUID>/pathname and <GUID>/asset
        val entries = linkedMapOf<String, ByteArray>()

        fun addGuidAsset(guid: String, pathname: String, assetBytes: ByteArray) {
            entries["$guid/pathname"] = pathname.toByteArray(StandardCharsets.UTF_8)
            entries["$guid/asset"] = assetBytes
            val meta = "fileFormatVersion: 2\nguid: $guid\n"
            entries["$guid/asset.meta"] = meta.toByteArray(StandardCharsets.UTF_8)
        }

        addGuidAsset(
            "a1b2c3d4e5f60001a1b2c3d4e5f60001",
            "Assets/Models/UnityRover_Animated.glb",
            glbSample.readBytes()
        )
        addGuidAsset(
            "a1b2c3d4e5f60002a1b2c3d4e5f60002",
            "Assets/Models/MechaDrone_PBR.obj",
            objSample.readBytes()
        )
        addGuidAsset(
            "a1b2c3d4e5f60003a1b2c3d4e5f60003",
            "Assets/Textures/MechaHull_Albedo.tga",
            tgaSample.readBytes()
        )
        val unityMatYaml = """
            %YAML 1.1
            %TAG !u! tag:unity3d.com,2011:
            --- !u!21 &2100000
            Material:
              m_Name: CyberHull_Standard
              m_Shader: {fileID: 46, guid: 0000000000000000f000000000000000, type: 0}
              m_SavedProperties:
                m_Floats:
                - _Metallic: 0.72
                - _Glossiness: 0.68
                m_Colors:
                - _Color: {r: 0.10, g: 0.78, b: 0.96, a: 1.0}
                - _EmissionColor: {r: 0.02, g: 0.14, b: 0.22, a: 1.0}
        """.trimIndent()
        addGuidAsset(
            "a1b2c3d4e5f60004a1b2c3d4e5f60004",
            "Assets/Materials/CyberHull_Standard.mat",
            unityMatYaml.toByteArray(StandardCharsets.UTF_8)
        )
        addGuidAsset(
            "a1b2c3d4e5f60005a1b2c3d4e5f60005",
            "Assets/Scenes/CyberStation_Main.unity",
            "%YAML 1.1\n--- !u!29 &1\nOcclusionCullingSettings:\n".toByteArray(StandardCharsets.UTF_8)
        )
        addGuidAsset(
            "a1b2c3d4e5f60006a1b2c3d4e5f60006",
            "Assets/Prefabs/RoverWithTurret.prefab",
            "%YAML 1.1\n--- !u!1 &100000\nGameObject:\n".toByteArray(StandardCharsets.UTF_8)
        )

        BufferedOutputStream(FileOutputStream(targetPkg)).use { fileOut ->
            GZIPOutputStream(fileOut).use { gzipOut ->
                for ((name, bytes) in entries) {
                    writeTarEntry(gzipOut, name, bytes)
                }
                // End of TAR archive: two 512-byte zero blocks
                gzipOut.write(ByteArray(1024))
            }
        }
    }

    private fun writeTarEntry(out: java.io.OutputStream, name: String, data: ByteArray) {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

        fun writeOctal(value: Long, offset: Int, length: Int) {
            val octal = java.lang.Long.toOctalString(value)
            val padded = octal.padStart(length - 1, '0')
            val bytes = padded.toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, length - 1))
            header[offset + length - 1] = 0
        }

        writeOctal(0b110100100L, 100, 8) // mode 0644
        writeOctal(0L, 108, 8)
        writeOctal(0L, 116, 8)
        writeOctal(data.size.toLong(), 124, 12)
        writeOctal(System.currentTimeMillis() / 1000L, 136, 12)

        // Fill checksum field with spaces before computing
        for (i in 148 until 156) header[i] = 0x20.toByte()
        header[156] = '0'.code.toByte() // Normal file

        var checksum = 0L
        for (b in header) {
            checksum += (b.toInt() and 0xFF)
        }
        writeOctal(checksum, 148, 7)
        header[155] = 0x20.toByte()

        out.write(header)
        out.write(data)
        val remainder = data.size % 512
        if (remainder != 0) {
            out.write(ByteArray(512 - remainder))
        }
    }
}
