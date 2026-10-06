package com.example.viewer.manager

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.example.viewer.model.BackgroundPreset
import com.example.viewer.model.LodLevel
import com.example.viewer.model.MaterialData
import com.example.viewer.model.Model3DData
import com.example.viewer.model.RenderMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SceneManager(
    val cameraController: CameraController,
    val animationManager: AnimationManager,
    private val textureManager: TextureManager
) : GLSurfaceView.Renderer {

    @Volatile
    var showGrid: Boolean = true

    @Volatile
    var enableLighting: Boolean = true

    @Volatile
    var lightAzimuthDeg: Float = 45f

    @Volatile
    var renderMode: RenderMode = RenderMode.SOLID

    @Volatile
    var backgroundPreset: BackgroundPreset = BackgroundPreset.STUDIO_DARK

    @Volatile
    var lodLevel: LodLevel = LodLevel.HIGH

    @Volatile
    private var pendingModel: Model3DData? = null

    @Volatile
    private var modelNeedsUpload: Boolean = false

    @Volatile
    private var overrideMaterial: MaterialData? = null

    private var meshProgram = 0
    private var lineProgram = 0
    private var glTextureId = 0
    private var hasBoundTexture = false

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    private var gridVertexBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var axesVertexBuffer: FloatBuffer? = null
    private var axesColorBuffer: FloatBuffer? = null

    private class GpuSubMesh(
        val vertexBuffer: FloatBuffer,
        val normalBuffer: FloatBuffer,
        val uvBuffer: FloatBuffer,
        val solidIndicesFull: ShortBuffer,
        val solidCountFull: Int,
        val solidIndicesMed: ShortBuffer,
        val solidCountMed: Int,
        val solidIndicesLow: ShortBuffer,
        val solidCountLow: Int,
        val wireIndices: ShortBuffer,
        val wireCount: Int,
        val materialIndex: Int
    )

    private val gpuSubMeshes = mutableListOf<GpuSubMesh>()
    private var activeMaterials: List<MaterialData> = emptyList()

    fun setModel(model: Model3DData?) {
        pendingModel = model
        overrideMaterial = null
        modelNeedsUpload = true
    }

    fun updateActiveMaterial(material: MaterialData) {
        overrideMaterial = material
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)

        buildGridAndAxesBuffers()
        modelNeedsUpload = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val bg = backgroundPreset.topColor
        GLES20.glClearColor(bg[0], bg[1], bg[2], 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (modelNeedsUpload) {
            modelNeedsUpload = false
            uploadModelToGpu(pendingModel)
        }

        cameraController.computeViewMatrix(viewMatrix)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        if (showGrid) {
            drawGridAndAxes()
        }

        if (gpuSubMeshes.isNotEmpty()) {
            drawActiveModel()
        }
    }

    private fun uploadModelToGpu(model: Model3DData?) {
        gpuSubMeshes.clear()
        if (glTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(glTextureId), 0)
            glTextureId = 0
        }
        hasBoundTexture = false

        if (model == null) return
        activeMaterials = model.materials

        // Upload primary texture if available
        val firstBitmap: Bitmap? = model.textures.firstOrNull { it.bitmap != null }?.bitmap
        if (firstBitmap != null && !firstBitmap.isRecycled) {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            glTextureId = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, firstBitmap, 0)
            hasBoundTexture = true
        }

        for (sub in model.subMeshes) {
            val vCount = sub.positions.size / 3
            if (vCount == 0 || sub.indices.isEmpty()) continue

            val vBuf = allocateFloatBuffer(sub.positions)
            val nBuf = allocateFloatBuffer(
                if (sub.normals.size == sub.positions.size) sub.normals else FloatArray(sub.positions.size) { if (it % 3 == 1) 1f else 0f }
            )
            val uvBuf = allocateFloatBuffer(
                if (sub.uvs.size >= vCount * 2) sub.uvs else FloatArray(vCount * 2)
            )

            // Cap indices to 16-bit unsigned range per draw chunk for universal ES 2.0 support
            val safeTriCount = (sub.indices.size / 3).coerceAtMost(21000)
            val fullIdx = ShortArray(safeTriCount * 3)
            for (i in 0 until safeTriCount * 3) {
                fullIdx[i] = sub.indices[i].coerceIn(0, 65534).toShort()
            }

            // Build LOD 1 (50%) and LOD 2 (25%) decimated triangle lists
            val medStep = 2
            val medTriCount = (safeTriCount / medStep).coerceAtLeast(1)
            val medIdx = ShortArray(medTriCount * 3)
            for (t in 0 until medTriCount) {
                val srcT = (t * medStep).coerceAtMost(safeTriCount - 1)
                medIdx[t * 3] = fullIdx[srcT * 3]
                medIdx[t * 3 + 1] = fullIdx[srcT * 3 + 1]
                medIdx[t * 3 + 2] = fullIdx[srcT * 3 + 2]
            }

            val lowStep = 4
            val lowTriCount = (safeTriCount / lowStep).coerceAtLeast(1)
            val lowIdx = ShortArray(lowTriCount * 3)
            for (t in 0 until lowTriCount) {
                val srcT = (t * lowStep).coerceAtMost(safeTriCount - 1)
                lowIdx[t * 3] = fullIdx[srcT * 3]
                lowIdx[t * 3 + 1] = fullIdx[srcT * 3 + 1]
                lowIdx[t * 3 + 2] = fullIdx[srcT * 3 + 2]
            }

            // Build wireframe line indices (3 edges per triangle)
            val wireTriLimit = safeTriCount.coerceAtMost(10000)
            val wireArr = ShortArray(wireTriLimit * 6)
            for (t in 0 until wireTriLimit) {
                val a = fullIdx[t * 3]
                val b = fullIdx[t * 3 + 1]
                val c = fullIdx[t * 3 + 2]
                wireArr[t * 6] = a; wireArr[t * 6 + 1] = b
                wireArr[t * 6 + 2] = b; wireArr[t * 6 + 3] = c
                wireArr[t * 6 + 4] = c; wireArr[t * 6 + 5] = a
            }

            gpuSubMeshes.add(
                GpuSubMesh(
                    vertexBuffer = vBuf,
                    normalBuffer = nBuf,
                    uvBuffer = uvBuf,
                    solidIndicesFull = allocateShortBuffer(fullIdx),
                    solidCountFull = fullIdx.size,
                    solidIndicesMed = allocateShortBuffer(medIdx),
                    solidCountMed = medIdx.size,
                    solidIndicesLow = allocateShortBuffer(lowIdx),
                    solidCountLow = lowIdx.size,
                    wireIndices = allocateShortBuffer(wireArr),
                    wireCount = wireArr.size,
                    materialIndex = sub.materialIndex
                )
            )
        }
    }

    private fun drawActiveModel() {
        val anim = animationManager.evaluateCurrentTransform()
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, anim.translationX, anim.translationY, anim.translationZ)
        Matrix.rotateM(modelMatrix, 0, anim.rotationYDeg, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, anim.rotationXDeg, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, anim.rotationZDeg, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, anim.scaleX, anim.scaleY, anim.scaleZ)

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        val camPos = cameraController.computeCameraPositionWorld()
        val rad = Math.toRadians(lightAzimuthDeg.toDouble())
        val lx = kotlin.math.cos(rad).toFloat() * 0.7f
        val ly = 0.85f
        val lz = kotlin.math.sin(rad).toFloat() * 0.7f

        GLES20.glUseProgram(meshProgram)
        val posLoc = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val normLoc = GLES20.glGetAttribLocation(meshProgram, "aNormal")
        val uvLoc = GLES20.glGetAttribLocation(meshProgram, "aTexCoord")

        val mvpLoc = GLES20.glGetUniformLocation(meshProgram, "uMVPMatrix")
        val modelLoc = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")
        val baseColorLoc = GLES20.glGetUniformLocation(meshProgram, "uBaseColor")
        val emissionLoc = GLES20.glGetUniformLocation(meshProgram, "uEmission")
        val pbrParamsLoc = GLES20.glGetUniformLocation(meshProgram, "uPbrParams")
        val lightDirLoc = GLES20.glGetUniformLocation(meshProgram, "uLightDir")
        val viewPosLoc = GLES20.glGetUniformLocation(meshProgram, "uViewPos")
        val useTexLoc = GLES20.glGetUniformLocation(meshProgram, "uUseTexture")
        val texSamplerLoc = GLES20.glGetUniformLocation(meshProgram, "uTexture")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform3f(lightDirLoc, lx, ly, lz)
        GLES20.glUniform3f(viewPosLoc, camPos[0], camPos[1], camPos[2])

        if (hasBoundTexture && glTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
            GLES20.glUniform1i(texSamplerLoc, 0)
            GLES20.glUniform1i(useTexLoc, 1)
        } else {
            GLES20.glUniform1i(useTexLoc, 0)
        }

        for (sub in gpuSubMeshes) {
            val mat = overrideMaterial
                ?: activeMaterials.getOrNull(sub.materialIndex)
                ?: activeMaterials.firstOrNull()
                ?: MaterialData("Default")

            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, sub.vertexBuffer)

            GLES20.glEnableVertexAttribArray(normLoc)
            GLES20.glVertexAttribPointer(normLoc, 3, GLES20.GL_FLOAT, false, 0, sub.normalBuffer)

            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, sub.uvBuffer)

            if (renderMode == RenderMode.SOLID || renderMode == RenderMode.SOLID_WIREFRAME) {
                GLES20.glUniform4f(
                    baseColorLoc,
                    mat.baseColor.getOrElse(0) { 0.8f },
                    mat.baseColor.getOrElse(1) { 0.8f },
                    mat.baseColor.getOrElse(2) { 0.8f },
                    mat.baseColor.getOrElse(3) { 1.0f }
                )
                GLES20.glUniform3f(
                    emissionLoc,
                    mat.emission.getOrElse(0) { 0f },
                    mat.emission.getOrElse(1) { 0f },
                    mat.emission.getOrElse(2) { 0f }
                )
                GLES20.glUniform3f(
                    pbrParamsLoc,
                    mat.metallic,
                    mat.roughness,
                    if (enableLighting) 1.0f else 0.0f
                )

                val (idxBuf, idxCount) = when (lodLevel) {
                    LodLevel.HIGH -> sub.solidIndicesFull to sub.solidCountFull
                    LodLevel.MEDIUM -> sub.solidIndicesMed to sub.solidCountMed
                    LodLevel.LOW -> sub.solidIndicesLow to sub.solidCountLow
                }
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, idxBuf)
            }

            if (renderMode == RenderMode.WIREFRAME || renderMode == RenderMode.SOLID_WIREFRAME) {
                GLES20.glUniform1i(useTexLoc, 0)
                if (renderMode == RenderMode.SOLID_WIREFRAME) {
                    GLES20.glUniform4f(baseColorLoc, 0.0f, 0.85f, 1.0f, 0.70f)
                } else {
                    GLES20.glUniform4f(baseColorLoc, 0.0f, 0.88f, 1.0f, 1.0f)
                }
                GLES20.glUniform3f(emissionLoc, 0.0f, 0.3f, 0.4f)
                GLES20.glUniform3f(pbrParamsLoc, 0f, 1f, 0f)
                GLES20.glLineWidth(1.5f)
                GLES20.glDrawElements(GLES20.GL_LINES, sub.wireCount, GLES20.GL_UNSIGNED_SHORT, sub.wireIndices)
                if (hasBoundTexture && glTextureId != 0) {
                    GLES20.glUniform1i(useTexLoc, 1)
                }
            }

            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(normLoc)
            GLES20.glDisableVertexAttribArray(uvLoc)
        }
    }

    private fun buildGridAndAxesBuffers() {
        val gridHalfSize = 5
        val step = 0.5f
        val linesCount = ((gridHalfSize * 2) / step).toInt() + 1
        val gridFloats = FloatArray(linesCount * 4 * 3)
        var p = 0
        for (i in 0 until linesCount) {
            val coord = -gridHalfSize + i * step
            // Line along Z
            gridFloats[p++] = coord; gridFloats[p++] = 0f; gridFloats[p++] = -gridHalfSize.toFloat()
            gridFloats[p++] = coord; gridFloats[p++] = 0f; gridFloats[p++] = gridHalfSize.toFloat()
            // Line along X
            gridFloats[p++] = -gridHalfSize.toFloat(); gridFloats[p++] = 0f; gridFloats[p++] = coord
            gridFloats[p++] = gridHalfSize.toFloat(); gridFloats[p++] = 0f; gridFloats[p++] = coord
        }
        gridVertexCount = gridFloats.size / 3
        gridVertexBuffer = allocateFloatBuffer(gridFloats)

        val axesVerts = floatArrayOf(
            0f, 0.005f, 0f, 1.5f, 0.005f, 0f, // X axis
            0f, 0.005f, 0f, 0f, 1.5f, 0f,     // Y axis
            0f, 0.005f, 0f, 0f, 0.005f, 1.5f  // Z axis
        )
        val axesColors = floatArrayOf(
            0.95f, 0.26f, 0.26f, 1f, 0.95f, 0.26f, 0.26f, 1f,
            0.20f, 0.85f, 0.45f, 1f, 0.20f, 0.85f, 0.45f, 1f,
            0.20f, 0.60f, 1.00f, 1f, 0.20f, 0.60f, 1.00f, 1f
        )
        axesVertexBuffer = allocateFloatBuffer(axesVerts)
        axesColorBuffer = allocateFloatBuffer(axesColors)
    }

    private fun drawGridAndAxes() {
        val gBuf = gridVertexBuffer ?: return
        GLES20.glUseProgram(lineProgram)
        val posLoc = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val mvpLoc = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, vpMatrix, 0)
        val gc = backgroundPreset.gridColor
        GLES20.glUniform4f(colorLoc, gc[0], gc[1], gc[2], gc[3])

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, gBuf)
        GLES20.glLineWidth(1.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)

        // Draw X (Red), Y (Green), Z (Blue) origin gizmo axes
        val axBuf = axesVertexBuffer
        if (axBuf != null) {
            GLES20.glLineWidth(3.0f)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, axBuf)
            GLES20.glUniform4f(colorLoc, 0.95f, 0.30f, 0.30f, 0.95f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
            GLES20.glUniform4f(colorLoc, 0.25f, 0.90f, 0.48f, 0.95f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 2, 2)
            GLES20.glUniform4f(colorLoc, 0.20f, 0.65f, 1.00f, 0.95f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 4, 2)
        }
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun allocateFloatBuffer(arr: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(arr)
                position(0)
            }
    }

    private fun allocateShortBuffer(arr: ShortArray): ShortBuffer {
        return ByteBuffer.allocateDirect(arr.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(arr)
                position(0)
            }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val MESH_VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aTexCoord;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec2 vTexCoord;
            void main() {
                vec4 worldPos = uModelMatrix * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vTexCoord = aTexCoord;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """

        private const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uBaseColor;
            uniform vec3 uEmission;
            uniform vec3 uPbrParams; // x = metallic, y = roughness, z = enableLighting
            uniform vec3 uLightDir;
            uniform vec3 uViewPos;
            uniform int uUseTexture;
            uniform sampler2D uTexture;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec2 vTexCoord;
            void main() {
                vec4 albedo = uBaseColor;
                if (uUseTexture == 1) {
                    vec4 texColor = texture2D(uTexture, vTexCoord);
                    albedo = vec4(albedo.rgb * texColor.rgb, albedo.a * texColor.a);
                }
                if (uPbrParams.z < 0.5) {
                    gl_FragColor = vec4(albedo.rgb + uEmission, albedo.a);
                    return;
                }
                vec3 N = normalize(vNormal);
                vec3 L = normalize(uLightDir);
                vec3 V = normalize(uViewPos - vWorldPos);
                vec3 H = normalize(L + V);

                // Hemisphere sky/ground ambient + directional diffuse + metallic-roughness Blinn-Phong specular
                float hemi = 0.5 * (N.y + 1.0);
                vec3 skyAmbient = vec3(0.28, 0.34, 0.42);
                vec3 groundAmbient = vec3(0.10, 0.12, 0.15);
                vec3 ambient = mix(groundAmbient, skyAmbient, hemi) * albedo.rgb;

                float diff = max(dot(N, L), 0.0);
                float backDiff = max(dot(N, -L), 0.0) * 0.22;

                float roughness = clamp(uPbrParams.y, 0.06, 0.98);
                float metallic = clamp(uPbrParams.x, 0.0, 1.0);
                float shininess = pow(2.0, (1.0 - roughness) * 7.0);
                float spec = pow(max(dot(N, H), 0.0), shininess) * (1.0 - roughness * 0.7);
                vec3 specColor = mix(vec3(0.95), albedo.rgb, metallic);

                vec3 diffuseTerm = albedo.rgb * (1.0 - metallic * 0.55) * (diff + backDiff);
                vec3 finalRgb = ambient + diffuseTerm + specColor * spec * (0.35 + metallic * 0.65) + uEmission;
                gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), albedo.a);
            }
        """

        private const val LINE_VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            attribute vec3 aPosition;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """

        private const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
