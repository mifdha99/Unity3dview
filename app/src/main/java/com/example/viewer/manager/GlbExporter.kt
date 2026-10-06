package com.example.viewer.manager

import android.content.Context
import android.net.Uri
import com.example.viewer.model.Model3DData
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports any imported Model3DData (from .obj, .fbx, .dae, .3ds, .unitypackage, or .glb)
 * into a standard Binary glTF 2.0 (.glb) file.
 */
class GlbExporter(private val context: Context) {

    fun exportModelToUri(model: Model3DData, targetUri: Uri): Boolean {
        return try {
            val glbBytes = serializeToGlbBytes(model)
            context.contentResolver.openOutputStream(targetUri)?.use { out: OutputStream ->
                out.write(glbBytes)
                out.flush()
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun serializeToGlbBytes(model: Model3DData): ByteArray {
        // Merge all submeshes into a single binary buffer for universal GLB 2.0 compatibility
        val allPos = mutableListOf<Float>()
        val allNorm = mutableListOf<Float>()
        val allUv = mutableListOf<Float>()
        val allIndices = mutableListOf<Int>()

        for (sub in model.subMeshes) {
            val baseVertex = allPos.size / 3
            val vCount = sub.positions.size / 3
            for (i in 0 until vCount) {
                allPos.add(sub.positions[i * 3])
                allPos.add(sub.positions[i * 3 + 1])
                allPos.add(sub.positions[i * 3 + 2])

                allNorm.add(sub.normals.getOrElse(i * 3) { 0f })
                allNorm.add(sub.normals.getOrElse(i * 3 + 1) { 1f })
                allNorm.add(sub.normals.getOrElse(i * 3 + 2) { 0f })

                allUv.add(sub.uvs.getOrElse(i * 2) { 0f })
                allUv.add(sub.uvs.getOrElse(i * 2 + 1) { 0f })
            }
            for (idx in sub.indices) {
                allIndices.add(baseVertex + idx)
            }
        }

        val vertexCount = allPos.size / 3
        val posBytes = allPos.size * 4
        val normBytes = allNorm.size * 4
        val uvBytes = allUv.size * 4
        val idxBytes = allIndices.size * 4 // 32-bit UNSIGNED_INT indices (5125)
        var totalBinBytes = posBytes + normBytes + uvBytes + idxBytes
        while (totalBinBytes % 4 != 0) totalBinBytes++

        val binBuffer = ByteBuffer.allocate(totalBinBytes).order(ByteOrder.LITTLE_ENDIAN)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (i in 0 until vertexCount) {
            val x = allPos[i * 3]
            val y = allPos[i * 3 + 1]
            val z = allPos[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            binBuffer.putFloat(x)
            binBuffer.putFloat(y)
            binBuffer.putFloat(z)
        }
        for (n in allNorm) binBuffer.putFloat(n)
        for (u in allUv) binBuffer.putFloat(u)
        for (idx in allIndices) binBuffer.putInt(idx)
        while (binBuffer.position() < totalBinBytes) binBuffer.put(0.toByte())

        fun floatArrayToJson(vals: FloatArray): JSONArray {
            val arr = JSONArray()
            for (v in vals) arr.put(v.toDouble())
            return arr
        }

        val mat = model.materials.firstOrNull()
        val baseColor = mat?.baseColor ?: floatArrayOf(0.8f, 0.82f, 0.86f, 1.0f)
        val metallic = mat?.metallic ?: 0.2f
        val roughness = mat?.roughness ?: 0.45f

        val gltfJson = JSONObject().apply {
            put("asset", JSONObject().put("version", "2.0").put("generator", "Unity 3D Viewer Android GLB Exporter"))
            put("scene", 0)
            put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            put("nodes", JSONArray().put(JSONObject().put("name", model.name).put("mesh", 0)))
            put(
                "materials",
                JSONArray().put(
                    JSONObject()
                        .put("name", mat?.name ?: "Exported_Material")
                        .put(
                            "pbrMetallicRoughness",
                            JSONObject()
                                .put("baseColorFactor", floatArrayToJson(baseColor))
                                .put("metallicFactor", metallic.toDouble())
                                .put("roughnessFactor", roughness.toDouble())
                        )
                )
            )
            put(
                "meshes",
                JSONArray().put(
                    JSONObject()
                        .put("name", model.name)
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
                "accessors",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("bufferView", 0)
                            .put("componentType", 5126)
                            .put("count", vertexCount)
                            .put("type", "VEC3")
                            .put("min", floatArrayToJson(floatArrayOf(minX, minY, minZ)))
                            .put("max", floatArrayToJson(floatArrayOf(maxX, maxY, maxZ)))
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
                            .put("componentType", 5125)
                            .put("count", allIndices.size)
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
        glbOut.putInt(0x46546C67)
        glbOut.putInt(2)
        glbOut.putInt(totalGlbLen)

        glbOut.putInt(jsonChunkLen)
        glbOut.putInt(0x4E4F534A)
        glbOut.put(jsonBytesRaw)
        while (glbOut.position() < 12 + 8 + jsonChunkLen) {
            glbOut.put(0x20.toByte())
        }

        glbOut.putInt(totalBinBytes)
        glbOut.putInt(0x004E4942)
        glbOut.put(binBuffer.array())

        return glbOut.array()
    }
}
