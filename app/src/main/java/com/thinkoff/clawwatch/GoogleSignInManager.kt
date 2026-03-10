package com.thinkoff.clawwatch

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

data class SignedInGoogleUser(
    val email: String,
    val displayName: String?,
    val avatarUrl: String?
)

class GoogleSignInManager(private val context: Context) {
    private val googleSignInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    fun getCurrentUser(): SignedInGoogleUser? =
        GoogleSignIn.getLastSignedInAccount(context)?.toSignedInGoogleUser()

    fun handleSignInResult(data: Intent?): Result<SignedInGoogleUser> = runCatching {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
            ?: error("Google sign-in returned no account")
        account.toSignedInGoogleUser()
    }

    fun signOut() {
        googleSignInClient.signOut()
    }

    private fun GoogleSignInAccount.toSignedInGoogleUser(): SignedInGoogleUser {
        val accountEmail = email?.trim().orEmpty()
        require(accountEmail.isNotBlank()) { "Google sign-in returned no email" }
        return SignedInGoogleUser(
            email = accountEmail,
            displayName = displayName?.trim().orEmpty().ifBlank { null },
            avatarUrl = photoUrl?.toString()
        )
    }
}
