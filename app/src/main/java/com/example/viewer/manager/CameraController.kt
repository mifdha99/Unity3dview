package com.example.viewer.manager

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CameraController {

    @Volatile
    var yawDeg: Float = 35f
        private set

    @Volatile
    var pitchDeg: Float = 22f
        private set

    @Volatile
    var distance: Float = 3.8f
        private set

    @Volatile
    var panX: Float = 0f
        private set

    @Volatile
    var panY: Float = -0.75f
        private set

    fun onSingleFingerRotate(dxPx: Float, dyPx: Float) {
        yawDeg = (yawDeg + dxPx * 0.42f) % 360f
        pitchDeg = (pitchDeg + dyPx * 0.38f).coerceIn(-85f, 85f)
    }

    fun onTwoFingerGesture(panDxPx: Float, panDyPx: Float, pinchRatio: Float) {
        if (pinchRatio > 0.01f && pinchRatio.isFinite()) {
            distance = (distance / pinchRatio).coerceIn(0.8f, 18.0f)
        }
        val panScale = distance * 0.0018f
        panX = (panX + panDxPx * panScale).coerceIn(-6f, 6f)
        panY = (panY - panDyPx * panScale).coerceIn(-6f, 6f)
    }

    fun resetView() {
        yawDeg = 35f
        pitchDeg = 22f
        distance = 3.8f
        panX = 0f
        panY = -0.75f
    }

    fun computeViewMatrix(outViewMatrix: FloatArray) {
        Matrix.setIdentityM(outViewMatrix, 0)
        Matrix.translateM(outViewMatrix, 0, panX, panY, -distance)
        Matrix.rotateM(outViewMatrix, 0, pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(outViewMatrix, 0, yawDeg, 0f, 1f, 0f)
    }

    fun computeCameraPositionWorld(): FloatArray {
        val pitchRad = Math.toRadians(pitchDeg.toDouble())
        val yawRad = Math.toRadians(yawDeg.toDouble())
        val cx = (distance * cos(pitchRad) * sin(-yawRad)).toFloat() - panX
        val cy = (distance * sin(pitchRad)).toFloat() - panY
        val cz = (distance * cos(pitchRad) * cos(-yawRad)).toFloat()
        return floatArrayOf(cx, cy, cz)
    }

    companion object {
        fun fingerDistance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0
            val dy = y1 - y0
            return sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        }
    }
}
