package haruka.reader.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import ca.mpreg.imagedecoder.ImageDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

/**
 * Small page thumbnails for [ReaderPagePreviewSheet], decoded from the pages' own streams and kept for one chapter.
 *
 * Decoding reads the bounds first, then decodes with BitmapFactory at a power-of-two sample size about
 * [TARGET_WIDTH] pixels wide (see [thumbnailSampleSize]) in RGB_565. Long-strip pages keep only their top
 * [THUMBNAIL_MAX_ASPECT] widths: a region decode where the format supports one, else a full sampled decode cut down.
 * Formats BitmapFactory cannot read (JPEG XL, AVIF before Android 12, JPEG 2000) go through the reader's own
 * image decoder instead. Animated pages show their first frame. A page nothing can decode has no thumbnail.
 *
 * The cache holds the last [MAX_ENTRIES] thumbnails of the chapter the sheet last showed, so reopening the sheet is
 * instant; showing another chapter empties it.
 */
internal object PageThumbnails {

    const val TARGET_WIDTH = 160

    private const val MAX_ENTRIES = 48

    // Each decode reads a whole page; a few at a time keeps the sheet's first frame from loading the disk or memory
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    private data class Key(val chapterId: Long, val index: Int)

    private val cache = LruCache<Key, Bitmap>(MAX_ENTRIES)

    private var chapterId: Long? = null

    /** Makes [chapterId] the chapter the cache keeps, emptying it if it held another one. */
    @Synchronized
    fun useChapter(chapterId: Long) {
        if (this.chapterId == chapterId) return
        cache.evictAll()
        this.chapterId = chapterId
    }

    fun cached(chapterId: Long, index: Int): Bitmap? = cache.get(Key(chapterId, index))

    /** The thumbnail of [page], decoded off the main thread unless it is cached; null if the page cannot be decoded. */
    suspend fun load(chapterId: Long, index: Int, page: ReaderPage): Bitmap? {
        cached(chapterId, index)?.let { return it }
        val stream = page.stream ?: return null
        val bitmap = withContext(decodeDispatcher) { decode(stream) } ?: return null
        synchronized(this) {
            if (this.chapterId == chapterId) cache.put(Key(chapterId, index), bitmap)
        }
        return bitmap
    }

    private fun decode(stream: () -> InputStream): Bitmap? {
        return try {
            decodeWithBitmapFactory(stream) ?: decodeWithImageDecoder(stream)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Page thumbnail failed" }
            null
        } catch (e: OutOfMemoryError) {
            logcat(LogPriority.WARN, e) { "Page thumbnail ran out of memory" }
            null
        }
    }

    private fun decodeWithBitmapFactory(stream: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val sampleSize = thumbnailSampleSize(width, height, TARGET_WIDTH)
        val cropHeight = thumbnailCropHeight(width, height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        if (cropHeight < height) {
            decodeTop(stream, width, cropHeight, options)?.let { return it }
        }
        val full = stream().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return full.cropTop(rows = cropHeight / sampleSize)
    }

    /** Decodes only the top [cropHeight] rows of a long-strip page; null where the format has no region decoder. */
    private fun decodeTop(
        stream: () -> InputStream,
        width: Int,
        cropHeight: Int,
        options: BitmapFactory.Options,
    ): Bitmap? {
        return stream().use { input ->
            val decoder = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(input)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(input, false)
                }
            } catch (_: java.io.IOException) {
                null
            } ?: return@use null
            try {
                decoder.decodeRegion(Rect(0, 0, width, cropHeight), options)
            } finally {
                decoder.recycle()
            }
        }
    }

    private fun decodeWithImageDecoder(stream: () -> InputStream): Bitmap? {
        return stream().use { input ->
            ImageDecoder.open(input).use { decoder ->
                val width = decoder.width
                val height = decoder.height
                if (width <= 0 || height <= 0) return@use null
                val sampleSize = thumbnailSampleSize(width, height, TARGET_WIDTH)
                val rows = thumbnailCropHeight(width, height) / sampleSize
                decoder.decodeToBitmap(null, sampleSize).cropTop(rows)
            }
        }
    }

    private fun Bitmap.cropTop(rows: Int): Bitmap {
        val keep = rows.coerceIn(1, height)
        if (keep == height) return this
        return Bitmap.createBitmap(this, 0, 0, width, keep).also { if (it !== this) recycle() }
    }
}
