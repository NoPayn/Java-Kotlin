package io.nopayn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class WebhookTest {

    private val config = NoPaynConfig(
        apiKey = "webhook-test-key",
        merchantId = "merchant-456",
        baseUrl = "https://api.test.nopayn.co.uk",
    )

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
    fun `parseWebhookBody parses valid payload`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        val payload = client.parseWebhookBody(
            """{"event":"status_changed","order_id":"order-123","project_id":"proj-456"}"""
        )

        assertEquals("status_changed", payload.event)
        assertEquals("order-123", payload.orderId)
        assertEquals("proj-456", payload.projectId)
    }

    @Test
    fun `parseWebhookBody works without project_id`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        val payload = client.parseWebhookBody(
            """{"event":"status_changed","order_id":"order-789"}"""
        )

        assertEquals("order-789", payload.orderId)
        assertNull(payload.projectId)
    }

    @Test
    fun `parseWebhookBody throws on invalid JSON`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        assertThrows<WebhookException> {
            client.parseWebhookBody("not valid json{{{")
        }
    }

    @Test
    fun `parseWebhookBody throws on missing order_id`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        assertThrows<WebhookException> {
            client.parseWebhookBody("""{"event":"status_changed"}""")
        }
    }

    @Test
    fun `parseWebhookBody throws on empty order_id`() {
        val client = NoPaynClient(config, mockHttpClient("{}"))
        assertThrows<WebhookException> {
            client.parseWebhookBody("""{"event":"status_changed","order_id":""}""")
        }
    }

    @Test
    fun `verifyWebhook returns verified order with final status`() = runTest {
        val orderJson = """
            {
              "id": "order-abc",
              "amount": 1000,
              "currency": "EUR",
              "status": "completed",
              "created": "2026-01-15T10:00:00Z",
              "modified": "2026-01-15T10:05:00Z",
              "completed": "2026-01-15T10:05:00Z",
              "transactions": []
            }
        """.trimIndent()

        val client = NoPaynClient(config, mockHttpClient(orderJson))
        val result = client.verifyWebhook(
            """{"event":"status_changed","order_id":"order-abc"}"""
        )

        assertEquals("order-abc", result.orderId)
        assertEquals("completed", result.order.status)
        assertTrue(result.isFinal)
    }

    @Test
    fun `verifyWebhook returns non-final for processing status`() = runTest {
        val orderJson = """
            {
              "id": "order-def",
              "amount": 500,
              "currency": "GBP",
              "status": "processing",
              "created": "2026-01-15T10:00:00Z",
              "modified": "2026-01-15T10:01:00Z",
              "transactions": []
            }
        """.trimIndent()

        val client = NoPaynClient(config, mockHttpClient(orderJson))
        val result = client.verifyWebhook(
            """{"event":"status_changed","order_id":"order-def"}"""
        )

        assertEquals("order-def", result.orderId)
        assertEquals("processing", result.order.status)
        assertFalse(result.isFinal)
    }

    @Test
    fun `verifyWebhook isFinal for all terminal statuses`() = runTest {
        for (status in listOf("completed", "cancelled", "expired", "error")) {
            val orderJson = """
                {
                  "id": "order-$status",
                  "amount": 100,
                  "currency": "EUR",
                  "status": "$status",
                  "created": "2026-01-15T10:00:00Z",
                  "modified": "2026-01-15T10:00:00Z",
                  "transactions": []
                }
            """.trimIndent()

            val client = NoPaynClient(config, mockHttpClient(orderJson))
            val result = client.verifyWebhook(
                """{"event":"status_changed","order_id":"order-$status"}"""
            )
            assertTrue(result.isFinal, "Expected isFinal=true for status '$status'")
        }
    }
}
