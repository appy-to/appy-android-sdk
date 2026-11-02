package to.appy.sdk.internal

import org.json.JSONObject
import to.appy.sdk.AppyAdNetwork
import to.appy.sdk.AppyAttribution
import to.appy.sdk.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

internal class HttpResult(val status: Int, val body: String, val retryAfter: String? = null, val location: String? = null) {
    val ok: Boolean get() = status in 200..299
    val retryable: Boolean get() = status == 0 || status == 429 || status >= 500
}

internal fun backoffMillis(step: Int): Long {
    val base = (1_000L shl (step - 1).coerceIn(0, 20)).coerceAtMost(300_000L)
    return base + (base * 0.1 * Random.nextDouble()).toLong()
}

internal fun retryAfterMillis(result: HttpResult): Long? {
    if (result.status != 429) return null
    return result.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(86_400)?.times(1_000)
}

internal class Api(
    private val settings: Settings,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val log: Logger,
) {
    private val headers = mapOf(
        "Authorization" to "Bearer ${settings.key}",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "X-Appy-SDK" to "android/${BuildConfig.SDK_VERSION}",
    )

    fun post(path: String, body: JSONObject, onResult: (HttpResult) -> Unit) = attempt(path, body.toString(), 1, onResult)

    private fun attempt(path: String, payload: String, number: Int, onResult: (HttpResult) -> Unit) {
        log.debug("POST $path, attempt $number of $MAX_ATTEMPTS")
        scheduler.io {
            val result = try {
                transport.post(settings.baseUrl + path, headers, payload)
            } catch (failure: Exception) {
                log.warning("POST $path failed: ${failure.javaClass.simpleName} ${failure.message.orEmpty()}")
                HttpResult(0, "")
            }
            scheduler.post { handle(path, payload, number, result, onResult) }
        }
    }

    private fun handle(path: String, payload: String, number: Int, result: HttpResult, onResult: (HttpResult) -> Unit) {
        if (!result.ok && result.status != 0) log.error("POST $path returned HTTP ${result.status} ${errorDetail(result.body)}")
        val retryAfter = retryAfterMillis(result)
        val delay = when {
            !result.retryable || number >= MAX_ATTEMPTS -> null
            retryAfter == null -> backoffMillis(number)
            retryAfter <= MAX_INLINE_RETRY_AFTER_MILLIS -> retryAfter
            else -> null
        }
        if (delay != null) {
            scheduler.postDelayed(delay) { attempt(path, payload, number + 1, onResult) }
            return
        }
        if (result.retryable) log.error("POST $path gave up after $number attempt(s)")
        onResult(result)
    }

    private fun errorDetail(body: String): String = runCatching {
        val error = JSONObject(body).getJSONObject("error")
        "${error.optString("code")}: ${error.optString("message")} (requestId ${error.optString("requestId")})"
    }.getOrDefault("without an error body")

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_INLINE_RETRY_AFTER_MILLIS = 10_000L
    }
}

internal class UrlConnectionTransport(private val timeoutMillis: Int) : Transport {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val bytes = body.toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResult(status, read(stream), connection.getHeaderField("Retry-After"))
        } finally {
            connection.disconnect()
        }
    }

    override fun get(url: String): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            return HttpResult(connection.responseCode, "", location = connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    private fun read(stream: InputStream?): String = stream?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() < MAX_BODY_BYTES) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }.orEmpty()

    private companion object {
        const val MAX_BODY_BYTES = 256 * 1_024
    }
}

internal class OpenResponse(val link: ResolvedLink?, val attribution: AppyAttribution?, val attributionJson: String?)

internal fun parseOpen(body: String): OpenResponse? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val attribution = json.optJSONObject("attribution")
    return OpenResponse(json.optJSONObject("deepLink")?.let(::parseDeepLink), attribution?.let(::parseAttribution), attribution?.toString())
}

internal fun storedAttribution(value: String): AppyAttribution? = runCatching { parseAttribution(JSONObject(value)) }.getOrNull()

private fun parseDeepLink(link: JSONObject): ResolvedLink {
    val parameters = link.optJSONObject("parameters")
    return ResolvedLink(
        url = link.text("url"),
        deeplinkUrl = link.text("deeplinkUrl"),
        slug = link.text("slug").orEmpty(),
        parameters = parameters?.keys()?.asSequence()?.mapNotNull { key -> parameters.text(key)?.let { key to it } }?.toMap().orEmpty(),
        isDeferred = link.optBoolean("isDeferred"),
        clickedAt = link.text("clickedAt")?.let(Links::parseTimestamp),
    )
}

private fun parseAttribution(json: JSONObject): AppyAttribution {
    val link = json.optJSONObject("link")
    return AppyAttribution(
        isOrganic = json.text("status") != "attributed",
        linkSlug = link?.text("slug"),
        linkTitle = link?.text("title"),
        source = json.text("source"),
        medium = json.text("medium"),
        campaign = json.text("campaign"),
        clickedAt = json.text("clickedAt")?.let(Links::parseTimestamp),
        network = json.optJSONObject("network")?.let(::parseNetwork),
    )
}

private fun parseNetwork(json: JSONObject): AppyAdNetwork? {
    val name = json.text("name") ?: return null
    return AppyAdNetwork(
        name = name,
        campaignId = json.text("campaignId"),
        campaignName = json.text("campaignName"),
        adGroupId = json.text("adGroupId"),
        adGroupName = json.text("adGroupName"),
        adId = json.text("adId"),
        clickId = json.text("clickId"),
        touch = json.text("touch"),
    )
}

private fun JSONObject.text(key: String): String? = opt(key)?.takeIf { it != JSONObject.NULL }?.toString()
