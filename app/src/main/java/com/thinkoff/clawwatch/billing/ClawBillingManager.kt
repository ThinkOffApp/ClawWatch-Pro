package com.thinkoff.clawwatch.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play Billing for the ClawWatch Pro subscription (petrus, Aug 1 2026:
 * 8 EUR/month after 3 free managed queries; BYOK stays unmetered).
 *
 * Ported from thinkoff-android's BillingManager with the product model
 * switched from INAPP consumable credits to a SUBS product: subscriptions
 * are ACKNOWLEDGED, never consumed, and the Play Console must carry the
 * product with a base plan + offer before queryProductDetails can see it
 * (claudemm's port notes). Server-side verification rides the Phase 3
 * contract: POST /billing/verify { purchaseToken, productId } — until the
 * backend lands, acknowledged-locally is treated as entitled, which is
 * acceptable for a subscription because Play itself enforces the renewal.
 */
class ClawBillingManager(
    context: Context,
) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        private const val TAG = "ClawBilling"
        const val SUB_PRO_MONTHLY = "clawwatch_pro_monthly" // 8 EUR/month
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _subscribed = MutableStateFlow(false)
    val subscribed: StateFlow<Boolean> = _subscribed.asStateFlow()

    private val _product = MutableStateFlow<ProductDetails?>(null)
    val product: StateFlow<ProductDetails?> = _product.asStateFlow()

    /** Set by the host to relay purchase tokens to the backend when it exists. */
    var onVerify: ((purchaseToken: String, productId: String) -> Unit)? = null

    fun connect() {
        if (!billingClient.isReady) billingClient.startConnection(this)
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "setup failed: ${result.debugMessage}")
            return
        }
        queryProduct()
        restorePurchases()
    }

    override fun onBillingServiceDisconnected() {
        // Reconnect lazily on next launchPurchase/connect call.
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUB_PRO_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        billingClient.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _product.value = details.firstOrNull()
            } else {
                Log.w(TAG, "product query failed: ${result.debugMessage}")
            }
        }
    }

    /** Re-derive entitlement from Play's own purchase records (restore). */
    fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val active = purchases.any {
                it.products.contains(SUB_PRO_MONTHLY) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            _subscribed.value = active
            purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
                .forEach { acknowledge(it) }
        }
    }

    fun launchPurchase(activity: Activity): Boolean {
        val details = _product.value ?: run { connect(); return false }
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        return billingClient.launchBillingFlow(activity, flow).responseCode ==
            BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
            if (p.products.contains(SUB_PRO_MONTHLY)) {
                _subscribed.value = true
                if (!p.isAcknowledged) acknowledge(p)
                onVerify?.invoke(p.purchaseToken, SUB_PRO_MONTHLY)
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.debugMessage}")
            }
        }
    }
}
