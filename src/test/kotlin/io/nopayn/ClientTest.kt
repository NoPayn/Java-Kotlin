package io.nopayn

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * Unit tests for [NoPaynClient] using a mock [HttpClient].
 */
class ClientTest {

    private val config = NoPaynConfig(
        apiKey = "test-key-abc",
        merchantId = "merchant-123",
        baseUrl = "https://api.test.nopayn.co.uk",
    )

    private val sampleOrderJson = """
        {
          "id": "order-uuid-001",
          "amount": 1295,
          "currency": "EUR",
          "status": "new",
          "description": "Test order",
          "merchant_order_id": "SHOP-001",
          "return_url": "https://shop.test/success",
          "failure_url": "https://shop.test/failure",
          "order_url": "https://pay.test/order-uuid-001",
          "created": "2026-01-15T10:00:00Z",
          "modified": "2026-01-15T10:00:00Z",
          "transactions": [
            {
              "id": "txn-uuid-001",
              "amount": 1295,
              "currency": "EUR",
              "payment_method": "credit-card",
              "payment_url": "https://pay.test/txn-uuid-001",
              "status": "new",
              "created": "2026-01-15T10:00:00Z",
              "modified": "2026-01-15T10:00:00Z",
              "expiration_period": "PT30M"
            }
          ]
        }
    """.trimIndent()

    private fun mockHttpClient(responseBody: String, statusCode: Int = 200): HttpClient {
        val mockResponse = object : HttpResponse<String> {
            override fun statusCode(): Int = statusCode
            override fun body(): String = responseBody
            override fun headers(): java.net.http.HttpHeaders =
                java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
            override fun request(): HttpRequest = throw UnsupportedOperationException()
            override fun previousResponse() = java.util.Optional.empty<HttpResponse<String>>()
            override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()
            override fun uri() = java.net.URI("https://api.test.nopayn.co.uk")
            override fun version() = HttpClient.Version.HTTP_1_1
        }

        return object : HttpClient() {
            override fun cookieHandler() = java.util.Optional.empty<java.net.CookieHandler>()
            override fun connectTimeout() = java.util.Optional.empty<java.time.Duration>()
            override fun followRedirects() = Redirect.NEVER
            override fun proxy() = java.util.Optional.empty<java.net.ProxySelector>()
            override fun sslContext(): SSLContext = SSLContext.getDefault()
            override fun sslParameters(): SSLParameters = SSLParameters()
            override fun authenticator() = java.util.Optional.empty<java.net.Authenticator>()
            override fun version() = Version.HTTP_1_1
            override fun executor() = java.util.Optional.empty<java.util.concurrent.Executor>()

            override fun <T> send(
                request: HttpRequest,
                responseBodyHandler: HttpResponse.BodyHandler<T>,
            ): HttpResponse<T> = throw UnsupportedOperationException()

            @Suppress("UNCHECKED_CAST")
            override fun <T> sendAsync(
                request: HttpRequest,
                responseBodyHandler: HttpResponse.BodyHandler<T>,
            ): CompletableFuture<HttpResponse<T>> =
                CompletableFuture.completedFuture(mockResponse as HttpResponse<T>)

            override fun <T> sendAsync(
                request: HttpRequest,
                responseBodyHandler: HttpResponse.BodyHandler<T>,
                pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
            ): CompletableFuture<HttpResponse<T>> =
                sendAsync(request, responseBodyHandler)

            override fun newWebSocketBuilder(): java.net.http.WebSocket.Builder =
                throw UnsupportedOperationException()
        }
    }

    @Test
    fun `createOrder deserializes response`() = runTest {
        val client = NoPaynClient(config, mockHttpClient(sampleOrderJson))
        val order = client.createOrder(
            CreateOrderParams(amount = 1295, currency = "EUR", description = "Test order")
        )

        assertEquals("order-uuid-001", order.id)
        assertEquals(1295, order.amount)
        assertEquals("EUR", order.currency)
        assertEquals("new", order.status)
        assertEquals("Test order", order.description)
        assertEquals("SHOP-001", order.merchantOrderId)
        assertEquals("https://pay.test/order-uuid-001", order.orderUrl)
        assertEquals(1, order.transactions.size)
        assertEquals("credit-card", order.transactions[0].paymentMethod)
        assertEquals("https://pay.test/txn-uuid-001", order.transactions[0].paymentUrl)
        assertEquals("PT30M", order.transactions[0].expirationPeriod)
    }

    @Test
    fun `getOrder deserializes response`() = runTest {
        val client = NoPaynClient(config, mockHttpClient(sampleOrderJson))
        val order = client.getOrder("order-uuid-001")
        assertEquals("order-uuid-001", order.id)
        assertEquals("new", order.status)
    }

    @Test
    fun `createRefund deserializes response`() = runTest {
        val refundJson = """{"id": "refund-001", "amount": 500, "status": "pending"}"""
        val client = NoPaynClient(config, mockHttpClient(refundJson))
        val refund = client.createRefund("order-uuid-001", 500, "Customer refund")

        assertEquals("refund-001", refund.id)
        assertEquals(500, refund.amount)
        assertEquals("pending", refund.status)
    }

    @Test
    fun `generatePaymentUrl returns signature and urls`() = runTest {
        val client = NoPaynClient(config, mockHttpClient(sampleOrderJson))
        val result = client.generatePaymentUrl(
            CreateOrderParams(amount = 1295, currency = "EUR")
        )

        assertEquals("order-uuid-001", result.orderId)
        assertEquals("https://pay.test/order-uuid-001", result.orderUrl)
        assertEquals("https://pay.test/txn-uuid-001", result.paymentUrl)
        assertTrue(result.signature.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(
            NoPaynSignature.verify("test-key-abc", 1295, "EUR", "order-uuid-001", result.signature)
        )
    }

    @Test
    fun `API error throws ApiException`() = runTest {
        val errorJson = """{"error": {"value": "Unauthorized"}}"""
        val client = NoPaynClient(config, mockHttpClient(errorJson, 401))

        val ex = assertThrows<ApiException> {
            client.getOrder("bad-id")
        }
        assertEquals(401, ex.statusCode)
        assertTrue(ex.message!!.contains("Unauthorized"))
        assertNotNull(ex.errorBody)
    }

    @Test
    fun `API error with detail field`() = runTest {
        val errorJson = """{"detail": "Not found"}"""
        val client = NoPaynClient(config, mockHttpClient(errorJson, 404))

        val ex = assertThrows<ApiException> {
            client.getOrder("missing")
        }
        assertEquals(404, ex.statusCode)
        assertTrue(ex.message!!.contains("Not found"))
    }

    @Test
    fun `constructor rejects blank apiKey`() {
        assertThrows<IllegalArgumentException> {
            NoPaynClient(NoPaynConfig(apiKey = "", merchantId = "m"))
        }
    }

    @Test
    fun `constructor rejects blank merchantId`() {
        assertThrows<IllegalArgumentException> {
            NoPaynClient(NoPaynConfig(apiKey = "k", merchantId = ""))
        }
    }

    @Test
    fun `generateSignature uses apiKey as secret`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        val sig = client.generateSignature(100, "EUR", "order-1")
        assertTrue(NoPaynSignature.verify("test-key-abc", 100, "EUR", "order-1", sig))
    }

    @Test
    fun `verifySignature validates correctly`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        val sig = NoPaynSignature.generate("test-key-abc", 500, "GBP", "order-2")
        assertTrue(client.verifySignature(500, "GBP", "order-2", sig))
        assertFalse(client.verifySignature(500, "GBP", "order-2", "tampered"))
    }

    // ── captureTransaction ───────────────────────────────────────────────────

    @Test
    fun `captureTransaction deserializes response`() = runTest {
        val txnJson = """
            {
              "id": "txn-uuid-001",
              "amount": 1295,
              "currency": "EUR",
              "payment_method": "credit-card",
              "status": "captured",
              "created": "2026-01-15T10:00:00Z",
              "modified": "2026-01-15T10:05:00Z"
            }
        """.trimIndent()

        val client = NoPaynClient(config, mockHttpClient(txnJson))
        val txn = client.captureTransaction("order-uuid-001", "txn-uuid-001")

        assertEquals("txn-uuid-001", txn.id)
        assertEquals(1295, txn.amount)
        assertEquals("EUR", txn.currency)
        assertEquals("credit-card", txn.paymentMethod)
        assertEquals("captured", txn.status)
    }

    // ── voidTransaction ──────────────────────────────────────────────────────

    @Test
    fun `voidTransaction deserializes response`() = runTest {
        val txnJson = """
            {
              "id": "txn-uuid-002",
              "amount": 500,
              "currency": "EUR",
              "payment_method": "credit-card",
              "status": "voided",
              "created": "2026-01-15T10:00:00Z",
              "modified": "2026-01-15T10:06:00Z"
            }
        """.trimIndent()

        val client = NoPaynClient(config, mockHttpClient(txnJson))
        val txn = client.voidTransaction("order-uuid-001", "txn-uuid-002", 500, "Customer request")

        assertEquals("txn-uuid-002", txn.id)
        assertEquals(500, txn.amount)
        assertEquals("voided", txn.status)
    }

    @Test
    fun `voidTransaction works without description`() = runTest {
        val txnJson = """
            {
              "id": "txn-uuid-003",
              "amount": 300,
              "currency": "GBP",
              "status": "voided",
              "created": "2026-01-15T10:00:00Z",
              "modified": "2026-01-15T10:07:00Z"
            }
        """.trimIndent()

        val client = NoPaynClient(config, mockHttpClient(txnJson))
        val txn = client.voidTransaction("order-uuid-001", "txn-uuid-003", 300)

        assertEquals("txn-uuid-003", txn.id)
        assertEquals("voided", txn.status)
    }

    // ── createOrder with orderLines ──────────────────────────────────────────

    @Test
    fun `createOrder with orderLines deserializes response`() = runTest {
        val client = NoPaynClient(config, mockHttpClient(sampleOrderJson))
        val order = client.createOrder(
            CreateOrderParams(
                amount = 1295,
                currency = "EUR",
                description = "Test order",
                orderLines = listOf(
                    OrderLine(
                        type = "physical",
                        name = "Widget",
                        quantity = 2,
                        amount = 500,
                        currency = "EUR",
                        vatPercentage = 2100,
                        merchantOrderLineId = "LINE-001",
                    ),
                    OrderLine(
                        type = "shipping_fee",
                        name = "Standard shipping",
                        quantity = 1,
                        amount = 295,
                        currency = "EUR",
                    ),
                ),
                customer = mapOf("email" to "test@example.com", "first_name" to "Jane"),
            )
        )

        assertEquals("order-uuid-001", order.id)
        assertEquals(1295, order.amount)
        assertEquals("EUR", order.currency)
    }

    // ── OrderLine serialization ──────────────────────────────────────────────

    @Test
    fun `OrderLine serializes to JSON with snake_case fields`() {
        val line = OrderLine(
            type = "physical",
            name = "Test Item",
            quantity = 3,
            amount = 999,
            currency = "EUR",
            vatPercentage = 2100,
            merchantOrderLineId = "ITEM-42",
        )

        val jsonFormat = Json { encodeDefaults = true }
        val jsonStr = jsonFormat.encodeToString(line)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals("physical", obj["type"]?.jsonPrimitive?.content)
        assertEquals("Test Item", obj["name"]?.jsonPrimitive?.content)
        assertEquals(3, obj["quantity"]?.jsonPrimitive?.int)
        assertEquals(999, obj["amount"]?.jsonPrimitive?.int)
        assertEquals("EUR", obj["currency"]?.jsonPrimitive?.content)
        assertEquals(2100, obj["vat_percentage"]?.jsonPrimitive?.int)
        assertEquals("ITEM-42", obj["merchant_order_line_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OrderLine serializes without optional fields when null`() {
        val line = OrderLine(
            type = "discount",
            name = "10% off",
            quantity = 1,
            amount = -100,
            currency = "EUR",
        )

        val jsonFormat = Json { encodeDefaults = false }
        val jsonStr = jsonFormat.encodeToString(line)
        val obj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals("discount", obj["type"]?.jsonPrimitive?.content)
        assertNull(obj["vat_percentage"])
        assertNull(obj["merchant_order_line_id"])
    }

    @Test
    fun `OrderLine round-trip serialization`() {
        val original = OrderLine(
            type = "physical",
            name = "Round Trip Item",
            quantity = 5,
            amount = 1250,
            currency = "GBP",
            vatPercentage = 2000,
            merchantOrderLineId = "RT-001",
        )

        val jsonFormat = Json { encodeDefaults = true }
        val jsonStr = jsonFormat.encodeToString(original)
        val restored = jsonFormat.decodeFromString<OrderLine>(jsonStr)

        assertEquals(original, restored)
    }
}
