package com.example.lifelens.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Prepare image for VLM:
 * - fix EXIF rotation
 * - scale down to <= maxSize
 * - optional square center-crop
 * - write jpeg to cacheDir and return absolute path
 */
suspend fun prepareImageForVlm(
    context: android.content.Context,
    srcPath: String,
    maxSize: Int = 448,
    squareCrop: Boolean = true
): String = withContext(Dispatchers.IO) {

    val srcFile = File(srcPath)
    require(srcFile.exists() && srcFile.length() > 0L) { "Image not found or empty: $srcPath" }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Failed to read image bounds: $srcPath" }

    val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSize, maxSize)
    val opts = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    var bmp = BitmapFactory.decodeFile(srcFile.absolutePath, opts)
        ?: throw IllegalStateException("Failed to decode image: $srcPath")

    val rotated = applyExifRotationIfNeeded(srcFile.absolutePath, bmp)
    if (rotated !== bmp) {
        bmp.recycle()
        bmp = rotated
    }

    val scaled = scaleDownToMax(bmp, maxSize)
    if (scaled !== bmp) {
        bmp.recycle()
        bmp = scaled
    }

    val finalBmp = if (squareCrop) {
        val cropped = centerCropSquare(bmp)
        if (cropped !== bmp) bmp.recycle()
        cropped
    } else bmp

    val outFile = File(context.cacheDir, "vlm_${System.currentTimeMillis()}.jpg")
    FileOutputStream(outFile).use { fos ->
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
    }
    finalBmp.recycle()

    require(outFile.exists() && outFile.length() > 0L) { "Prepared image is empty" }
    outFile.absolutePath
}

private fun computeInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
    var inSampleSize = 1
    val halfW = w / 2
    val halfH = h / 2
    while (halfW / inSampleSize >= reqW && halfH / inSampleSize >= reqH) {
        inSampleSize *= 2
    }
    return inSampleSize.coerceAtLeast(1)
}

private fun applyExifRotationIfNeeded(path: String, bitmap: Bitmap): Bitmap {
    return try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) bitmap
        else {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
    } catch (_: Exception) {
        bitmap
    }
}

private fun scaleDownToMax(src: Bitmap, maxSize: Int): Bitmap {
    val w = src.width
    val h = src.height
    val maxSide = maxOf(w, h)
    if (maxSide <= maxSize) return src
    val scale = maxSize.toFloat() / maxSide.toFloat()
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, nw, nh, true)
}

private fun centerCropSquare(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val side = minOf(w, h)
    val x = (w - side) / 2
    val y = (h - side) / 2
    return if (x == 0 && y == 0 && side == w && side == h) src
    else Bitmap.createBitmap(src, x, y, side, side)
}
