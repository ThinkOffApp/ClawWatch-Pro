package com.thinkoff.clawwatch

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SignedInGoogleUser(
    val email: String,
    val displayName: String?,
    val avatarUrl: String?,
    val idToken: String?
)

class GoogleSignInManager(private val context: Context) {
    private val googleSignInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(context.getString(R.string.google_web_client_id))
            .build()
        GoogleSignIn.getClient(context, options)
    }

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    fun getCurrentUser(): SignedInGoogleUser? =
        GoogleSignIn.getLastSignedInAccount(context)?.toSignedInGoogleUser()

    fun handleSignInResult(data: Intent?): Result<SignedInGoogleUser> = runCatching {
        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
        } catch (apiError: ApiException) {
            // Some devices occasionally report a transient sign-in failure while still
            // persisting the selected account. Recover from that instead of hard-failing.
            GoogleSignIn.getLastSignedInAccount(context)
                ?: throw IllegalStateException(formatSignInError(apiError), apiError)
        } ?: GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Google sign-in returned no account")

        account.toSignedInGoogleUser()
    }

    fun signOut() {
        googleSignInClient.signOut()
    }

    suspend fun getFreshIdToken(): String? = withContext(Dispatchers.IO) {
        try {
            val account = Tasks.await(googleSignInClient.silentSignIn())
            account.idToken?.trim()?.takeIf { it.isNotBlank() }
                ?: GoogleSignIn.getLastSignedInAccount(context)?.idToken?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            GoogleSignIn.getLastSignedInAccount(context)?.idToken?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun GoogleSignInAccount.toSignedInGoogleUser(): SignedInGoogleUser {
        val accountEmail = email?.trim().orEmpty()
            .ifBlank {
                // Some Google identities do not return email in the result payload.
                // Keep a stable synthetic identifier so login can still proceed.
                id?.trim()?.takeIf { it.isNotBlank() }?.let { "$it@google.local" }.orEmpty()
            }
        require(accountEmail.isNotBlank()) { "Google sign-in returned no email" }
        return SignedInGoogleUser(
            email = accountEmail,
            displayName = displayName?.trim().orEmpty().ifBlank { null },
            avatarUrl = photoUrl?.toString(),
            idToken = idToken?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun formatSignInError(error: ApiException): String {
        val statusText = CommonStatusCodes.getStatusCodeString(error.statusCode)
        val details = error.statusMessage?.takeIf { it.isNotBlank() } ?: "no details"
        return "Google sign-in failed (code ${error.statusCode}: $statusText, $details)"
    }
}
