package haruka.sync.engine

import haruka.sync.remote.CasResult
import haruka.sync.remote.Head
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.RemoteRef
import haruka.sync.remote.RemoteStore
import java.util.concurrent.atomic.AtomicInteger

/** Counts the requests of one sync for the request-count log line (quota budget: plan "Fees and quotas"). */
internal class CountingRemoteStore(private val delegate: RemoteStore) : RemoteStore {
    private val lists = AtomicInteger()
    private val downloads = AtomicInteger()
    private val creates = AtomicInteger()
    private val heads = AtomicInteger()
    private val cas = AtomicInteger()
    private val deletes = AtomicInteger()

    val downloadCount: Int get() = downloads.get()
    val createCount: Int get() = creates.get()

    override suspend fun list(): List<RemoteRef> {
        lists.incrementAndGet()
        return delegate.list()
    }

    override suspend fun download(fileId: String): ByteArray {
        downloads.incrementAndGet()
        return delegate.download(fileId)
    }

    override suspend fun createImmutable(file: RemoteFile.Payload, bytes: ByteArray): String {
        creates.incrementAndGet()
        return delegate.createImmutable(file, bytes)
    }

    override suspend fun readHead(): Head? {
        heads.incrementAndGet()
        return delegate.readHead()
    }

    override suspend fun casHead(expected: Head?, bytes: ByteArray): CasResult {
        cas.incrementAndGet()
        return delegate.casHead(expected, bytes)
    }

    override suspend fun delete(fileId: String) {
        deletes.incrementAndGet()
        delegate.delete(fileId)
    }

    fun summary(): String = "list=${lists.get()} readHead=${heads.get()} download=${downloads.get()} " +
        "create=${creates.get()} cas=${cas.get()} delete=${deletes.get()}"
}
