package haruka.sync.drive

import haruka.sync.remote.CasResult
import haruka.sync.remote.FakeRemoteStore
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.RemoteFileGoneException
import haruka.sync.remote.RemoteLayout
import haruka.sync.remote.RemoteStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

/**
 * The behaviour every [RemoteStore] must share, run against the fake and against [DriveRemoteStore] talking to
 * [FakeDrive]. Stores returned by [device] in one test share one remote, like devices on one account.
 */
abstract class RemoteStoreContract {

    protected val layout = RemoteLayout("app.haruka.test")

    protected abstract fun device(layout: RemoteLayout = this.layout): RemoteStore

    /** Puts a manifest on the remote directly, as a lost create race would. */
    protected abstract fun seedManifest(bytes: ByteArray, createdAtMillis: Long)

    /** Makes another device commit [bytes] after the next head read fetched the tag, before it reads content. */
    protected abstract fun commitBeforeNextContentRead(bytes: ByteArray)

    private fun bytes(text: String) = text.encodeToByteArray()

    private fun bucket(index: Int) = RemoteFile.Bucket(index, index.toString(16).padStart(16, '0'), encrypted = false)

    @Test
    fun `an empty remote has no head and no files`() = runBlocking<Unit> {
        val store = device()

        store.readHead().shouldBeNull()
        store.list().shouldBeEmpty()
    }

    @Test
    fun `casHead without a head creates the manifest once`() = runBlocking<Unit> {
        val a = device()
        val b = device()

        a.casHead(null, bytes("m0")) shouldBe CasResult.Committed
        b.casHead(null, bytes("other")) shouldBe CasResult.Conflict

        val head = b.readHead().shouldNotBeNull()
        head.bytes.decodeToString() shouldBe "m0"
        b.list().map { it.name } shouldBe listOf(layout.manifestName)
    }

    @Test
    fun `casHead with the current head commits and changes the tag`() = runBlocking<Unit> {
        val store = device()
        store.casHead(null, bytes("m0"))
        val head = store.readHead()!!

        store.casHead(head, bytes("m1")) shouldBe CasResult.Committed

        val next = store.readHead()!!
        next.fileId shouldBe head.fileId
        next.etag shouldNotBe head.etag
        next.bytes.decodeToString() shouldBe "m1"
    }

    @Test
    fun `a stale head conflicts and leaves the remote unchanged`() = runBlocking<Unit> {
        val store = device()
        store.casHead(null, bytes("m0"))
        val stale = store.readHead()!!
        store.casHead(stale, bytes("m1")) shouldBe CasResult.Committed

        store.casHead(stale, bytes("m2")) shouldBe CasResult.Conflict

        store.readHead()!!.bytes.decodeToString() shouldBe "m1"
    }

    @Test
    fun `two devices committing from the same head - the second conflicts`() = runBlocking<Unit> {
        val a = device()
        val b = device()
        a.casHead(null, bytes("m0"))
        val headA = a.readHead()!!
        val headB = b.readHead()!!

        a.casHead(headA, bytes("from a")) shouldBe CasResult.Committed
        b.casHead(headB, bytes("from b")) shouldBe CasResult.Conflict

        b.readHead()!!.bytes.decodeToString() shouldBe "from a"
    }

    @Test
    fun `a commit between tag and content read makes the head stale, never lost`() = runBlocking<Unit> {
        val store = device()
        store.casHead(null, bytes("m0"))
        commitBeforeNextContentRead(bytes("foreign"))

        val head = store.readHead()!!
        head.bytes.decodeToString() shouldBe "foreign"
        store.casHead(head, bytes("mine")) shouldBe CasResult.Conflict
        store.readHead()!!.bytes.decodeToString() shouldBe "foreign"

        store.casHead(store.readHead(), bytes("mine")) shouldBe CasResult.Committed
    }

    @Test
    fun `the oldest of duplicate manifests is the head`() = runBlocking<Unit> {
        seedManifest(bytes("newer"), createdAtMillis = 2_000_000)
        seedManifest(bytes("older"), createdAtMillis = 1_000_000)

        device().readHead()!!.bytes.decodeToString() shouldBe "older"
    }

    @Test
    fun `payloads round-trip and listings stay inside the namespace`() = runBlocking<Unit> {
        val store = device()
        val other = device(RemoteLayout("app.haruka.other"))
        other.createImmutable(bucket(1), bytes("not mine"))
        store.casHead(null, bytes("m0"))

        val ids = (0..6).map { store.createImmutable(bucket(it), bytes("payload $it")) }

        val listed = store.list()
        listed.map { it.name } shouldContainExactlyInAnyOrder
            listOf(layout.manifestName) + (0..6).map { layout.nameOf(bucket(it)) }
        listed.filter { it.id in ids }.map { it.sizeBytes }.toSet() shouldBe setOf(9L)
        ids.forEachIndexed { i, id -> store.download(id).decodeToString() shouldBe "payload $i" }
        other.list().map { it.name } shouldBe listOf(RemoteLayout("app.haruka.other").nameOf(bucket(1)))
    }

    @Test
    fun `deleted files are gone and deleting twice is fine`() = runBlocking<Unit> {
        val store = device()
        val id = store.createImmutable(bucket(3), bytes("x"))

        store.delete(id)
        store.delete(id)

        shouldThrow<RemoteFileGoneException> { store.download(id) }.fileId shouldBe id
        store.list().shouldBeEmpty()
    }
}

class FakeRemoteStoreContractTest : RemoteStoreContract() {
    private val backend = FakeRemoteStore.Backend()

    override fun device(layout: RemoteLayout) = FakeRemoteStore(layout, backend)

    override fun seedManifest(bytes: ByteArray, createdAtMillis: Long) {
        backend.add(layout.manifestName, layout.appProperties(RemoteFile.Manifest), bytes, createdAtMillis)
    }

    override fun commitBeforeNextContentRead(bytes: ByteArray) {
        backend.intercept = { step, fileId ->
            if (step == FakeRemoteStore.Step.READ_HEAD_CONTENT) {
                backend.intercept = { _, _ -> }
                backend.overwrite(fileId!!, bytes)
            }
        }
    }

    @Test
    fun `injected ambiguous CAS outcomes apply or drop the write`() = runBlocking<Unit> {
        val store = device()
        store.casHead(null, "m0".encodeToByteArray())

        backend.nextCasAmbiguous = false
        store.casHead(store.readHead(), "lost".encodeToByteArray()).shouldBeInstanceOf<CasResult.Ambiguous>()
        store.readHead()!!.bytes.decodeToString() shouldBe "m0"

        backend.nextCasAmbiguous = true
        store.casHead(store.readHead(), "landed".encodeToByteArray()).shouldBeInstanceOf<CasResult.Ambiguous>()
        store.readHead()!!.bytes.decodeToString() shouldBe "landed"
        backend.nextCasAmbiguous.shouldBeNull()
    }

    @Test
    fun `injected failures and removals surface as errors`() = runBlocking<Unit> {
        val store = device()
        val id = store.createImmutable(RemoteFile.Categories("0123456789abcdef", encrypted = false), byteArrayOf(1))
        backend.intercept = { step, _ -> if (step == FakeRemoteStore.Step.LIST) throw IOException("offline") }

        shouldThrow<IOException> { store.list() }.message shouldBe "offline"
        backend.remove(id)
        shouldThrow<RemoteFileGoneException> { store.download(id) }
    }
}

class DriveRemoteStoreContractTest : RemoteStoreContract() {
    // Three files per page, so listings of more than three files exercise paging.
    private val drive = FakeDrive(pageSize = 3)
    private val server = MockWebServer().apply {
        dispatcher = drive
        start()
    }
    private val client = OkHttpClient()

    @AfterEach
    fun stop() {
        server.close()
    }

    override fun device(layout: RemoteLayout) =
        DriveRemoteStore(client, TestTokens("token"), layout, server.url("/"), sleep = {}, jitterMillis = { 0 })

    override fun seedManifest(bytes: ByteArray, createdAtMillis: Long) {
        drive.add(
            layout.manifestName,
            layout.appProperties(RemoteFile.Manifest),
            bytes,
            Instant.ofEpochMilli(createdAtMillis),
        )
    }

    override fun commitBeforeNextContentRead(bytes: ByteArray) {
        drive.beforeNextDownload = { fileId -> drive.overwrite(fileId, bytes) }
    }
}
