# nopayn-sdk (Kotlin/Java)

Official Kotlin SDK for the [NoPayn Payment Gateway](https://costplus.io). 100% Java-interoperable. Simplifies the HPP (Hosted Payment Page) redirect flow, HMAC payload signing, and webhook verification.

[![CI](https://github.com/NoPayn/Java-Kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/NoPayn/Java-Kotlin/actions/workflows/ci.yml)

## Features

- **Kotlin-first, Java-friendly** — data classes, coroutines, null safety; fully usable from Java
- **kotlinx.serialization** — no Gson/Jackson dependency; automatic snake_case ↔ camelCase mapping
- **HMAC-SHA256** signature generation and constant-time verification
- **Webhook verification** — parse + API-based order status confirmation (as recommended by NoPayn)
- **Suspend functions** for non-blocking IO via Kotlin coroutines
- **Injectable `HttpClient`** — easy testing with mock transports
- Targets **Java 17+**

## Requirements

- JDK 17 or later
- A NoPayn / Cost+ merchant account — [manage.nopayn.io](https://manage.nopayn.io/)

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.nopayn:nopayn-sdk:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.nopayn:nopayn-sdk:1.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.nopayn</groupId>
    <artifactId>nopayn-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### Kotlin

```kotlin
import io.nopayn.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val nopayn = NoPaynClient(
        NoPaynConfig(
            apiKey = "your-api-key",
            merchantId = "your-project",
        )
    )

    // Create a payment and get the HPP redirect URL
    val result = nopayn.generatePaymentUrl(
        CreateOrderParams(
            amount = 1295,            // €12.95 in cents
            currency = "EUR",
            merchantOrderId = "ORDER-001",
            description = "Premium Widget",
            returnUrl = "https://shop.example.com/success",
            failureUrl = "https://shop.example.com/failure",
            webhookUrl = "https://shop.example.com/webhook",
            locale = "en-GB",
            expirationPeriod = "PT30M",
        )
    )

    println(result.orderUrl)    // HPP URL
    println(result.paymentUrl)  // Direct payment method URL
    println(result.signature)   // HMAC-SHA256 signature
}
```

### Java

```java
import io.nopayn.*;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.GlobalScope;

public class Example {
    public static void main(String[] args) throws Exception {
        NoPaynClient client = new NoPaynClient(
            new NoPaynConfig("your-api-key", "your-project", "https://api.nopayn.co.uk")
        );

        // Synchronous call from Java using runBlocking
        Order order = BuildersKt.runBlocking(
            Dispatchers.getIO(),
            (scope, continuation) -> client.createOrder(
                new CreateOrderParams(
                    1295, "EUR", "ORDER-001", "Premium Widget",
                    "https://shop.example.com/success",
                    "https://shop.example.com/failure",
                    "https://shop.example.com/webhook",
                    "en-GB", null, "PT30M"
                ),
                continuation
            )
        );

        System.out.println("Order ID: " + order.getId());
        System.out.println("Order URL: " + order.getOrderUrl());

        // Signature utilities work synchronously
        String sig = client.generateSignature(1295, "EUR", order.getId());
        boolean valid = client.verifySignature(1295, "EUR", order.getId(), sig);
        System.out.println("Signature valid: " + valid);
    }
}
```

### Webhook Handling (Kotlin)

```kotlin
// In your HTTP handler (Ktor, Spring, etc.)
val rawBody: String = request.body()
val verified = nopayn.verifyWebhook(rawBody)

println(verified.order.status)  // "completed", "cancelled", etc.
println(verified.isFinal)       // true when the order won't change

if (verified.order.status == "completed") {
    // Fulfil the order
}
```

## API Reference

### `NoPaynClient(config, httpClient?)`

| Parameter | Type | Required | Default |
|-----------|------|----------|---------|
| `config.apiKey` | `String` | Yes | — |
| `config.merchantId` | `String` | Yes | — |
| `config.baseUrl` | `String` | No | `https://api.nopayn.co.uk` |
| `httpClient` | `java.net.http.HttpClient` | No | Default client |

### `client.createOrder(params): Order` (suspend)

Creates an order via `POST /v1/orders/`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `amount` | `Int` | Yes | Amount in smallest currency unit (cents) |
| `currency` | `String` | Yes | ISO 4217 code (`EUR`, `GBP`, `USD`, `NOK`, `SEK`) |
| `merchantOrderId` | `String?` | No | Your internal order reference |
| `description` | `String?` | No | Order description |
| `returnUrl` | `String?` | No | Redirect after successful payment |
| `failureUrl` | `String?` | No | Redirect on cancel/expiry/error |
| `webhookUrl` | `String?` | No | Async status-change notifications |
| `locale` | `String?` | No | HPP language (`en-GB`, `de-DE`, `nl-NL`, etc.) |
| `paymentMethods` | `List<String>?` | No | Filter HPP methods |
| `expirationPeriod` | `String?` | No | ISO 8601 duration (`PT30M`) |

**Available payment methods:** `credit-card`, `apple-pay`, `google-pay`, `vipps-mobilepay`

### `client.getOrder(orderId): Order` (suspend)

Fetches order details via `GET /v1/orders/{id}/`.

### `client.createRefund(orderId, amount, description?): Refund` (suspend)

Issues a full or partial refund via `POST /v1/orders/{id}/refunds/`.

### `client.generatePaymentUrl(params): PaymentUrlResult` (suspend)

Convenience method that creates an order and returns:

```kotlin
PaymentUrlResult(
    orderId: String,     // NoPayn order UUID
    orderUrl: String,    // HPP URL
    paymentUrl: String?, // Direct payment URL (first transaction)
    signature: String,   // HMAC-SHA256 of amount:currency:orderId
    order: Order,        // Full order object
)
```

### `client.generateSignature(amount, currency, orderId): String`

Generates an HMAC-SHA256 hex signature. The canonical message is `$amount:$currency:$orderId`, signed with the API key.

### `client.verifySignature(amount, currency, orderId, signature): Boolean`

Constant-time verification of an HMAC-SHA256 signature. Returns `true` if valid.

### `client.verifyWebhook(rawBody): VerifiedWebhook` (suspend)

Parses the webhook body, then calls `GET /v1/orders/{id}/` to verify the actual status. Returns:

```kotlin
VerifiedWebhook(
    orderId: String,  // NoPayn order UUID from the webhook
    order: Order,     // Verified via API
    isFinal: Boolean, // true for completed/cancelled/expired/error
)
```

### `client.parseWebhookBody(rawBody): WebhookPayload`

Parses and validates a webhook body without calling the API.

### Standalone HMAC Utilities

```kotlin
import io.nopayn.NoPaynSignature

val sig = NoPaynSignature.generate("your-api-key", 1295, "EUR", "order-uuid")
val ok  = NoPaynSignature.verify("your-api-key", 1295, "EUR", "order-uuid", sig)
```

From Java:

```java
String sig = NoPaynSignature.generate("your-api-key", 1295, "EUR", "order-uuid");
boolean ok = NoPaynSignature.verify("your-api-key", 1295, "EUR", "order-uuid", sig);
```

## Error Handling

```kotlin
import io.nopayn.*

try {
    nopayn.createOrder(CreateOrderParams(amount = 100, currency = "EUR"))
} catch (e: ApiException) {
    println(e.statusCode)  // 401, 400, etc.
    println(e.errorBody)   // Raw API error response
} catch (e: NoPaynException) {
    println(e.message)     // Network or parsing error
}
```

## Order Statuses

| Status | Final? | Description |
|--------|--------|-------------|
| `new` | No | Order created |
| `processing` | No | Payment in progress |
| `completed` | Yes | Payment successful — deliver the goods |
| `cancelled` | Yes | Payment cancelled by customer |
| `expired` | Yes | Payment link timed out |
| `error` | Yes | Technical failure |

## Webhook Best Practices

1. **Always verify via the API** — the webhook payload only contains the order ID, never the status. The SDK's `verifyWebhook()` does this automatically.
2. **Return HTTP 200** to acknowledge receipt. Any other code triggers up to 10 retries (2 minutes apart).
3. **Implement a backup poller** — for orders older than 10 minutes that haven't reached a final status, poll `getOrder()` as a safety net.
4. **Be idempotent** — you may receive the same webhook multiple times.

## Demo Merchant Site

A Docker-based demo app (Ktor) is included for testing the full payment flow.

### Run with Docker Compose

```bash
cd demo

# Create a .env file
cat > .env << EOF
NOPAYN_API_KEY=your-api-key
NOPAYN_MERCHANT_ID=your-merchant-id
PUBLIC_URL=http://localhost:3000
EOF

docker compose up --build
```

Open [http://localhost:3000](http://localhost:3000) to see the demo checkout page.

## Testing

```bash
gradle test           # Run all tests
gradle test --info    # With verbose output
```

## Test Cards

Use these cards in NoPayn test mode (project status `active-testing`):

| Card | Number | Notes |
|------|--------|-------|
| Visa (frictionless) | `4018 8100 0010 0036` | No 3DS challenge |
| Mastercard (frictionless) | `5420 7110 0021 0016` | No 3DS challenge |
| Visa (3DS) | `4018 8100 0015 0015` | OTP: `0101` (success), `3333` (fail) |
| Mastercard (3DS) | `5299 9100 1000 0015` | OTP: `4445` (success), `9999` (fail) |

Use any future expiry date and any 3-digit CVC.

## License

MIT — see [LICENSE](LICENSE).

## Support

- NoPayn API docs: [dev.nopayn.io](https://dev.nopayn.io/)
- Merchant portal: [manage.nopayn.io](https://manage.nopayn.io/)
- Developer: [Cost+](https://costplus.io)
