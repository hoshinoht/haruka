package haruka.related

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Calls the related-titles API that keiyoushi (lib 1.6, `KeiSource`) and Komikku extensions carry.
 *
 * Mihon's source-api does not declare `supportsRelatedMangas` / `fetchRelatedMangaList`, so on the loaded
 * extension class they are plain public members and can only be reached through reflection. Their names
 * survive the extensions' R8 pass because they override methods of the compile-only extensions-lib.
 */
object RelatedMangaSource {

    private val TIMEOUT: Duration = 10.seconds

    private class Members(val supports: Method, val fetch: Method)

    // ConcurrentHashMap rejects null values, so classes without the API map to this sentinel.
    private val unsupported = Any()
    private val membersByClass = ConcurrentHashMap<Class<*>, Any>()

    /**
     * Related titles for [manga] from [source], or an empty list when the source lacks the API, opts out,
     * fails or takes longer than 10 s. Failures are logged, never thrown.
     */
    suspend fun fetch(source: Any, manga: SManga): List<SManga> {
        val members = membersOf(source.javaClass) ?: return emptyList()
        return try {
            if (members.supports.invoke(source) != true) return emptyList()
            val result = withTimeoutOrNull(TIMEOUT) { members.fetch.invokeSuspend(source, manga) }
            if (result == null) {
                logcat(LogPriority.WARN) { "Related titles from ${source.javaClass.name} timed out" }
                return emptyList()
            }
            (result as List<*>).filterIsInstance<SManga>()
        } catch (e: CancellationException) {
            // Our own scope going away must propagate; a cancellation thrown by the extension is just a failure.
            currentCoroutineContext().ensureActive()
            logcat(LogPriority.ERROR, e) { "Related titles from ${source.javaClass.name} failed" }
            emptyList()
        } catch (e: Throwable) {
            // Extensions are compiled against a different source-api, so LinkageErrors are as likely as exceptions.
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            logcat(LogPriority.ERROR, cause) { "Related titles from ${source.javaClass.name} failed" }
            emptyList()
        }
    }

    private fun membersOf(sourceClass: Class<*>): Members? {
        val cached = membersByClass.getOrPut(sourceClass) { lookup(sourceClass) ?: unsupported }
        return cached as? Members
    }

    private fun lookup(sourceClass: Class<*>): Members? {
        return try {
            val supports = sourceClass.getMethod("getSupportsRelatedMangas")
            val fetch = sourceClass.getMethod("fetchRelatedMangaList", SManga::class.java, Continuation::class.java)
            if (supports.returnType != Boolean::class.javaPrimitiveType) return null
            supports.isAccessible = true
            fetch.isAccessible = true
            Members(supports, fetch)
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: LinkageError) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Invokes a compiled `suspend fun` the way `KCallable.callSuspend` does, without kotlin-reflect. */
    private suspend fun Method.invokeSuspend(target: Any, argument: Any): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                invoke(target, argument, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
}
