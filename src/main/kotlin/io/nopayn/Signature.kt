package io.nopayn

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signature utilities for NoPayn payment verification.
 *
 * The canonical message format is `amount:currency:orderId`.
 */
public object NoPaynSignature {

    /**
     * Generate an HMAC-SHA256 hex signature for the given payment parameters.
     *
     * @param secret   The API key used as the HMAC secret.
     * @param amount   Amount in smallest currency unit (cents).
     * @param currency ISO 4217 currency code.
     * @param orderId  The NoPayn order UUID or merchant order ID.
     * @return Hex-encoded HMAC-SHA256 signature.
     */
    @JvmStatic
    public fun generate(secret: String, amount: Int, currency: String, orderId: String): String {
        val message = "$amount:$currency:$orderId"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify an HMAC-SHA256 signature using constant-time comparison.
     *
     * @return `true` if the signature is valid, `false` otherwise.
     */
    @JvmStatic
    public fun verify(
        secret: String,
        amount: Int,
        currency: String,
        orderId: String,
        signature: String,
    ): Boolean {
        val expected = generate(secret, amount, currency, orderId)
        if (expected.length != signature.length) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8),
        )
    }
}
