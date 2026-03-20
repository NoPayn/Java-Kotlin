package io.nopayn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client configuration.
 *
 * @property apiKey  NoPayn API key (used for HTTP Basic auth and HMAC signing).
 * @property merchantId  Your merchant/project ID from the NoPayn dashboard.
 * @property baseUrl  API base URL. Defaults to `https://api.nopayn.co.uk`.
 */
public data class NoPaynConfig(
    val apiKey: String,
    val merchantId: String,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    internal companion object {
        internal const val DEFAULT_BASE_URL: String = "https://api.nopayn.co.uk"
    }
}

/**
 * Parameters for creating a new order via `POST /v1/orders/`.
 */
public data class CreateOrderParams(
    /** Amount in smallest currency unit (e.g. cents — 12.95 EUR = 1295). */
    val amount: Int,
    /** ISO 4217 currency code (EUR, GBP, USD, NOK, SEK, etc.). */
    val currency: String,
    /** Your internal order reference. */
    val merchantOrderId: String? = null,
    /** Human-readable order description. */
    val description: String? = null,
    /** Redirect URL after successful payment. */
    val returnUrl: String? = null,
    /** Redirect URL on cancel, expiry, or error. */
    val failureUrl: String? = null,
    /** URL for asynchronous status-change webhooks. */
    val webhookUrl: String? = null,
    /** HPP locale (en-GB, de-DE, nl-NL, nl-BE, fr-BE, sv-SE, no-NO, da-DK). */
    val locale: String? = null,
    /** Restrict HPP to specific payment methods. */
    val paymentMethods: List<String>? = null,
    /** ISO 8601 duration before the payment link expires (e.g. PT30M). */
    val expirationPeriod: String? = null,
)

@Serializable
public data class Transaction(
    val id: String,
    val amount: Int,
    val currency: String,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_url") val paymentUrl: String? = null,
    val status: String,
    val created: String,
    val modified: String,
    @SerialName("expiration_period") val expirationPeriod: String? = null,
)

@Serializable
public data class Order(
    val id: String,
    val amount: Int,
    val currency: String,
    val status: String,
    val description: String? = null,
    @SerialName("merchant_order_id") val merchantOrderId: String? = null,
    @SerialName("return_url") val returnUrl: String? = null,
    @SerialName("failure_url") val failureUrl: String? = null,
    @SerialName("order_url") val orderUrl: String? = null,
    val created: String,
    val modified: String,
    val completed: String? = null,
    val transactions: List<Transaction> = emptyList(),
)

@Serializable
public data class Refund(
    val id: String,
    val amount: Int,
    val status: String,
)

@Serializable
public data class WebhookPayload(
    val event: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("project_id") val projectId: String? = null,
)

/**
 * Result of [NoPaynClient.generatePaymentUrl].
 */
public data class PaymentUrlResult(
    /** NoPayn order UUID. */
    val orderId: String,
    /** HPP URL (customer picks payment method here). */
    val orderUrl: String,
    /** Direct payment URL for the first transaction (if available). */
    val paymentUrl: String?,
    /** HMAC-SHA256 signature of `amount:currency:orderId`. */
    val signature: String,
    /** Full order object from the API. */
    val order: Order,
)

/**
 * Result of [NoPaynClient.verifyWebhook].
 */
public data class VerifiedWebhook(
    /** NoPayn order UUID from the webhook. */
    val orderId: String,
    /** Order details fetched and verified via the API. */
    val order: Order,
    /** Whether the order has reached a terminal status. */
    val isFinal: Boolean,
)
