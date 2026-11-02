package to.appy.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

private val CURRENCY = Regex("^[A-Z]{3}$")
private const val MAX_EVENT_ID_LENGTH = 128

internal fun eventJson(
    name: String,
    properties: Map<*, *>,
    revenue: Double?,
    currency: String?,
    deduplicationId: String?,
    trackedAt: Long,
    userId: String?,
    log: Logger,
): JSONObject? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > 64 || trimmed.any { it.isISOControl() }) {
        log.warning("Event name must be 1 to 64 printable characters; \"$name\" was dropped")
        return null
    }
    val accepted = JSONObject()
    for ((key, value) in properties) {
        val clean = propertyValue(value)
        when {
            key !is String || key.isEmpty() || key.length > 64 -> log.debug("Event \"$trimmed\": key \"$key\" must be 1 to 64 characters")
            accepted.length() >= 50 -> log.debug("Event \"$trimmed\": more than 50 properties, \"$key\" dropped")
            clean == null -> log.debug("Event \"$trimmed\": \"$key\" must be a String, Number or Boolean")
            else -> accepted.put(key, clean)
        }
    }
    val event = JSONObject()
        .put("id", eventId(trimmed, deduplicationId, log))
        .put("name", trimmed)
        .put("timestamp", Links.formatTimestamp(trackedAt))
        .put("properties", accepted)
        .putOpt("userId", userId)
    val code = currency?.trim()?.uppercase()
    when {
        revenue == null -> if (currency != null) log.debug("Event \"$trimmed\": currency without revenue was ignored")
        revenue.isFinite() && abs(revenue) < 1e12 && code != null && CURRENCY.matches(code) ->
            event.put("revenue", revenue).put("currency", code)
        else -> log.warning("Event \"$trimmed\": revenue needs an amount below 1e12 and an ISO 4217 currency; both dropped")
    }
    return event
}

private fun eventId(name: String, deduplicationId: String?, log: Logger): String {
    val id = deduplicationId?.trim() ?: return UUID.randomUUID().toString()
    val problem = when {
        id.isEmpty() -> "is empty"
        id.length > MAX_EVENT_ID_LENGTH -> "is longer than $MAX_EVENT_ID_LENGTH characters"
        id.any { it !in '!'..'~' } -> "\"$id\" contains a space or a character outside printable ASCII"
        else -> return id
    }
    log.error("Event \"$name\": deduplicationId $problem; a generated id was used")
    return UUID.randomUUID().toString()
}

private fun propertyValue(value: Any?): Any? = when (value) {
    is String -> if (value.length <= 512) value else value.substring(0, if (value[511].isHighSurrogate()) 511 else 512)
    is Boolean -> value
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    is Number -> value.toDouble().takeIf { it.isFinite() }
    else -> null
}

internal class Events(
    private val store: Store,
    private val api: Api,
    private val scheduler: Scheduler,
    private val installId: String,
    private val log: Logger,
) {
    private val queue: MutableList<JSONObject> = load()
    private var gateOpen = false
    private var sending = false
    private var flushAgain = false
    private var failures = 0
    private var debounce: (() -> Unit)? = null
    private var retry: (() -> Unit)? = null

    fun add(event: JSONObject) {
        val id = event.optString("id")
        if (queue.any { it.optString("id") == id }) {
            return log.info("Event \"${event.optString("name")}\" with deduplicationId $id is already waiting to be sent; the new one was dropped")
        }
        queue.add(event)
        if (queue.size > CAPACITY) {
            queue.subList(0, queue.size - CAPACITY).clear()
            log.warning("Event queue is full; the oldest events were dropped")
        }
        save()
        if (queue.size >= FLUSH_AT) return flush()
        debounce?.invoke()
        debounce = scheduler.postDelayed(DEBOUNCE_MILLIS) {
            debounce = null
            flush()
        }
    }

    fun openGate() {
        gateOpen = true
        if (queue.isNotEmpty()) flush()
    }

    fun closeGate() {
        gateOpen = false
    }

    fun clear() {
        queue.clear()
        save()
        debounce?.invoke()
        retry?.invoke()
        debounce = null
        retry = null
        failures = 0
    }

    fun flush() {
        debounce?.invoke()
        debounce = null
        when {
            !gateOpen || retry != null || queue.isEmpty() -> Unit
            sending -> flushAgain = true
            else -> send()
        }
    }

    private fun send() {
        val batch = ArrayList<JSONObject>()
        var bytes = ENVELOPE_BYTES + installId.length
        for (event in queue.take(BATCH_SIZE)) {
            val size = event.toString().toByteArray().size + 1
            if (batch.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) break
            batch.add(event)
            bytes += size
        }
        val more = batch.size < queue.size
        sending = true
        flushAgain = false
        val body = JSONObject().put("installId", installId).put("events", JSONArray(batch))
        api.post(EVENTS_PATH, body) { result -> finished(batch, more, result) }
    }

    private fun finished(batch: List<JSONObject>, more: Boolean, result: HttpResult) {
        sending = false
        if (result.retryable) {
            failures++
            val delay = maxOf(backoffMillis(failures + 2), retryAfterMillis(result) ?: 0)
            log.warning("Events could not be delivered; ${queue.size} kept, next attempt in $delay ms")
            retry = scheduler.postDelayed(delay) {
                retry = null
                flush()
            }
            return
        }
        val sent = batch.map { it.optString("id") }.toSet()
        queue.removeAll { it.optString("id") in sent }
        save()
        failures = 0
        if (result.ok) logRejected(batch, result.body) else log.error("${batch.size} event(s) rejected with HTTP ${result.status} were dropped")
        if (more || flushAgain) flush()
    }

    private fun logRejected(batch: List<JSONObject>, body: String) {
        val rejected = runCatching { JSONObject(body).optJSONArray("rejected") }.getOrNull() ?: return
        for (position in 0 until rejected.length()) {
            val entry = rejected.optJSONObject(position) ?: continue
            val name = batch.getOrNull(entry.optInt("index", -1))?.optString("name") ?: entry.optString("id")
            log.warning("Server rejected event \"$name\": ${entry.optString("message")}")
        }
    }

    private fun load(): MutableList<JSONObject> {
        val array = store[QUEUE_KEY]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return ArrayList()
        return (0 until array.length()).mapNotNullTo(ArrayList()) { index ->
            array.optJSONObject(index)?.takeIf { it.has("id") && it.has("name") }
        }
    }

    private fun save() {
        store[QUEUE_KEY] = if (queue.isEmpty()) null else JSONArray(queue).toString()
    }

    companion object {
        const val EVENTS_PATH: String = "/v1/sdk/events"
        const val QUEUE_KEY: String = "eventQueue"
        private const val CAPACITY = 1_000
        private const val FLUSH_AT = 20
        private const val BATCH_SIZE = 100
        private const val MAX_BATCH_BYTES = 256 * 1_024
        private const val ENVELOPE_BYTES = 32
        private const val DEBOUNCE_MILLIS = 2_000L
    }
}
