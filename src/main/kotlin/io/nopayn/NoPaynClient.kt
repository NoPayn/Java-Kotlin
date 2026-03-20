package io.nopayn

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val FINAL_STATUSES = setOf("completed", "cancelled", "expired", "error")

/**
 * Client for the NoPayn Payment Gateway API.
 *
 * All IO methods are `suspend` functions suitable for Kotlin coroutines.
 * For Java interop, use the `*Blocking` companion wrappers or call
 * from `runBlocking`.
 *
 * @param config Client configuration containing API key, merchant ID, and optional base URL.
 * @param httpClient Optional [HttpClient] instance for custom transport / testing.
 */
public class NoPaynClient @JvmOverloads constructor(
    config: NoPaynConfig,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val apiKey: String = config.apiKey.also {
        require(it.isNotBlank()) { "apiKey is required" }
    }

    @Suppress("unused")
    private val merchantId: String = config.merchantId.also {
        require(it.isNotBlank()) { "merchantId is required" }
    }

    private val baseUrl: String = config.baseUrl.trimEnd('/')

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // ── Order API ────────────────────────────────────────────────────────────

    /**
     * Create an order via `POST /v1/orders/`.
     * Returns the full order object including `orderUrl` (the HPP link).
     */
    public suspend fun createOrder(params: CreateOrderParams): Order {
        val body = buildOrderBody(params)
        val data = request("POST", "/v1/orders/", body)
        return json.decodeFromJsonElement(data)
    }

    /**
     * Fetch an existing order via `GET /v1/orders/{id}/`.
     */
    public suspend fun getOrder(orderId: String): Order {
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8)
        val data = request("GET", "/v1/orders/$encoded/")
        return json.decodeFromJsonElement(data)
    }

    /**
     * Issue a full or partial refund via `POST /v1/orders/{id}/refunds/`.
     */
    public suspend fun createRefund(
        orderId: String,
        amount: Int,
        description: String? = null,
    ): Refund {
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8)
        val body = buildJsonObject {
            put("amount", amount)
            if (description != null) put("description", description)
        }
        val data = request("POST", "/v1/orders/$encoded/refunds/", body)
        return json.decodeFromJsonElement(data)
    }

    // ── HPP Redirect ─────────────────────────────────────────────────────────

    /**
     * Create an order and return the HPP redirect URL with an HMAC signature.
     *
     * The signature covers `amount:currency:orderId` so the merchant can later
     * verify that the return/callback parameters haven't been tampered with.
     */
    public suspend fun generatePaymentUrl(params: CreateOrderParams): PaymentUrlResult {
        val order = createOrder(params)
        val signature = NoPaynSignature.generate(apiKey, params.amount, params.currency, order.id)

        return PaymentUrlResult(
            orderId = order.id,
            orderUrl = order.orderUrl ?: "",
            paymentUrl = order.transactions.firstOrNull()?.paymentUrl,
            signature = signature,
            order = order,
        )
    }

    // ── HMAC Signature Utilities ─────────────────────────────────────────────

    /**
     * Generate an HMAC-SHA256 hex signature for the given payment parameters.
     * Canonical message: `amount:currency:orderId`.
     */
    public fun generateSignature(amount: Int, currency: String, orderId: String): String =
        NoPaynSignature.generate(apiKey, amount, currency, orderId)

    /**
     * Constant-time verification of an HMAC-SHA256 signature.
     */
    public fun verifySignature(amount: Int, currency: String, orderId: String, signature: String): Boolean =
        NoPaynSignature.verify(apiKey, amount, currency, orderId, signature)

    // ── Webhook Handling ─────────────────────────────────────────────────────

    /**
     * Parse a raw webhook body into a typed payload.
     * Throws [WebhookException] if the body is invalid.
     */
    public fun parseWebhookBody(rawBody: String): WebhookPayload {
        val element: JsonElement = try {
            Json.parseToJsonElement(rawBody)
        } catch (_: Exception) {
            throw WebhookException("Invalid JSON in webhook body")
        }

        val obj = element.jsonObject
        val orderId = obj["order_id"]?.jsonPrimitive?.contentOrNull
        if (orderId.isNullOrBlank()) {
            throw WebhookException("Missing order_id in webhook payload")
        }

        return WebhookPayload(
            event = obj["event"]?.jsonPrimitive?.contentOrNull ?: "",
            orderId = orderId,
            projectId = obj["project_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * Full webhook verification: parse the body, then call the API to confirm
     * the actual order status. **Never trust the webhook payload alone.**
     *
     * Returns the verified order and whether it has reached a final status.
     */
    public suspend fun verifyWebhook(rawBody: String): VerifiedWebhook {
        val payload = parseWebhookBody(rawBody)
        val order = getOrder(payload.orderId)
        return VerifiedWebhook(
            orderId = payload.orderId,
            order = order,
            isFinal = order.status in FINAL_STATUSES,
        )
    }

    // ── Internal HTTP ────────────────────────────────────────────────────────

    internal suspend fun request(
        method: String,
        endpoint: String,
        body: JsonElement? = null,
    ): JsonElement {
        val url = "$baseUrl$endpoint"
        val credentials = Base64.getEncoder().encodeToString("$apiKey:".toByteArray(Charsets.UTF_8))

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Basic $credentials")
            .header("Accept", "application/json")

        if (body != null && method != "GET") {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        val httpRequest = builder.build()
        val response: HttpResponse<String> = try {
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: Exception) {
            throw NoPaynException("Network error: ${e.message}")
        }

        val text = response.body() ?: ""
        val data: JsonElement = try {
            if (text.isNotBlank()) Json.parseToJsonElement(text) else JsonObject(emptyMap())
        } catch (_: Exception) {
            throw NoPaynException("Invalid JSON response: ${text.take(200)}")
        }

        if (response.statusCode() !in 200..299) {
            val obj = data.jsonObject
            val nested = obj["error"]?.jsonObject
            val errorMsg = nested?.get("value")?.jsonPrimitive?.contentOrNull
                ?: nested?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["detail"]?.jsonPrimitive?.contentOrNull
                ?: "Unknown error"
            throw ApiException(response.statusCode(), errorMsg, text)
        }

        return data
    }

    private fun buildOrderBody(params: CreateOrderParams): JsonObject = buildJsonObject {
        put("amount", params.amount)
        put("currency", params.currency)
        params.merchantOrderId?.let { put("merchant_order_id", it) }
        params.description?.let { put("description", it) }
        params.returnUrl?.let { put("return_url", it) }
        params.failureUrl?.let { put("failure_url", it) }
        params.webhookUrl?.let { put("webhook_url", it) }
        params.locale?.let { put("locale", it) }
        params.paymentMethods?.let { methods ->
            putJsonArray("payment_methods") {
                methods.forEach { add(it) }
            }
        }
        params.expirationPeriod?.let { put("expiration_period", it) }
    }
}

@Suppress("UNCHECKED_CAST")
private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
    suspendCoroutine { cont ->
        whenComplete { value, throwable ->
            if (throwable != null) {
                cont.resumeWithException(throwable)
            } else {
                cont.resume(value)
            }
        }
    }
