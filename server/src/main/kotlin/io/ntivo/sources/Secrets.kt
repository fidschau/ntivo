package io.ntivo.sources

/**
 * Obvious credential shapes. Repository text is redacted with these before it is
 * embedded or stored, so a committed key never reaches the vector store or a prompt.
 * This is a safety net for the common cases, not a full secret scanner.
 */
private val SECRET_PATTERNS = listOf(
    Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
    Regex("""AIza[0-9A-Za-z_\-]{35}"""),                 // Google API key
    Regex("""gh[pousr]_[A-Za-z0-9]{36,}"""),             // GitHub tokens
    Regex("""github_pat_[A-Za-z0-9_]{22,}"""),           // GitHub fine-grained token
    Regex("""(?:A3T[A-Z0-9]|AKIA|ASIA)[A-Z0-9]{16}"""),  // AWS access key id
    Regex("""xox[abprs]-[A-Za-z0-9-]{10,}"""),            // Slack token
    Regex("""sk-(?:ant-)?[A-Za-z0-9_\-]{20,}"""),        // Anthropic / OpenAI style keys
    Regex("""[sr]k_live_[0-9A-Za-z]{20,}""")             // Stripe live keys
)

const val REDACTED = "[REDACTED]"

/** [text] with every match of a known secret pattern replaced by [REDACTED]. */
fun redactSecrets(text: String): String =
    SECRET_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc, REDACTED) }
