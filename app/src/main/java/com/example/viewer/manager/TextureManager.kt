package com.example.viewer.manager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.viewer.model.TextureData
import java.io.File
import java.util.Locale

class TextureManager {

    fun loadTextureFromFile(file: File, maxDimension: Int = 1024): TextureData? {
        if (!file.exists() || !file.canRead()) return null
        val ext = file.extension.lowercase(Locale.ROOT)
        return try {
            val bitmap = if (ext == "tga") {
                decodeTga(file.readBytes(), maxDimension)
            } else {
                decodeStandardBitmap(file.absolutePath, maxDimension)
            }
            if (bitmap != null) {
                TextureData(
                    name = file.name,
                    format = ext.uppercase(Locale.ROOT),
                    width = bitmap.width,
                    height = bitmap.height,
                    bitmap = bitmap,
                    fileSizeBytes = file.length()
                )
            } else {
                null
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun loadTextureFromBytes(name: String, formatHint: String, bytes: ByteArray, maxDimension: Int = 1024): TextureData? {
        if (bytes.isEmpty()) return null
        val ext = formatHint.lowercase(Locale.ROOT).removePrefix(".")
        return try {
            val bitmap = if (ext == "tga" || name.endsWith(".tga", ignoreCase = true)) {
                decodeTga(bytes, maxDimension)
            } else {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                var sampleSize = 1
                while (boundsOpts.outWidth / sampleSize > maxDimension || boundsOpts.outHeight / sampleSize > maxDimension) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            }
            if (bitmap != null) {
                TextureData(
                    name = name,
                    format = ext.uppercase(Locale.ROOT).ifEmpty { "PNG" },
                    width = bitmap.width,
                    height = bitmap.height,
                    bitmap = bitmap,
                    fileSizeBytes = bytes.size.toLong()
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeStandardBitmap(path: String, maxDimension: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxDimension || boundsOpts.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, decodeOpts)
    }

    /**
     * Custom Truevision TGA parser supporting 24-bit and 32-bit uncompressed (type 2)
     * and RLE compressed (type 10) TGA textures commonly found in Unity projects.
     */
    fun decodeTga(data: ByteArray, maxDimension: Int = 1024): Bitmap? {
        if (data.size < 18) return null
        val idLength = data[0].toInt() and 0xFF
        val colorMapType = data[1].toInt() and 0xFF
        val imageType = data[2].toInt() and 0xFF
        if (colorMapType != 0 || (imageType != 2 && imageType != 3 && imageType != 10)) {
            return null
        }
        val width = (data[12].toInt() and 0xFF) or ((data[13].toInt() and 0xFF) shl 8)
        val height = (data[14].toInt() and 0xFF) or ((data[15].toInt() and 0xFF) shl 8)
        val bpp = data[16].toInt() and 0xFF
        val descriptor = data[17].toInt() and 0xFF

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null
        val bytesPerPixel = bpp / 8
        if (bytesPerPixel !in intArrayOf(1, 3, 4)) return null

        val topOrigin = (descriptor and 0x20) != 0
        val pixels = IntArray(width * height)
        var offset = 18 + idLength

        fun readColor(pos: Int): Int {
            if (pos + bytesPerPixel > data.size) return Color.GRAY
            return when (bytesPerPixel) {
                1 -> {
                    val v = data[pos].toInt() and 0xFF
                    Color.rgb(v, v, v)
                }
                3 -> {
                    val b = data[pos].toInt() and 0xFF
                    val g = data[pos + 1].toInt() and 0xFF
                    val r = data[pos + 2].toInt() and 0xFF
                    Color.rgb(r, g, b)
                }
                else -> {
                    val b = data[pos].toInt() and 0xFF
                    val g = data[pos + 1].toInt() and 0xFF
                    val r = data[pos + 2].toInt() and 0xFF
                    val a = data[pos + 3].toInt() and 0xFF
                    Color.argb(a, r, g, b)
                }
            }
        }

        if (imageType == 2 || imageType == 3) {
            for (y in 0 until height) {
                val targetY = if (topOrigin) y else (height - 1 - y)
                val rowBase = targetY * width
                for (x in 0 until width) {
                    if (offset + bytesPerPixel > data.size) break
                    pixels[rowBase + x] = readColor(offset)
                    offset += bytesPerPixel
                }
            }
        } else if (imageType == 10) {
            var currentPixel = 0
            val totalPixels = width * height
            while (currentPixel < totalPixels && offset < data.size) {
                val packetHeader = data[offset++].toInt() and 0xFF
                val count = (packetHeader and 0x7F) + 1
                if ((packetHeader and 0x80) != 0) {
                    val color = readColor(offset)
                    offset += bytesPerPixel
                    for (i in 0 until count) {
                        if (currentPixel >= totalPixels) break
                        val x = currentPixel % width
                        val y = currentPixel / width
                        val targetY = if (topOrigin) y else (height - 1 - y)
                        pixels[targetY * width + x] = color
                        currentPixel++
                    }
                } else {
                    for (i in 0 until count) {
                        if (currentPixel >= totalPixels || offset + bytesPerPixel > data.size) break
                        val color = readColor(offset)
                        offset += bytesPerPixel
                        val x = currentPixel % width
                        val y = currentPixel / width
                        val targetY = if (topOrigin) y else (height - 1 - y)
                        pixels[targetY * width + x] = color
                        currentPixel++
                    }
                }
            }
        }

        val fullBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (width > maxDimension || height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(width, height).toFloat()
            val nw = (width * scale).toInt().coerceAtLeast(1)
            val nh = (height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(fullBitmap, nw, nh, true)
        }
        return fullBitmap
    }

    fun createFallbackCheckerBitmap(): Bitmap {
        val size = 64
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val cell = ((x / 8) + (y / 8)) % 2 == 0
                pixels[y * size + x] = if (cell) Color.rgb(195, 205, 220) else Color.rgb(145, 155, 172)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
