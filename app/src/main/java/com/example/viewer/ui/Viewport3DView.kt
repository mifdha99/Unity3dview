package com.example.viewer.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.viewer.manager.CameraController
import com.example.viewer.manager.SceneManager
import com.example.viewer.model.BackgroundPreset
import com.example.viewer.model.LodLevel
import com.example.viewer.model.MaterialData
import com.example.viewer.model.Model3DData
import com.example.viewer.model.RenderMode

private class InteractiveGLSurfaceView(
    context: Context,
    private val sceneManager: SceneManager
) : GLSurfaceView(context) {

    private var prevX0 = 0f
    private var prevY0 = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var prevFingerDist = 1f
    private var activePointerCount = 0

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(sceneManager)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val count = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prevX0 = event.getX(0)
                prevY0 = event.getY(0)
                activePointerCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (count >= 2) {
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    prevMidX = (x0 + x1) * 0.5f
                    prevMidY = (y0 + y1) * 0.5f
                    prevFingerDist = CameraController.fingerDistance(x0, y0, x1, y1)
                    activePointerCount = 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (count == 1 && activePointerCount == 1) {
                    // 1 finger = Rotate model
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - prevX0
                    val dy = y - prevY0
                    sceneManager.cameraController.onSingleFingerRotate(dx, dy)
                    prevX0 = x
                    prevY0 = y
                } else if (count >= 2) {
                    // 2 fingers = Pinch Zoom + Pan
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    val midX = (x0 + x1) * 0.5f
                    val midY = (y0 + y1) * 0.5f
                    val newDist = CameraController.fingerDistance(x0, y0, x1, y1)

                    if (activePointerCount >= 2 && prevFingerDist > 1f) {
                        val panDx = midX - prevMidX
                        val panDy = midY - prevMidY
                        val pinchRatio = (newDist / prevFingerDist).coerceIn(0.75f, 1.35f)
                        sceneManager.cameraController.onTwoFingerGesture(panDx, panDy, pinchRatio)
                    }
                    prevMidX = midX
                    prevMidY = midY
                    prevFingerDist = newDist
                    activePointerCount = 2
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount = (count - 1).coerceAtLeast(0)
                if (activePointerCount == 1) {
                    val remainingIdx = if (event.actionIndex == 0) 1 else 0
                    if (remainingIdx < count) {
                        prevX0 = event.getX(remainingIdx)
                        prevY0 = event.getY(remainingIdx)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
            }
        }
        return true
    }
}

@Composable
fun Viewport3DView(
    sceneManager: SceneManager,
    model: Model3DData?,
    renderMode: RenderMode,
    showGrid: Boolean,
    enableLighting: Boolean,
    lightAzimuthDeg: Float,
    backgroundPreset: BackgroundPreset,
    lodLevel: LodLevel,
    overrideMaterial: MaterialData?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val glView = remember { InteractiveGLSurfaceView(context, sceneManager) }

    LaunchedEffect(model) {
        sceneManager.setModel(model)
    }
    LaunchedEffect(renderMode) {
        sceneManager.renderMode = renderMode
    }
    LaunchedEffect(showGrid) {
        sceneManager.showGrid = showGrid
    }
    LaunchedEffect(enableLighting) {
        sceneManager.enableLighting = enableLighting
    }
    LaunchedEffect(lightAzimuthDeg) {
        sceneManager.lightAzimuthDeg = lightAzimuthDeg
    }
    LaunchedEffect(backgroundPreset) {
        sceneManager.backgroundPreset = backgroundPreset
    }
    LaunchedEffect(lodLevel) {
        sceneManager.lodLevel = lodLevel
    }
    LaunchedEffect(overrideMaterial) {
        if (overrideMaterial != null) {
            sceneManager.updateActiveMaterial(overrideMaterial)
        }
    }

    DisposableEffect(glView) {
        glView.onResume()
        onDispose {
            glView.onPause()
        }
    }

    AndroidView(
        factory = { glView },
        modifier = modifier
    )
}
