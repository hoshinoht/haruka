package haruka.sync.drive

/**
 * OAuth access tokens for the `drive.appdata` scope. Implementations keep tokens in memory only; nothing here
 * ever sees or stores a refresh token.
 */
interface AccessTokenProvider {

    /** A token believed valid; may suspend while the platform authorizes (for example a cached grant refresh). */
    suspend fun accessToken(): String

    /** Drive rejected [token] with 401: drop it so the next [accessToken] returns a fresh one. */
    suspend fun invalidate(token: String)
}
