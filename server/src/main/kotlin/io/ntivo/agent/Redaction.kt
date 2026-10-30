package io.ntivo.agent

/** `key=`, `token=` or `access_token=` in a URL query, up to the next `&`, whitespace or `]`. */
private val QUERY_SECRET = Regex("([?&](key|token|access_token)=)[^&\\s\\]]+")

/** [text] with secrets in URL query parameters replaced by `REDACTED`. */
fun redactSecrets(text: String): String = QUERY_SECRET.replace(text, "$1REDACTED")

/**
 * The message of [e], safe to log, persist or return to the browser.
 *
 * Gemini clients send the API key as `?key=...`, and Ktor's timeout exceptions quote
 * the full request URL, so a raw `e.message` can carry the key. Falls back to the
 * exception's class name when there is no message.
 */
fun safeMessage(e: Throwable): String = redactSecrets(e.message ?: e::class.simpleName ?: "error")
