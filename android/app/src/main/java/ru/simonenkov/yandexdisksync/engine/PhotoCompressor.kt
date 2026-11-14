package ru.simonenkov.yandexdisksync.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Compresses photos into a temporary JPEG inside cacheDir.
 * The original file on the phone is NEVER modified.
 * Returns null when compression is impossible or not worth it
 * (caller must upload the original in that case).
 */
object PhotoCompressor {

    private const val MIN_SIZE_BYTES = 200L * 1024      // skip files smaller than 200 KB
    private const val MAX_DECODE_PIXELS = 20_000_000L   // ~20 MP decode cap, OOM protection

    fun isCompressible(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg" ||
            ((ext == "heic" || ext == "heif") && Build.VERSION.SDK_INT >= 28)
    }

    fun compressToCache(
        context: Context,
        uri: Uri,
        fileName: String,
        sourceSize: Long,
        quality: Int,
        maxSide: Int
    ): File? {
        if (sourceSize in 1 until MIN_SIZE_BYTES) return null
        val cr = context.contentResolver
        val q = quality.coerceIn(50, 95)
        var bmp: Bitmap? = null
        var out: File? = null

        try {
            // 1) bounds only, no full decode
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            // 2) downsample while decoding to save memory
            var sample = 1
            while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_DECODE_PIXELS) sample *= 2
            if (maxSide > 0) {
                while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var work: Bitmap = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            bmp = work

            // 3) apply EXIF orientation (otherwise portraits get rotated)
            val srcExif = cr.openInputStream(uri)?.use { ExifInterface(it) }
            val orientation = srcExif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
            work = applyOrientation(work, orientation)
            bmp = work

            // 4) exact resize of the longest side
            if (maxSide > 0 && maxOf(work.width, work.height) > maxSide) {
                val k = maxSide.toFloat() / maxOf(work.width, work.height)
                val scaled = Bitmap.createScaledBitmap(
                    work,
                    (work.width * k).toInt().coerceAtLeast(1),
                    (work.height * k).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled != work) work.recycle()
                work = scaled
                bmp = work
            }

            // 5) write JPEG (HEIC becomes .jpg)
            val dir = File(context.cacheDir, "compressed").apply { mkdirs() }
            val outFile = File(dir, "${System.nanoTime()}_${fileName.substringBeforeLast('.')}.jpg")
            out = outFile
            FileOutputStream(outFile).use { work.compress(Bitmap.CompressFormat.JPEG, q, it) }

            // 6) keep original capture date so photos do not jump to "today"
            srcExif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { date ->
                ExifInterface(outFile.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                    saveAttributes()
                }
            }

            // 7) if not smaller - compression is pointless
            if (outFile.length() >= sourceSize) {
                outFile.delete()
                return null
            }
            return outFile
        } catch (e: Exception) {
            out?.delete()
            return null
        } catch (e: OutOfMemoryError) {
            out?.delete()
            return null
        } finally {
            bmp?.recycle()
        }
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(degrees) }, true
        )
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
