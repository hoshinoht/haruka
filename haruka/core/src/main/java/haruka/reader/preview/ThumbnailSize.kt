package haruka.reader.preview

/** Tallest region of a page a thumbnail shows, as a multiple of the page's width; long-strip pages are cut to it. */
const val THUMBNAIL_MAX_ASPECT = 3

/**
 * The `inSampleSize` to decode a [width] x [height] page at for a thumbnail [targetWidth] pixels wide: the largest
 * power of two that keeps the decoded width at or above [targetWidth], so the thumbnail is never upscaled. Pages
 * already narrower than the target, and unknown sizes (zero or negative), decode at full size (1).
 *
 * Only the width decides it: tall long-strip pages are cut to [thumbnailCropHeight] rather than shrunk further, so
 * a strip keeps the same sharpness as a regular page.
 */
fun thumbnailSampleSize(width: Int, height: Int, targetWidth: Int): Int {
    if (width <= 0 || height <= 0 || targetWidth <= 0) return 1
    var sampleSize = 1
    while (width / (sampleSize * 2) >= targetWidth) sampleSize *= 2
    return sampleSize
}

/**
 * How many source rows of a [width] x [height] page the thumbnail keeps, counted from the top: the whole page unless
 * it is taller than [maxAspect] times its width, as long-strip pages are, in which case only its top part.
 */
fun thumbnailCropHeight(width: Int, height: Int, maxAspect: Int = THUMBNAIL_MAX_ASPECT): Int {
    if (width <= 0 || height <= 0) return height.coerceAtLeast(0)
    val maxHeight = width.toLong() * maxAspect
    return if (height > maxHeight) maxHeight.toInt() else height
}
