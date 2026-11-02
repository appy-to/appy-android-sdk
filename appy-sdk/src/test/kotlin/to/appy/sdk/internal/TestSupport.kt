package to.appy.sdk.internal

import org.json.JSONObject
import to.appy.sdk.AppyAttribution
import to.appy.sdk.AppyConfig
import to.appy.sdk.AppyLogLevel
import java.io.IOException
import java.net.URL
import java.util.PriorityQueue

internal class FakeScheduler : Scheduler {
    var elapsed = 0L
        private set
    var latencyMillis = 0L
    private var order = 0L
    private val tasks = PriorityQueue(compareBy<Task>({ it.due }, { it.order }))

    private class Task(val due: Long, val order: Long, val run: () -> Unit) {
        var cancelled = false
    }

    override fun now(): Long = START + elapsed

    override fun post(task: () -> Unit) {
        postDelayed(0, task)
    }

    override fun postDelayed(delayMillis: Long, task: () -> Unit): () -> Unit {
        val entry = Task(elapsed + delayMillis, order++, task)
        tasks.add(entry)
        return { entry.cancelled = true }
    }

    override fun io(task: () -> Unit) {
        postDelayed(latencyMillis, task)
    }

    fun advance(millis: Long) {
        val target = elapsed + millis
        while (true) {
            val next = tasks.peek() ?: break
            if (next.due > target) break
            tasks.poll()
            elapsed = next.due
            if (!next.cancelled) next.run()
        }
        elapsed = target
    }

    companion object {
        const val START: Long = 1_790_330_645_000L
    }
}

internal class FakeTransport(private val scheduler: FakeScheduler) : Transport {
    class Call(val path: String, val headers: Map<String, String>, val body: JSONObject, val at: Long)

    val calls = mutableListOf<Call>()
    var openResult = HttpResult(200, NO_LINK)
    var eventsResult = HttpResult(202, """{"accepted":1,"duplicates":0,"rejected":[]}""")
    private val scripted = HashMap<String, ArrayDeque<HttpResult>>()

    fun enqueue(path: String, vararg results: HttpResult) {
        scripted.getOrPut(path) { ArrayDeque() }.addAll(results)
    }

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val path = URL(url).path
        calls.add(Call(path, headers, JSONObject(body), scheduler.elapsed))
        val result = scripted[path]?.removeFirstOrNull() ?: if (path == Events.EVENTS_PATH) eventsResult else openResult
        if (result === OFFLINE) throw IOException("Unable to resolve host")
        return result
    }

    override fun get(url: String): HttpResult {
        gets.add(url)
        val result = pages[url] ?: HttpResult(404, "")
        if (result === OFFLINE) throw IOException("Unable to resolve host")
        return result
    }

    val gets = mutableListOf<String>()
    val pages = HashMap<String, HttpResult>()

    fun redirect(from: String, to: String, status: Int = 302) {
        pages[from] = HttpResult(status, "", location = to)
    }

    fun opens(): List<JSONObject> = calls.filter { it.path == Session.OPEN_PATH }.map { it.body }

    fun batches(): List<JSONObject> = calls.filter { it.path == Events.EVENTS_PATH }.map { it.body }

    fun sentEvents(): List<JSONObject> = batches().flatMap { batch ->
        val events = batch.getJSONArray("events")
        (0 until events.length()).map { events.getJSONObject(it) }
    }

    fun eventNames(): List<String> = sentEvents().map { it.getString("name") }

    companion object {
        const val NO_LINK: String = """{"installId":"x","firstLaunch":false,"deepLink":null,"attribution":null}"""
        const val ORGANIC: String =
            """{"installId":"x","firstLaunch":true,"deepLink":null,"attribution":{"status":"organic","link":null,"source":null,"medium":null,"campaign":null,"clickedAt":null}}"""
        val OFFLINE: HttpResult = HttpResult(-1, "")

        fun status(code: Int, body: String = "", retryAfter: String? = null): HttpResult = HttpResult(code, body, retryAfter)

        fun withLink(link: JSONObject, attribution: JSONObject? = null): HttpResult =
            HttpResult(200, JSONObject().put("deepLink", link).put("attribution", attribution ?: JSONObject.NULL).toString())

        fun link(url: String = "https://acme.appy.to/spring?item=42", isDeferred: Boolean = false): JSONObject = JSONObject()
            .put("url", url)
            .put("deeplinkUrl", "acme://items?item=42")
            .put("slug", "spring")
            .put("parameters", JSONObject().put("item", "42").put("utm_source", "instagram"))
            .put("isDeferred", isDeferred)
            .put("clickedAt", "2026-09-25T10:04:05Z")

        fun deferred(): HttpResult = withLink(link(isDeferred = true), attribution())

        fun attribution(): JSONObject = JSONObject()
            .put("status", "attributed")
            .put("link", JSONObject().put("slug", "spring").put("title", "Spring campaign"))
            .put("source", "instagram")
            .put("medium", "story")
            .put("campaign", "spring_sale")
            .put("clickedAt", "2026-09-25T10:04:05Z")
    }
}

internal class MemoryStore : Store {
    val values = HashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal class FakeReferrer : ReferrerSource {
    var requests = 0
    var cancels = 0
    private var callback: ((String?) -> Unit)? = null

    override fun fetch(onResult: (String?) -> Unit): () -> Unit {
        requests++
        callback = onResult
        return { cancels++ }
    }

    fun complete(value: String?) {
        checkNotNull(callback) { "no install referrer request" }.invoke(value)
    }
}

internal class Harness(val store: MemoryStore = MemoryStore()) {
    val scheduler = FakeScheduler()
    val transport = FakeTransport(scheduler)
    val referrer = FakeReferrer()
    val appGallery = FakeReferrer()
    var installer: String? = null
    var metaRow: JSONObject? = null
    val metaQueries = mutableListOf<String>()
    val logs = mutableListOf<Pair<AppyLogLevel, String>>()
    val delivered = mutableListOf<LinkResult>()
    val attributions = mutableListOf<AppyAttribution>()
    val opened = mutableListOf<String>()
    val timeline = mutableListOf<String>()
    private val mainQueue = ArrayDeque<() -> Unit>()
    val engine = Engine(scheduler, { task -> mainQueue.addLast(task) }, { level, message -> logs.add(level to message) })

    val mainPending: Int get() = mainQueue.size

    fun init(config: AppyConfig = config()): Boolean = engine.init(config) {
        val openUrl: (String) -> Unit = { url ->
            opened.add(url)
            timeline.add("open $url")
        }
        val metaReferrer: (String) -> JSONObject? = { appId ->
            metaQueries.add(appId)
            metaRow?.let { JSONObject(it.toString()) }
        }
        Services(store, transport, InstallSource({ installer }, referrer, appGallery), openUrl, metaReferrer) {
            JSONObject(DEVICE.toString()) to JSONObject(APP.toString())
        }
    }

    fun listen() {
        engine.delivery.links.listen { result ->
            delivered.add(result)
            timeline.add("listener ${result.describe()}")
        }
    }

    fun listenAttribution() {
        engine.delivery.attributions.listen { attributions.add(it) }
    }

    fun advance(millis: Long = 0) {
        scheduler.advance(millis)
        runMain()
    }

    fun runMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()
    }

    fun completeFirstOpen() {
        store[Session.FIRST_OPEN_KEY] = "true"
    }

    fun opens(): List<JSONObject> = transport.opens()

    fun singleOpen(): JSONObject = opens().single()

    fun messages(level: AppyLogLevel): List<String> = logs.filter { it.first == level }.map { it.second }

    fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        revenue: Double? = null,
        currency: String? = null,
        deduplicationId: String? = null,
    ) {
        engine.track(name, properties, revenue, currency, deduplicationId)
    }

    companion object {
        const val KEY: String = "appy_pk_test_0123456789abcdef"
        const val LINK: String = "https://acme.appy.to/spring?item=42&appy_click_id=h3k9"
        const val REFERRER: String = "utm_source=google-play&appy_slug=spring&appy_click_id=h3k9"
        val DEVICE: JSONObject = JSONObject().put("platform", "android").put("osVersion", "14").put("model", "Pixel 8")
        val APP: JSONObject = JSONObject().put("bundleId", "com.acme.shop").put("version", "2.3.1").put("build", "231")

        fun config(
            linkWaitMillis: Long = 500,
            collectInstallReferrer: Boolean = true,
            openDeferredDeepLinks: Boolean = false,
            deferredTimeoutMillis: Long = 5_000,
            linkDomains: List<String> = listOf("acme.appy.to"),
            publishableKey: String = KEY,
            metaAppId: String? = null,
            emailLinkDomains: List<String> = emptyList(),
        ): AppyConfig = AppyConfig(publishableKey, linkDomains).apply {
            this.metaAppId = metaAppId
            this.emailLinkDomains = emailLinkDomains
            logLevel = AppyLogLevel.DEBUG
            launchLinkWaitMillis = linkWaitMillis
            this.collectInstallReferrer = collectInstallReferrer
            this.openDeferredDeepLinks = openDeferredDeepLinks
            deferredDeepLinkTimeoutMillis = deferredTimeoutMillis
        }
    }
}

internal val LinkResult.isNotFound: Boolean get() = link == null && error == null

internal fun LinkResult.describe(): String = link?.let { "found ${it.slug}" } ?: error?.let { "failed $it" } ?: "not found"
