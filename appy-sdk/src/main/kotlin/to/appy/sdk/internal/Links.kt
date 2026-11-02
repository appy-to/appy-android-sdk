package to.appy.sdk.internal

import java.net.URLDecoder
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object Links {
    private const val CLICK_ID = "appy_click_id"
    private val SLUG = Regex("^[a-z0-9-]{1,64}$")
    private val TIMESTAMP = Regex("^(\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?([Zz]|[+-]\\d{2}:?\\d{2})$")
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun host(url: String): String? = afterScheme(url).substringBefore('/').substringAfterLast('@').substringBefore(':')
        .trimEnd('.').lowercase().takeIf { it.isNotEmpty() }

    fun query(url: String): List<Pair<String, String>> = url.substringBefore('#').substringAfter('?', "").split('&')
        .filter { it.isNotEmpty() }
        .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }

    fun hasClickId(url: String): Boolean = query(url).any { it.first == CLICK_ID }

    fun isAppyLink(url: String, domains: List<String>): Boolean = host(url)?.let { it in domains } == true || hasClickId(url)

    fun isEmailLink(url: String, emailDomains: List<String>): Boolean = host(url)?.let { it in emailDomains } == true

    fun normalizeDomain(domain: String): String? = domain.trim().let { host(if ("://" in it) it else "https://$it") }

    fun localLink(url: String, domains: List<String>): ResolvedLink {
        val parameters = LinkedHashMap<String, String>()
        for ((name, value) in query(url)) {
            if (name.isNotEmpty() && !name.startsWith("appy_", ignoreCase = true)) parameters.getOrPut(name) { value }
        }
        val onLinkDomain = host(url)?.let { it in domains } == true
        val slug = if (onLinkDomain) decode(afterScheme(url).substringAfter('/', "").substringBefore('/')) else ""
        return ResolvedLink(url, null, slug, parameters, isDeferred = false, clickedAt = null)
    }

    fun build(domain: String?, slug: String, parameters: Map<String, String>): String? {
        val path = slug.lowercase()
        if (domain == null || !SLUG.matches(path)) return null
        val entries: Map<*, *> = parameters
        val query = entries.mapNotNull { (key, value) -> if (key is String && key.isNotEmpty() && value is String) key to value else null }
            .sortedBy { it.first }
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return if (query.isEmpty()) "https://$domain/$path" else "https://$domain/$path?$query"
    }

    fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = UTC }.format(Date(millis))

    fun parseTimestamp(value: String): Long? {
        val (base, fraction, zone) = TIMESTAMP.matchEntire(value.trim())?.destructured ?: return null
        val offset = if (zone.equals("Z", ignoreCase = true)) "+0000" else zone.replace(":", "")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { isLenient = false }
        val seconds = try {
            format.parse(base.uppercase() + offset)?.time
        } catch (invalid: ParseException) {
            null
        } ?: return null
        return seconds + fraction.padEnd(3, '0').take(3).toInt()
    }

    private fun afterScheme(url: String): String = url.trim().substringBefore('#').substringBefore('?').substringAfter("://", "")

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (malformed: IllegalArgumentException) {
        value
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
