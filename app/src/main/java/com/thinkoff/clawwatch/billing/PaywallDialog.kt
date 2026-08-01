package com.thinkoff.clawwatch.billing

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * The 8 EUR/month paywall (petrus, Aug 1 2026), shown when the free managed
 * queries are spent and the user is neither subscribed nor BYOK.
 *
 * claudemm's offramp principle: never a dead end. Past the allowance the
 * user is offered BYOK (their own Anthropic key, unmetered by us, they pay
 * Anthropic directly) alongside the subscription — heavy users route
 * themselves off our invoice instead of being throttled.
 */
object PaywallDialog {
    fun show(activity: Activity, billing: ClawBillingManager) {
        val price = billing.product.value
            ?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()
            ?.formattedPrice ?: "€8/month"
        AlertDialog.Builder(activity)
            .setTitle("Your free queries are used")
            .setMessage(
                "You have used your ${QueryQuota.FREE_QUERIES} free ClawWatch queries.\n\n" +
                    "Continue with ClawWatch Pro ($price) for unlimited managed queries, " +
                    "or bring your own Anthropic API key — unmetered and free here, " +
                    "you pay Anthropic directly."
            )
            .setPositiveButton("Subscribe $price") { _, _ ->
                billing.launchPurchase(activity)
            }
            .setNeutralButton("Use my own key") { _, _ ->
                AlertDialog.Builder(activity)
                    .setTitle("Bring your own key")
                    .setMessage(
                        "Set your Anthropic API key with the ClawWatch admin tool " +
                            "(set_key.sh, or the admin page on your paired computer). " +
                            "It syncs to the watch automatically and all queries run " +
                            "on your own account."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
