package io.nopayn

/**
 * Base exception for all NoPayn SDK errors.
 */
public open class NoPaynException(message: String) : Exception(message)

/**
 * Thrown when the NoPayn API returns a non-2xx HTTP response.
 *
 * @property statusCode HTTP status code returned by the API.
 * @property errorBody  Raw error response body, if available.
 */
public class ApiException(
    public val statusCode: Int,
    message: String,
    public val errorBody: String? = null,
) : NoPaynException("NoPayn API error (HTTP $statusCode): $message")

/**
 * Thrown when webhook parsing or verification fails.
 */
public class WebhookException(message: String) : NoPaynException(message)
