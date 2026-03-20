package io.nopayn

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SignatureTest {

    private val secret = "test-api-key-12345"

    @Test
    fun `generate produces deterministic output`() {
        val sig1 = NoPaynSignature.generate(secret, 1295, "EUR", "order-001")
        val sig2 = NoPaynSignature.generate(secret, 1295, "EUR", "order-001")
        assertEquals(sig1, sig2)
        assertTrue(sig1.matches(Regex("^[0-9a-f]{64}$")), "Should be 64-char hex string")
    }

    @Test
    fun `round-trip generate then verify`() {
        val sig = NoPaynSignature.generate(secret, 5000, "GBP", "uuid-abc-123")
        assertTrue(NoPaynSignature.verify(secret, 5000, "GBP", "uuid-abc-123", sig))
    }

    @Test
    fun `verify fails with tampered amount`() {
        val sig = NoPaynSignature.generate(secret, 1000, "EUR", "order-x")
        assertFalse(NoPaynSignature.verify(secret, 9999, "EUR", "order-x", sig))
    }

    @Test
    fun `verify fails with tampered currency`() {
        val sig = NoPaynSignature.generate(secret, 1000, "EUR", "order-x")
        assertFalse(NoPaynSignature.verify(secret, 1000, "USD", "order-x", sig))
    }

    @Test
    fun `verify fails with tampered orderId`() {
        val sig = NoPaynSignature.generate(secret, 1000, "EUR", "order-x")
        assertFalse(NoPaynSignature.verify(secret, 1000, "EUR", "order-y", sig))
    }

    @Test
    fun `verify fails with wrong key`() {
        val sig = NoPaynSignature.generate(secret, 1000, "EUR", "order-x")
        assertFalse(NoPaynSignature.verify("wrong-key", 1000, "EUR", "order-x", sig))
    }

    @Test
    fun `verify fails with garbage signature`() {
        assertFalse(NoPaynSignature.verify(secret, 1000, "EUR", "order-x", "not-a-valid-hex"))
    }

    @Test
    fun `verify fails with empty signature`() {
        assertFalse(NoPaynSignature.verify(secret, 1000, "EUR", "order-x", ""))
    }

    @Test
    fun `different amounts produce different signatures`() {
        val sig1 = NoPaynSignature.generate(secret, 100, "EUR", "order-1")
        val sig2 = NoPaynSignature.generate(secret, 200, "EUR", "order-1")
        assertNotEquals(sig1, sig2)
    }
}
