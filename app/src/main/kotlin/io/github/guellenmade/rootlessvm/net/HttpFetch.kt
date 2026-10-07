package io.github.guellenmade.rootlessvm.net

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * HTTP fetch with mandatory HTTPS, timeouts, and redirect validation.
 * All runtime artifact downloads (proot, rootfs, Vector) go through here
 * so the security policy is enforced in one place.
 */
object HttpFetch {
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_REDIRECTS = 5

    /** Opens a validated HTTPS connection, following redirects (each re-validated). */
    fun open(url: String): InputStream {
        var current = URI(url)
        repeat(MAX_REDIRECTS + 1) { attempt ->
            require(current.scheme.equals("https", ignoreCase = true)) {
                "refusing non-HTTPS url: ${current.scheme}://${current.host}"
            }
            val conn = current.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            when {
                code == 200 -> return conn.inputStream
                code in 301..303 || code == 307 || code == 308 -> {
                    val loc = conn.getHeaderField("Location")
                        ?: error("HTTP $code without Location header")
                    conn.disconnect()
                    current = URI(loc)
                    if (attempt == MAX_REDIRECTS) error("too many redirects (max $MAX_REDIRECTS)")
                }
                else -> {
                    conn.disconnect()
                    error("HTTP $code for ${current}")
                }
            }
        }
        error("unreachable")
    }

    /** Fetches the full response body as text (for small files like .sha256). */
    fun fetchText(url: String): String =
        open(url).bufferedReader().use { it.readText() }
}
