package ru.simonenkov.yandexdisksync.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object PhotoCompressor {
    private const val TAG = "PhotoCompressor"
    private const val MIN_SIZE_BYTES = 200L * 1024
    private const val MAX_DECODE_PIXELS = 20_000_000L

    fun isCompressible(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg" ||
            ((ext == "heic" || ext == "heif") && Build.VERSION.SDK_INT >= 28)
    }

    fun compressToCache(
        context: Context, uri: Uri, fileName: String,
        sourceSize: Long, quality: Int, maxSide: Int
    ): File? {
        Log.w(TAG, "START $fileName size=$sourceSize q=$quality maxSide=$maxSide")
        if (sourceSize in 1 until MIN_SIZE_BYTES) {
            Log.w(TAG, "SKIP too small: $sourceSize"); return null
        }
        val cr = context.contentResolver
        val q = quality.coerceIn(50, 95)
        var bmp: Bitmap? = null
        var out: File? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            Log.w(TAG, "bounds ${w}x${h}")
            if (w <= 0 || h <= 0) { Log.w(TAG, "FAIL bounds"); return null }

            var sample = 1
            while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_DECODE_PIXELS) sample *= 2
            if (maxSide > 0) { while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2 }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (decoded == null) { Log.w(TAG, "FAIL decode null sample=$sample"); return null }
            var work: Bitmap = decoded
            bmp = work
            Log.w(TAG, "decoded ${work.width}x${work.height} sample=$sample")

            val srcExif = cr.openInputStream(uri)?.use { ExifInterface(it) }
            val orientation = srcExif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
            work = applyOrientation(work, orientation)
            bmp = work

            if (maxSide > 0 && maxOf(work.width, work.height) > maxSide) {
                val k = maxSide.toFloat() / maxOf(work.width, work.height)
                val scaled = Bitmap.createScaledBitmap(work, (work.width * k).toInt().coerceAtLeast(1), (work.height * k).toInt().coerceAtLeast(1), true)
                if (scaled != work) work.recycle()
                work = scaled
                bmp = work
            }

            val dir = File(context.cacheDir, "compressed").apply { mkdirs() }
            val outFile = File(dir, "${System.nanoTime()}_${fileName.substringBeforeLast('.')}.jpg")
            out = outFile
            val ok = FileOutputStream(outFile).use { work.compress(Bitmap.CompressFormat.JPEG, q, it) }
            Log.w(TAG, "compress ok=$ok len=${outFile.length()}")

            srcExif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { date ->
                try {
                    ExifInterface(outFile.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                        saveAttributes()
                    }
                } catch (e: Exception) { Log.w(TAG, "exif write failed: ${e.message}") }
            }

            if (!ok || outFile.length() == 0L) { Log.w(TAG, "FAIL compress empty"); outFile.delete(); return null }
            if (outFile.length() >= sourceSize) { Log.w(TAG, "FAIL not smaller ${outFile.length()} >= $sourceSize"); outFile.delete(); return null }
            Log.w(TAG, "SUCCESS len=${outFile.length()}")
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "FAIL exception ${e.javaClass.simpleName}: ${e.message}", e)
            out?.delete(); return null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "FAIL OutOfMemory", e)
            out?.delete(); return null
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
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
