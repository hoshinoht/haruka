package haruka.stale

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource

class SourceAvailabilityTest {

    private val store = ExtensionStore(
        indexUrl = "https://example.org/index.min.json",
        name = "Store",
        badgeLabel = "S",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun loaded(pkgName: String, vararg sourceIds: Long, isObsolete: Boolean = false) = Extension.Loaded(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        signatures = listOf("key"),
        pkgFactory = null,
        sources = sourceIds.map { StubSource(id = it, lang = "en", name = "Source $it") },
        isObsolete = isObsolete,
    )

    private fun notLoaded(pkgName: String) = Extension.NotLoaded(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.4.1",
        versionCode = 1,
        isShared = true,
        contentWarning = ContentWarning.SAFE,
        signatures = listOf("other"),
        reason = Extension.NotLoaded.Reason.Untrusted("other"),
    )

    private fun available(pkgName: String, vararg sourceIds: Long) = Extension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        sources = sourceIds.map { Extension.Available.Source(id = it, lang = "en", name = "Source $it", baseUrl = "") },
        apkUrl = "",
        iconUrl = "",
        store = store,
    )

    private val availability = SourceAvailability(
        loaded = listOf(loaded("ext.healthy", 1L), loaded("ext.orphan", 2L, isObsolete = true)),
        notLoaded = listOf(notLoaded("ext.untrusted")),
        available = listOf(available("ext.healthy", 1L), available("ext.untrusted", 3L), available("ext.gone", 4L)),
    )

    @Test
    fun `a registered source from a listed extension is usable`() {
        availability.classify(1L, isStub = false) shouldBe null
    }

    @Test
    fun `a registered source from an extension no store lists is obsolete`() {
        availability.classify(2L, isStub = false) shouldBe UnavailableReason.Obsolete
    }

    @Test
    fun `a stub listed under an installed but unloaded package is not loaded`() {
        availability.classify(3L, isStub = true) shouldBe UnavailableReason.NotLoaded
    }

    @Test
    fun `a stub whose package isn't installed is not installed`() {
        availability.classify(4L, isStub = true) shouldBe UnavailableReason.NotInstalled
    }

    @Test
    fun `a stub no listing knows about is not installed`() {
        availability.classify(99L, isStub = true) shouldBe UnavailableReason.NotInstalled
    }

    @Test
    fun `the source manager's stub flag wins over stale extension state`() {
        availability.classify(3L, isStub = false) shouldBe null
        availability.classify(1L, isStub = true) shouldBe UnavailableReason.NotInstalled
    }
}
