package haruka.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Walks the downloads root (root, source folders, series folders, chapters) and learns sizes from directory listings
 * alone; no file is ever opened. On a storage access framework tree each directory is one children query returning id,
 * name, type and size, so a scan costs 1 + sources + series + page-folder chapters queries (CBZ chapters take their
 * size from the series listing). Plain file roots use `java.io.File` listings, one stat per entry.
 */
@Inject
class DownloadsScanner(private val context: Context) {

    /**
     * The folders under [root], or null when it is neither a document tree nor a plain directory. [onProgress] gets
     * the series folders listed so far and their total, from any thread.
     */
    suspend fun scan(root: UniFile, onProgress: (done: Int, total: Int) -> Unit): List<SourceFolder>? {
        val uri = root.uri
        return when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> walk(uri, DocumentLister(context.contentResolver, uri), onProgress)
            ContentResolver.SCHEME_FILE -> uri.path?.let { walk(File(it), FileLister, onProgress) }
            else -> null
        }
    }

    private suspend fun <H> walk(
        root: H,
        lister: Lister<H>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SourceFolder> {
        val sourceDirs = lister.list(root).filter { it.isDirectory && !isInProgress(it.name) }
        val seriesBySource = sourceDirs.map { sourceDir ->
            currentCoroutineContext().ensureActive()
            sourceDir to lister.list(sourceDir.handle).filter { it.isDirectory && !isInProgress(it.name) }
        }
        val total = seriesBySource.sumOf { it.second.size }
        val done = AtomicInteger()
        onProgress(0, total)
        return coroutineScope {
            seriesBySource.map { (sourceDir, seriesDirs) ->
                val series = seriesDirs.map { seriesDir ->
                    async(listingDispatcher) {
                        scanSeries(seriesDir, lister).also { onProgress(done.incrementAndGet(), total) }
                    }
                }
                sourceDir.name to series
            }
                .map { (name, series) -> SourceFolder(name, series.awaitAll()) }
        }
    }

    private suspend fun <H> scanSeries(seriesDir: Node<H>, lister: Lister<H>): SeriesFolder {
        val chapters = ArrayList<ChapterEntry>()
        var size = 0L
        for (entry in lister.list(seriesDir.handle)) {
            if (isInProgress(entry.name)) continue
            currentCoroutineContext().ensureActive()
            when {
                // Folder of pages: their sizes come from its own listing; nested folders aren't part of a download.
                entry.isDirectory -> {
                    val pages = lister.list(entry.handle).sumOf { if (it.isDirectory) 0L else it.size }
                    chapters += ChapterEntry(entry.name, pages)
                    size += pages
                }
                entry.name.endsWith(CBZ_SUFFIX, ignoreCase = true) -> {
                    chapters += ChapterEntry(entry.name, entry.size)
                    size += entry.size
                }
                else -> size += entry.size
            }
        }
        return SeriesFolder(seriesDir.name, size, chapters)
    }

    /** Unfinished downloads: chapter folders being written (`_tmp`) and pages or archives being written (`.tmp`). */
    private fun isInProgress(name: String): Boolean =
        name.endsWith(Downloader.TMP_DIR_SUFFIX) || name.endsWith(TMP_FILE_SUFFIX)
}

private class Node<H>(val name: String, val isDirectory: Boolean, val size: Long, val handle: H)

private fun interface Lister<H> {
    fun list(dir: H): List<Node<H>>
}

/** One children query per directory; the child document uris are built from the ids it returns. */
private class DocumentLister(private val resolver: ContentResolver, private val root: Uri) : Lister<Uri> {

    override fun list(dir: Uri): List<Node<Uri>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        val cursor = resolver.query(children, PROJECTION, null, null, null) ?: return emptyList()
        return cursor.use {
            val result = ArrayList<Node<Uri>>(it.count)
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                val isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (isDirectory || it.isNull(3)) 0L else it.getLong(3)
                result +=
                    Node(name, isDirectory, size, DocumentsContract.buildDocumentUriUsingTree(root, it.getString(0)))
            }
            result
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

private object FileLister : Lister<File> {
    override fun list(dir: File): List<Node<File>> = dir.listFiles().orEmpty().map { file ->
        val isDirectory = file.isDirectory
        Node(file.name, isDirectory, if (isDirectory) 0L else file.length(), file)
    }
}

private const val CBZ_SUFFIX = ".cbz"
private const val TMP_FILE_SUFFIX = ".tmp"
private const val LISTING_PARALLELISM = 8

// Listings block on the provider or the disk; a few at once keeps a document tree scan from being one long queue.
private val listingDispatcher = Dispatchers.IO.limitedParallelism(LISTING_PARALLELISM)
