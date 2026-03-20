package io.nopayn.demo

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.nopayn.*
import kotlinx.html.*

fun main() {
    val apiKey = System.getenv("NOPAYN_API_KEY") ?: ""
    val merchantId = System.getenv("NOPAYN_MERCHANT_ID") ?: ""
    val baseUrl = System.getenv("NOPAYN_BASE_URL") ?: "https://api.nopayn.co.uk"
    val publicUrl = System.getenv("PUBLIC_URL") ?: "http://localhost:3000"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3000

    if (apiKey.isBlank() || merchantId.isBlank()) {
        System.err.println("Set NOPAYN_API_KEY and NOPAYN_MERCHANT_ID environment variables")
        System.exit(1)
    }

    val nopayn = NoPaynClient(
        NoPaynConfig(apiKey = apiKey, merchantId = merchantId, baseUrl = baseUrl)
    )

    embeddedServer(Netty, port = port) {
        routing {
            get("/") { call.respondHtml(HttpStatusCode.OK) { productPage() } }

            post("/pay") {
                try {
                    val params = call.receiveParameters()
                    val amount = ((params["amount"]?.toDoubleOrNull() ?: 9.95) * 100).toInt()
                    val currency = params["currency"] ?: "EUR"
                    val orderId = "DEMO-${System.currentTimeMillis()}"

                    val result = nopayn.generatePaymentUrl(
                        CreateOrderParams(
                            amount = amount,
                            currency = currency,
                            merchantOrderId = orderId,
                            description = "Demo order $orderId",
                            returnUrl = "$publicUrl/success?order_id=$orderId",
                            failureUrl = "$publicUrl/failure?order_id=$orderId",
                            webhookUrl = "$publicUrl/webhook",
                            locale = params["locale"] ?: "en-GB",
                            expirationPeriod = "PT30M",
                        )
                    )

                    println("[PAY] Order ${result.orderId} created — signature: ${result.signature}")
                    call.respondRedirect(result.paymentUrl ?: result.orderUrl)
                } catch (e: Exception) {
                    System.err.println("[PAY] Error: $e")
                    call.respondHtml(HttpStatusCode.InternalServerError) {
                        failurePage("Payment Error", e.message ?: "Unknown error")
                    }
                }
            }

            get("/success") {
                val orderId = call.parameters["order_id"] ?: "(unknown)"
                call.respondHtml(HttpStatusCode.OK) { successPage(orderId) }
            }

            get("/failure") {
                val orderId = call.parameters["order_id"] ?: "(unknown)"
                call.respondHtml(HttpStatusCode.OK) {
                    failurePage("Payment Failed", "Order $orderId was not completed.")
                }
            }

            post("/webhook") {
                try {
                    val rawBody = call.receiveText()
                    val verified = nopayn.verifyWebhook(rawBody)
                    println("[WEBHOOK] Order ${verified.orderId} → ${verified.order.status} (final: ${verified.isFinal})")
                } catch (e: Exception) {
                    System.err.println("[WEBHOOK] Verification failed: $e")
                }
                call.respond(HttpStatusCode.OK)
            }
        }
    }.start(wait = true)
}

private val commonCss = """
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
        background: #f4f5f7; color: #1a1a2e; min-height: 100vh;
        display: flex; align-items: center; justify-content: center;
    }
    .card {
        background: #fff; border-radius: 16px; box-shadow: 0 4px 24px rgba(0,0,0,.08);
        max-width: 420px; width: 100%; padding: 40px; text-align: center;
    }
""".trimIndent()

private fun HTML.productPage() {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("NoPayn SDK — Merchant Demo")
        style {
            unsafe {
                raw(commonCss + """
                    .badge {
                        display: inline-block; background: #eef2ff; color: #4338ca;
                        font-size: 12px; font-weight: 600; letter-spacing: .5px; text-transform: uppercase;
                        padding: 4px 12px; border-radius: 999px; margin-bottom: 16px;
                    }
                    h1 { font-size: 24px; margin-bottom: 8px; }
                    .desc { color: #64748b; font-size: 14px; margin-bottom: 28px; line-height: 1.5; }
                    .product {
                        display: flex; align-items: center; gap: 16px;
                        background: #f8fafc; border-radius: 12px; padding: 16px; margin-bottom: 24px; text-align: left;
                    }
                    .product-icon {
                        width: 56px; height: 56px; background: linear-gradient(135deg, #6366f1, #8b5cf6);
                        border-radius: 12px; display: flex; align-items: center; justify-content: center;
                        font-size: 28px; flex-shrink: 0;
                    }
                    .product-info { flex: 1; }
                    .product-name { font-weight: 600; font-size: 16px; margin-bottom: 2px; }
                    .product-price { color: #6366f1; font-weight: 700; font-size: 20px; }
                    .form-row { display: flex; gap: 8px; margin-bottom: 16px; }
                    .form-group { flex: 1; text-align: left; }
                    .form-group label {
                        display: block; font-size: 12px; font-weight: 600; color: #64748b;
                        margin-bottom: 4px; text-transform: uppercase; letter-spacing: .3px;
                    }
                    select, input[type="number"] {
                        width: 100%; padding: 10px 12px; border: 1.5px solid #e2e8f0;
                        border-radius: 8px; font-size: 14px; background: #fff;
                        transition: border-color .15s;
                    }
                    select:focus, input:focus { outline: none; border-color: #6366f1; }
                    .btn {
                        display: block; width: 100%; padding: 14px;
                        background: linear-gradient(135deg, #6366f1, #8b5cf6);
                        color: #fff; border: none; border-radius: 10px;
                        font-size: 16px; font-weight: 600; cursor: pointer;
                        transition: transform .1s, box-shadow .15s;
                    }
                    .btn:hover { transform: translateY(-1px); box-shadow: 0 4px 16px rgba(99,102,241,.4); }
                    .btn:active { transform: translateY(0); }
                    .powered { margin-top: 24px; font-size: 12px; color: #94a3b8; }
                    .powered a { color: #6366f1; text-decoration: none; font-weight: 600; }
                """.trimIndent())
            }
        }
    }
    body {
        div("card") {
            div("badge") { +"SDK Demo" }
            h1 { +"NoPayn Checkout" }
            p("desc") {
                +"This merchant demo uses the "
                strong { +"nopayn-sdk (Kotlin)" }
                +" to create a payment and redirect to the Hosted Payment Page."
            }
            div("product") {
                div("product-icon") { +"\u26A1" }
                div("product-info") {
                    div("product-name") { +"NoPayn Test Product" }
                    div("product-price") {
                        id = "price-display"
                        +"\u20AC9.95"
                    }
                }
            }
            form(action = "/pay", method = FormMethod.post) {
                div("form-row") {
                    div("form-group") {
                        label { htmlFor = "amount"; +"Amount" }
                        input(type = InputType.number, name = "amount") {
                            id = "amount"; value = "9.95"; min = "0.01"; step = "0.01"
                        }
                    }
                    div("form-group") {
                        label { htmlFor = "currency"; +"Currency" }
                        select {
                            id = "currency"; name = "currency"
                            option { value = "EUR"; selected = true; +"EUR" }
                            option { value = "GBP"; +"GBP" }
                            option { value = "USD"; +"USD" }
                            option { value = "NOK"; +"NOK" }
                            option { value = "SEK"; +"SEK" }
                            option { value = "DKK"; +"DKK" }
                        }
                    }
                }
                div("form-row") {
                    div("form-group") {
                        label { htmlFor = "locale"; +"Language" }
                        select {
                            id = "locale"; name = "locale"
                            option { value = "en-GB"; selected = true; +"English" }
                            option { value = "de-DE"; +"Deutsch" }
                            option { value = "nl-NL"; +"Nederlands" }
                            option { value = "sv-SE"; +"Svenska" }
                            option { value = "no-NO"; +"Norsk" }
                            option { value = "da-DK"; +"Dansk" }
                        }
                    }
                }
                button(type = ButtonType.submit, classes = "btn") { +"Pay with NoPayn" }
            }
            p("powered") {
                +"Powered by "
                a(href = "https://costplus.io", target = "_blank") { +"Cost+" }
                +" \u00B7 "
                a(href = "https://github.com/NoPayn/Java-Kotlin", target = "_blank") { +"View SDK on GitHub" }
            }
        }
        script {
            unsafe {
                raw("""
                    var symbols = { EUR: '\u20AC', GBP: '\u00A3', USD: '$', NOK: 'kr', SEK: 'kr', DKK: 'kr' };
                    var amt = document.getElementById('amount');
                    var cur = document.getElementById('currency');
                    var display = document.getElementById('price-display');
                    function update() {
                        display.textContent = (symbols[cur.value] || cur.value) + parseFloat(amt.value || 0).toFixed(2);
                    }
                    amt.addEventListener('input', update);
                    cur.addEventListener('change', update);
                """.trimIndent())
            }
        }
    }
}

private fun HTML.successPage(orderId: String) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("Payment Successful — NoPayn Demo")
        style {
            unsafe {
                raw(commonCss + """
                    .icon {
                        width: 72px; height: 72px; border-radius: 50%;
                        background: linear-gradient(135deg, #10b981, #34d399);
                        display: flex; align-items: center; justify-content: center;
                        margin: 0 auto 20px; font-size: 36px; color: #fff;
                    }
                    h1 { font-size: 22px; margin-bottom: 8px; }
                    .order-id { color: #64748b; font-size: 14px; margin-bottom: 24px; word-break: break-all; }
                    .btn {
                        display: inline-block; padding: 12px 28px;
                        background: #6366f1; color: #fff; border-radius: 10px;
                        text-decoration: none; font-weight: 600; font-size: 14px;
                    }
                """.trimIndent())
            }
        }
    }
    body {
        div("card") {
            div("icon") { +"\u2713" }
            h1 { +"Payment Successful" }
            p("order-id") {
                +"Order: "
                strong { +orderId }
            }
            a(href = "/", classes = "btn") { +"Back to Shop" }
        }
    }
}

private fun HTML.failurePage(title: String, message: String) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("Payment Failed — NoPayn Demo")
        style {
            unsafe {
                raw(commonCss + """
                    .icon {
                        width: 72px; height: 72px; border-radius: 50%;
                        background: linear-gradient(135deg, #ef4444, #f87171);
                        display: flex; align-items: center; justify-content: center;
                        margin: 0 auto 20px; font-size: 36px; color: #fff;
                    }
                    h1 { font-size: 22px; margin-bottom: 8px; }
                    .msg { color: #64748b; font-size: 14px; margin-bottom: 24px; line-height: 1.5; }
                    .btn {
                        display: inline-block; padding: 12px 28px;
                        background: #6366f1; color: #fff; border-radius: 10px;
                        text-decoration: none; font-weight: 600; font-size: 14px;
                    }
                """.trimIndent())
            }
        }
    }
    body {
        div("card") {
            div("icon") { +"\u2717" }
            h1 { +title }
            p("msg") { +message }
            a(href = "/", classes = "btn") { +"Try Again" }
        }
    }
}
