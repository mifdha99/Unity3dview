package com.example.viewer.manager

import com.example.viewer.model.MaterialData
import java.io.File
import java.util.Locale

class MaterialManager {

    /**
     * Parses Wavefront .mtl files into MaterialData list.
     */
    fun parseMtlFile(file: File): List<MaterialData> {
        if (!file.exists() || !file.canRead()) return emptyList()
        val materials = mutableListOf<MaterialData>()
        var currentName = "Default_MTL"
        var kd = floatArrayOf(0.8f, 0.82f, 0.86f, 1.0f)
        var ke = floatArrayOf(0f, 0f, 0f)
        var roughness = 0.45f
        var metallic = 0.15f
        var diffuseTex: String? = null
        var normalTex: String? = null
        var hasMaterial = false

        fun flushCurrent() {
            if (hasMaterial) {
                materials.add(
                    MaterialData(
                        name = currentName,
                        baseColor = kd.clone(),
                        metallic = metallic,
                        roughness = roughness,
                        emission = ke.clone(),
                        normalMapName = normalTex,
                        diffuseTextureName = diffuseTex,
                        isUnityApproximation = false,
                        sourceShaderName = "Wavefront MTL"
                    )
                )
            }
        }

        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split(Regex("\\s+"))
            when (parts[0].lowercase(Locale.ROOT)) {
                "newmtl" -> {
                    flushCurrent()
                    currentName = parts.drop(1).joinToString(" ").ifEmpty { "Material_${materials.size + 1}" }
                    kd = floatArrayOf(0.8f, 0.82f, 0.86f, 1.0f)
                    ke = floatArrayOf(0f, 0f, 0f)
                    roughness = 0.45f
                    metallic = 0.15f
                    diffuseTex = null
                    normalTex = null
                    hasMaterial = true
                }
                "kd" -> if (parts.size >= 4) {
                    kd = floatArrayOf(
                        parts[1].toFloatOrNull() ?: 0.8f,
                        parts[2].toFloatOrNull() ?: 0.8f,
                        parts[3].toFloatOrNull() ?: 0.8f,
                        1.0f
                    )
                }
                "ke" -> if (parts.size >= 4) {
                    ke = floatArrayOf(
                        parts[1].toFloatOrNull() ?: 0f,
                        parts[2].toFloatOrNull() ?: 0f,
                        parts[3].toFloatOrNull() ?: 0f
                    )
                }
                "ns" -> if (parts.size >= 2) {
                    val shininess = (parts[1].toFloatOrNull() ?: 32f).coerceIn(1f, 1000f)
                    roughness = (1.0f - (shininess / 250f)).coerceIn(0.08f, 0.95f)
                }
                "pm" -> if (parts.size >= 2) {
                    metallic = (parts[1].toFloatOrNull() ?: 0.15f).coerceIn(0f, 1f)
                }
                "map_kd" -> if (parts.size >= 2) {
                    diffuseTex = parts.last().substringAfterLast('/').substringAfterLast('\\')
                }
                "map_bump", "bump", "norm" -> if (parts.size >= 2) {
                    normalTex = parts.last().substringAfterLast('/').substringAfterLast('\\')
                }
            }
        }
        flushCurrent()
        return materials
    }

    /**
     * Parses a Unity YAML .mat file to build a honest PBR approximation
     * (Base Color, Metallic, Roughness, Normal Map, Emission).
     * Explicitly marks isUnityApproximation = true so UI never claims 100% Unity Shader parity.
     */
    fun parseUnityMatFile(file: File): MaterialData {
        val baseName = file.nameWithoutExtension
        if (!file.exists() || !file.canRead()) {
            return createDefaultMaterial(baseName)
        }
        var matName = baseName
        var r = 0.78f
        var g = 0.82f
        var b = 0.88f
        var a = 1.0f
        var metallic = 0.2f
        var glossiness = 0.55f
        var er = 0f
        var eg = 0f
        var eb = 0f
        var hasNormalMap = false

        try {
            val content = file.readText()
            Regex("""m_Name:\s*([^\r\n]+)""").find(content)?.let {
                matName = it.groupValues[1].trim().ifEmpty { baseName }
            }
            Regex("""_Metallic:\s*([0-9.]+)""").find(content)?.let {
                metallic = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.2f
            }
            Regex("""(?:_Glossiness|_Smoothness):\s*([0-9.]+)""").find(content)?.let {
                glossiness = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.55f
            }
            val colorRegex = Regex("""(?:_Color|_BaseColor):\s*\{r:\s*([0-9.]+),\s*g:\s*([0-9.]+),\s*b:\s*([0-9.]+)(?:,\s*a:\s*([0-9.]+))?""")
            colorRegex.find(content)?.let { m ->
                r = m.groupValues[1].toFloatOrNull() ?: r
                g = m.groupValues[2].toFloatOrNull() ?: g
                b = m.groupValues[3].toFloatOrNull() ?: b
                a = m.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1.0f
            }
            val emissionRegex = Regex("""_EmissionColor:\s*\{r:\s*([0-9.]+),\s*g:\s*([0-9.]+),\s*b:\s*([0-9.]+)""")
            emissionRegex.find(content)?.let { m ->
                er = m.groupValues[1].toFloatOrNull() ?: 0f
                eg = m.groupValues[2].toFloatOrNull() ?: 0f
                eb = m.groupValues[3].toFloatOrNull() ?: 0f
            }
            if (content.contains("_BumpMap")) {
                hasNormalMap = true
            }
        } catch (_: Exception) {
        }

        return MaterialData(
            name = matName,
            baseColor = floatArrayOf(r, g, b, a),
            metallic = metallic,
            roughness = (1.0f - glossiness).coerceIn(0.05f, 0.95f),
            emission = floatArrayOf(er, eg, eb),
            normalMapName = if (hasNormalMap) "Unity _BumpMap Reference" else null,
            isUnityApproximation = true,
            sourceShaderName = "Unity .mat (PBR Approximation)"
        )
    }

    fun createDefaultMaterial(name: String = "Default_Standard"): MaterialData {
        return MaterialData(
            name = name,
            baseColor = floatArrayOf(0.76f, 0.80f, 0.86f, 1.0f),
            metallic = 0.20f,
            roughness = 0.42f,
            emission = floatArrayOf(0f, 0f, 0f),
            isUnityApproximation = false,
            sourceShaderName = "Fallback Standard Material"
        )
    }
}
