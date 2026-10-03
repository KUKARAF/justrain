package page.osmosis.billing

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams

private const val TAG = "BillingPlugin"

// The one consumable "tip" product. This ID MUST match the managed product
// created in the Play Console exactly, or queries come back empty.
private const val PRODUCT_ID = "support_justrain"

/**
 * Optional one-time "tip" purchase via Google Play Billing. The app itself is
 * free; this just lets someone buy the dev a coffee. It is a CONSUMABLE
 * product, so it is consumed right after purchase and can be bought again.
 *
 * Billing callbacks are asynchronous, so the pending `tip` Invoke is held in
 * `pendingTip` and resolved/rejected from the PurchasesUpdatedListener.
 */
@TauriPlugin
class BillingPlugin(private val activity: Activity) : Plugin(activity) {
    private val main = Handler(Looper.getMainLooper())
    private var productDetails: ProductDetails? = null
    private var pendingTip: Invoke? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    resolveTip("done")
                } else {
                    purchases.forEach { handlePurchase(it) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> rejectTip("canceled")
            else -> rejectTip("billing error ${result.responseCode}: ${result.debugMessage}")
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    override fun load(webView: WebView) {
        super.load(webView)
        // Warm the connection so the first tip/get_price is fast. Failures here
        // are non-fatal — each command reconnects on demand.
        connect(null)
    }

    // ── connection ────────────────────────────────────────────────────────
    private fun connect(onReady: ((Boolean) -> Unit)?) {
        if (billingClient.isReady) { onReady?.invoke(true); return }
        try {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                    if (!ok) Log.w(TAG, "billing setup failed: ${result.debugMessage}")
                    onReady?.invoke(ok)
                }
                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "billing service disconnected")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "startConnection failed", e)
            onReady?.invoke(false)
        }
    }

    // ── product details ───────────────────────────────────────────────────
    private fun queryProduct(onResult: (ProductDetails?) -> Unit) {
        productDetails?.let { onResult(it); return }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(ProductType.INAPP)
                        .build()
                )
            )
            .build()
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            // PBL 8 wraps the response: fetched products are in productDetailsList
            // (unfetchable ones would be in queryResult.unfetchedProductList).
            val list = queryResult.productDetailsList
            if (result.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                productDetails = list[0]
                onResult(productDetails)
            } else {
                Log.w(TAG, "queryProductDetails failed: ${result.responseCode} ${result.debugMessage}")
                onResult(null)
            }
        }
    }

    // ── purchase handling ─────────────────────────────────────────────────
    private fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                // Consume so the tip can be given again; consuming also
                // acknowledges, which is required within 3 days.
                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.consumeAsync(consumeParams) { result, _ ->
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(TAG, "consume failed: ${result.debugMessage}")
                    }
                    resolveTip("purchased")
                }
            }
            Purchase.PurchaseState.PENDING -> resolveTip("pending")
            else -> resolveTip("done")
        }
    }

    private fun resolveTip(status: String) {
        pendingTip?.let { it.resolve(JSObject().put("status", status)); pendingTip = null }
    }

    private fun rejectTip(message: String) {
        pendingTip?.let { it.reject(message); pendingTip = null }
    }

    // ── commands ──────────────────────────────────────────────────────────
    @Command
    fun getPrice(invoke: Invoke) {
        connect { ok ->
            if (!ok) { invoke.reject("Google Play Billing unavailable"); return@connect }
            queryProduct { pd ->
                val price = pd?.oneTimePurchaseOfferDetails?.formattedPrice
                if (price == null) {
                    invoke.reject("tip product not available")
                } else {
                    invoke.resolve(JSObject().put("price", price))
                }
            }
        }
    }

    @Command
    fun tip(invoke: Invoke) {
        if (pendingTip != null) { invoke.reject("a purchase is already in progress"); return }
        pendingTip = invoke
        connect { ok ->
            if (!ok) { rejectTip("Google Play Billing unavailable"); return@connect }
            queryProduct { pd ->
                if (pd == null) { rejectTip("tip product not available"); return@queryProduct }
                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pd)
                    .build()
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams))
                    .build()
                // launchBillingFlow must run on the UI thread.
                main.post {
                    val result = billingClient.launchBillingFlow(activity, flowParams)
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        rejectTip("couldn't open purchase: ${result.debugMessage}")
                    }
                    // success path resolves via purchasesListener
                }
            }
        }
    }
}
