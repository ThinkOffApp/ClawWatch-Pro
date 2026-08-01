package com.thinkoff.clawwatch.billing

import android.content.Context
import com.thinkoff.clawwatch.SecurePrefs

/**
 * Entitlement gate for cloud queries (petrus's model, Aug 1 2026):
 *  - BYOK (user's own Anthropic key in SecurePrefs): unmetered, free —
 *    the user pays Anthropic directly. This is also the OFFRAMP for heavy
 *    managed users (claudemm's design: past the allowance you are offered
 *    BYOK, never throttled and never silently billed).
 *  - Subscribed (clawwatch_pro_monthly): unmetered managed queries.
 *  - Otherwise: [FREE_QUERIES] lifetime free managed queries, then the
 *    paywall. The free tier doubles as onboarding — value before key entry.
 *
 * The counter is client-side for UX; the backend meters authoritatively in
 * Phase 3 (a wiped app does not reset the server's count).
 */
object QueryQuota {
    const val FREE_QUERIES = 3
    private const val PREF_USED = "managed_queries_used"

    sealed class Gate {
        object Byok : Gate()
        object Subscribed : Gate()
        data class Free(val remaining: Int) : Gate()
        object Paywall : Gate()
    }

    fun gate(context: Context, subscribed: Boolean): Gate {
        val prefs = SecurePrefs.watch(context)
        val byok = !prefs.getString("anthropic_api_key", null).isNullOrBlank()
        if (byok) return Gate.Byok
        if (subscribed) return Gate.Subscribed
        val used = prefs.getInt(PREF_USED, 0)
        return if (used < FREE_QUERIES) Gate.Free(FREE_QUERIES - used) else Gate.Paywall
    }

    /** Call after a managed (non-BYOK, non-subscribed) query is served. */
    fun recordManagedQuery(context: Context) {
        val prefs = SecurePrefs.watch(context)
        prefs.edit().putInt(PREF_USED, prefs.getInt(PREF_USED, 0) + 1).apply()
    }
}
