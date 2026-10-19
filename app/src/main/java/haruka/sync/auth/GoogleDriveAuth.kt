package haruka.sync.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import haruka.sync.drive.AccessTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Outcome of asking Google Play services for a `drive.appdata` access token. */
sealed interface DriveAuthResult {

    data class Authorized(val accessToken: String) : DriveAuthResult

    /**
     * The user has to pick an account or grant the scope. A screen launches [pendingIntent] (for example with
     * `ActivityResultContracts.StartIntentSenderForResult`) and passes the returned intent to
     * [GoogleDriveAuth.completeAuthorization].
     */
    data class NeedsUserAction(val pendingIntent: PendingIntent) : DriveAuthResult

    /** Authorization is unavailable: Play services missing or failing, or a rejected app (an `ApiException`). */
    data class Failed(val cause: Exception) : DriveAuthResult
}

/** Thrown by [GoogleDriveAuth.accessToken] when only the user can continue; background callers stop and report it. */
class DriveAuthRequiredException(val pendingIntent: PendingIntent) :
    Exception("Google Drive access needs the user's consent")

/**
 * [AccessTokenProvider] over the Google Identity [AuthorizationClient], requesting only [DRIVE_APPDATA].
 *
 * The token lives in this object's memory only. While the grant stands, [authorize] returns a fresh token without
 * UI, so there is no refresh token to keep: Play services caches the token and renews it on demand. The Android
 * OAuth client is matched by package name and signing certificate, so no client id appears here.
 */
class GoogleDriveAuth(context: Context) : AccessTokenProvider {

    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val mutex = Mutex()

    @Volatile
    private var token: String? = null

    /** Asks for the scope without UI; a screen launches [DriveAuthResult.NeedsUserAction] itself. */
    suspend fun authorize(): DriveAuthResult = mutex.withLock { authorizeLocked() }

    /** Reads the result intent of the consent flow started from [DriveAuthResult.NeedsUserAction]. */
    suspend fun completeAuthorization(data: Intent?): DriveAuthResult = mutex.withLock {
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: Exception) {
            // ApiException when the user backed out or the flow failed; a missing intent is the same outcome.
            return@withLock DriveAuthResult.Failed(e)
        }
        resultOf(result)
    }

    override suspend fun accessToken(): String = token ?: mutex.withLock {
        // Another caller may have authorized while this one waited for the lock.
        token ?: when (val result = authorizeLocked()) {
            is DriveAuthResult.Authorized -> result.accessToken
            is DriveAuthResult.NeedsUserAction -> throw DriveAuthRequiredException(result.pendingIntent)
            is DriveAuthResult.Failed -> throw result.cause
        }
    }

    /**
     * Drops [token] here and from the Play services cache, so the next [accessToken] re-authorizes silently and
     * gets a new one instead of the cached, rejected token.
     */
    override suspend fun invalidate(token: String) = mutex.withLock {
        if (this.token == token) this.token = null
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        Unit
    }

    /** Disconnect: revokes the grant for [email] (from Drive `about.get`), so the next authorization asks again. */
    suspend fun revoke(email: String) = mutex.withLock {
        token = null
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .setScopes(listOf(Scope(DRIVE_APPDATA)))
            .build()
        client.revokeAccess(request).await()
        Unit
    }

    private suspend fun authorizeLocked(): DriveAuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DriveAuthResult.Failed(e)
        }
        return resultOf(result)
    }

    private fun resultOf(result: AuthorizationResult): DriveAuthResult {
        if (result.hasResolution()) {
            token = null
            return DriveAuthResult.NeedsUserAction(result.pendingIntent!!)
        }
        val accessToken = result.accessToken
            ?: return DriveAuthResult.Failed(IllegalStateException("Authorization returned no access token"))
        token = accessToken
        return DriveAuthResult.Authorized(accessToken)
    }

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
