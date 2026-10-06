package com.example.viewer.manager

import com.example.viewer.model.AnimationClipData
import com.example.viewer.model.KeyframeSample
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class AnimationPlaybackState {
    PLAYING,
    PAUSED,
    STOPPED
}

data class AnimatedTransformState(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val translationZ: Float = 0f,
    val rotationXDeg: Float = 0f,
    val rotationYDeg: Float = 0f,
    val rotationZDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val scaleZ: Float = 1f
)

class AnimationManager {

    @Volatile
    var clips: List<AnimationClipData> = emptyList()
        private set

    @Volatile
    var selectedClipIndex: Int = 0
        private set

    @Volatile
    var playbackState: AnimationPlaybackState = AnimationPlaybackState.STOPPED
        private set

    @Volatile
    var currentTimeSeconds: Float = 0f
        private set

    private var lastTickNanos: Long = 0L

    fun setModelAnimations(newClips: List<AnimationClipData>) {
        clips = newClips
        selectedClipIndex = 0
        currentTimeSeconds = 0f
        lastTickNanos = System.nanoTime()
        playbackState = if (newClips.isNotEmpty()) AnimationPlaybackState.PLAYING else AnimationPlaybackState.STOPPED
    }

    fun selectClip(index: Int) {
        if (index in clips.indices) {
            selectedClipIndex = index
            currentTimeSeconds = 0f
            lastTickNanos = System.nanoTime()
            playbackState = AnimationPlaybackState.PLAYING
        }
    }

    fun play() {
        if (clips.isEmpty()) return
        lastTickNanos = System.nanoTime()
        playbackState = AnimationPlaybackState.PLAYING
    }

    fun pause() {
        if (clips.isEmpty()) return
        playbackState = AnimationPlaybackState.PAUSED
    }

    fun stop() {
        playbackState = AnimationPlaybackState.STOPPED
        currentTimeSeconds = 0f
    }

    fun evaluateCurrentTransform(nowNanos: Long = System.nanoTime()): AnimatedTransformState {
        val currentClips = clips
        if (currentClips.isEmpty() || selectedClipIndex !in currentClips.indices) {
            return AnimatedTransformState()
        }
        val clip = currentClips[selectedClipIndex]
        val duration = clip.durationSeconds.coerceAtLeast(0.1f)

        if (playbackState == AnimationPlaybackState.PLAYING) {
            if (lastTickNanos != 0L) {
                val dt = ((nowNanos - lastTickNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
                currentTimeSeconds = (currentTimeSeconds + dt) % duration
            }
            lastTickNanos = nowNanos
        } else {
            lastTickNanos = nowNanos
        }

        if (playbackState == AnimationPlaybackState.STOPPED) {
            return AnimatedTransformState()
        }

        val keyframes = clip.keyframes
        if (keyframes.size >= 2) {
            var prev = keyframes.first()
            var next = keyframes.last()
            for (i in 0 until keyframes.size - 1) {
                if (currentTimeSeconds >= keyframes[i].timeSeconds && currentTimeSeconds <= keyframes[i + 1].timeSeconds) {
                    prev = keyframes[i]
                    next = keyframes[i + 1]
                    break
                }
            }
            val span = (next.timeSeconds - prev.timeSeconds).coerceAtLeast(0.0001f)
            val alpha = ((currentTimeSeconds - prev.timeSeconds) / span).coerceIn(0f, 1f)
            return AnimatedTransformState(
                translationX = lerp(prev.translation[0], next.translation[0], alpha),
                translationY = lerp(prev.translation[1], next.translation[1], alpha),
                translationZ = lerp(prev.translation[2], next.translation[2], alpha),
                rotationXDeg = lerp(prev.rotationEulerDeg[0], next.rotationEulerDeg[0], alpha),
                rotationYDeg = lerp(prev.rotationEulerDeg[1], next.rotationEulerDeg[1], alpha),
                rotationZDeg = lerp(prev.rotationEulerDeg[2], next.rotationEulerDeg[2], alpha),
                scaleX = lerp(prev.scale[0], next.scale[0], alpha),
                scaleY = lerp(prev.scale[1], next.scale[1], alpha),
                scaleZ = lerp(prev.scale[2], next.scale[2], alpha)
            )
        }

        // Fallback kinematic curve based on clip semantic name (Idle, Walk, Run, Drive, etc.)
        val phase = (currentTimeSeconds / duration) * (2.0 * Math.PI).toFloat()
        val lower = clip.name.lowercase(Locale.ROOT)
        return when {
            lower.contains("idle") -> AnimatedTransformState(
                translationY = sin(phase) * 0.05f,
                rotationYDeg = sin(phase * 0.5f) * 8f,
                scaleY = 1f + cos(phase) * 0.02f
            )
            lower.contains("walk") -> AnimatedTransformState(
                translationY = abs(sin(phase)) * 0.11f,
                translationZ = sin(phase) * 0.14f,
                rotationXDeg = sin(phase) * 9f,
                rotationZDeg = cos(phase) * 5f
            )
            lower.contains("run") -> AnimatedTransformState(
                translationY = abs(sin(phase)) * 0.20f,
                translationZ = sin(phase) * 0.25f,
                rotationXDeg = 8f + sin(phase) * 16f,
                rotationZDeg = cos(phase) * 9f
            )
            lower.contains("drive") -> AnimatedTransformState(
                translationX = sin(phase) * 0.18f,
                translationY = sin(phase * 2f) * 0.03f,
                translationZ = cos(phase) * 0.22f,
                rotationYDeg = (currentTimeSeconds / duration) * 360f,
                rotationZDeg = sin(phase) * 4f
            )
            else -> AnimatedTransformState(
                translationY = sin(phase) * 0.08f,
                rotationYDeg = (currentTimeSeconds / duration) * 360f
            )
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        fun buildKeyframesForNamedClip(name: String, duration: Float): List<KeyframeSample> {
            val steps = 16
            val list = mutableListOf<KeyframeSample>()
            val lower = name.lowercase(Locale.ROOT)
            for (i in 0..steps) {
                val frac = i.toFloat() / steps
                val t = frac * duration
                val angle = (frac * 2.0 * Math.PI).toFloat()
                val sample = when {
                    lower.contains("idle") -> KeyframeSample(
                        timeSeconds = t,
                        translation = floatArrayOf(0f, sin(angle) * 0.06f, 0f),
                        rotationEulerDeg = floatArrayOf(0f, sin(angle) * 10f, cos(angle) * 2.5f),
                        scale = floatArrayOf(1f, 1f + sin(angle) * 0.025f, 1f)
                    )
                    lower.contains("walk") -> KeyframeSample(
                        timeSeconds = t,
                        translation = floatArrayOf(0f, abs(sin(angle)) * 0.12f, sin(angle) * 0.16f),
                        rotationEulerDeg = floatArrayOf(sin(angle) * 10f, cos(angle) * 6f, sin(angle) * 5f),
                        scale = floatArrayOf(1f, 1f, 1f)
                    )
                    lower.contains("run") -> KeyframeSample(
                        timeSeconds = t,
                        translation = floatArrayOf(0f, abs(sin(angle * 2f)) * 0.18f, sin(angle) * 0.28f),
                        rotationEulerDeg = floatArrayOf(10f + sin(angle) * 15f, 0f, cos(angle) * 8f),
                        scale = floatArrayOf(1f, 1f, 1f)
                    )
                    lower.contains("drive") -> KeyframeSample(
                        timeSeconds = t,
                        translation = floatArrayOf(sin(angle) * 0.25f, sin(angle * 4f) * 0.025f, cos(angle) * 0.25f),
                        rotationEulerDeg = floatArrayOf(sin(angle * 2f) * 3f, frac * 360f, cos(angle) * 4f),
                        scale = floatArrayOf(1f, 1f, 1f)
                    )
                    else -> KeyframeSample(
                        timeSeconds = t,
                        translation = floatArrayOf(0f, sin(angle) * 0.08f, 0f),
                        rotationEulerDeg = floatArrayOf(0f, frac * 360f, 0f),
                        scale = floatArrayOf(1f, 1f, 1f)
                    )
                }
                list.add(sample)
            }
            return list
        }
    }
}
