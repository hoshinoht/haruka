package haruka.sync.remote

import haruka.sync.codec.SyncCodec
import haruka.sync.model.BUCKET_COUNT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RemoteLayoutTest {

    private val dev = RemoteLayout("app.haruka.dev")
    private val release = RemoteLayout("app.haruka")
    private val sha = SyncCodec.sha256Hex("stored".encodeToByteArray())

    @Test
    fun `names carry the namespace, kind, bucket index and stored sha prefix`() {
        // printf 'stored' | shasum -a 256
        sha.take(16) shouldBe "87b04e58961f9a99"

        dev.manifestName shouldBe "app.haruka.dev-manifest"
        dev.nameOf(RemoteFile.Manifest) shouldBe "app.haruka.dev-manifest"
        dev.nameOf(RemoteFile.bucket(7, sha, encrypted = false)) shouldBe "app.haruka.dev-b007-87b04e58961f9a99.pb.gz"
        dev.nameOf(RemoteFile.bucket(127, sha, encrypted = true)) shouldBe
            "app.haruka.dev-b127-87b04e58961f9a99.pb.gz.enc"
        dev.nameOf(RemoteFile.categories(sha, encrypted = false)) shouldBe
            "app.haruka.dev-categories-87b04e58961f9a99.pb.gz"
        release.nameOf(RemoteFile.prefs(sha, encrypted = true)) shouldBe "app.haruka-prefs-87b04e58961f9a99.pb.gz.enc"
    }

    @Test
    fun `app properties carry the namespace and kind`() {
        dev.appProperties(RemoteFile.Manifest) shouldBe mapOf("ns" to "app.haruka.dev", "kind" to "manifest")
        release.appProperties(RemoteFile.bucket(0, sha, encrypted = false)) shouldBe
            mapOf("ns" to "app.haruka", "kind" to "bucket")
        dev.appProperties(RemoteFile.categories(sha, encrypted = true))["kind"] shouldBe "categories"
        dev.appProperties(RemoteFile.prefs(sha, encrypted = true))["kind"] shouldBe "prefs"
    }

    @Test
    fun `namespaces never claim each other's files`() {
        val devFiles = listOf(RemoteFile.Manifest, RemoteFile.bucket(3, sha, false), RemoteFile.prefs(sha, true))
        for (file in devFiles) {
            release.parse(dev.nameOf(file)).shouldBeNull()
            dev.parse(release.nameOf(file)).shouldBeNull()
            dev.parse(dev.nameOf(file)) shouldBe file
        }
    }

    @Test
    fun `unknown and malformed names are not parsed`() {
        listOf(
            "manifest",
            "b007-87b04e58961f9a99.pb.gz",
            "app.haruka.dev-manifest.bak",
            "app.haruka.dev-b128-87b04e58961f9a99.pb.gz",
            "app.haruka.dev-b07-87b04e58961f9a99.pb.gz",
            "app.haruka.dev-b007-87B04E58961F9A99.pb.gz",
            "app.haruka.dev-b007-87b04e58961f9a9.pb.gz",
            "app.haruka.dev-b007-87b04e58961f9a99.pb",
            "app.haruka.dev-b007-87b04e58961f9a99.pb.gz.enc.enc",
            "app.haruka.dev-history-87b04e58961f9a99.pb.gz",
        ).forEach { dev.parse(it).shouldBeNull() }
    }

    @Test
    fun `invalid namespaces and file fields are rejected`() {
        listOf("", "haruka", "app.haruka-dev", "app..haruka", "1app.haruka", "app.haruka.").forEach {
            shouldThrow<IllegalArgumentException> { RemoteLayout(it) }
        }
        shouldThrow<IllegalArgumentException> { RemoteFile.bucket(BUCKET_COUNT, sha, false) }
        shouldThrow<IllegalArgumentException> { RemoteFile.bucket(-1, sha, false) }
        shouldThrow<IllegalArgumentException> { RemoteFile.categories(sha.uppercase(), false) }
        shouldThrow<IllegalArgumentException> { RemoteFile.prefs(sha.take(16), false) }
        shouldThrow<IllegalArgumentException> { RemoteFile.Bucket(0, "xyz", false) }
    }

    @Test
    fun `every file parses back from its name`() {
        val seed = Random.nextLong()
        println("RemoteLayoutTest name round trip seed=$seed")
        runBlocking {
            checkAll(ITERATIONS, PropTestConfig(seed = seed), remoteFiles) { file ->
                dev.parse(dev.nameOf(file)) shouldBe file
                release.parse(dev.nameOf(file)).shouldBeNull()
            }
        }
    }

    private companion object {
        const val ITERATIONS = 1_000

        val remoteFiles: Arb<RemoteFile> = arbitrary { rs ->
            val random = rs.random
            val storedSha = SyncCodec.sha256Hex(random.nextBytes(8))
            val encrypted = random.nextBoolean()
            when (random.nextInt(4)) {
                0 -> RemoteFile.Manifest
                1 -> RemoteFile.bucket(random.nextInt(BUCKET_COUNT), storedSha, encrypted)
                2 -> RemoteFile.categories(storedSha, encrypted)
                else -> RemoteFile.prefs(storedSha, encrypted)
            }
        }
    }
}
