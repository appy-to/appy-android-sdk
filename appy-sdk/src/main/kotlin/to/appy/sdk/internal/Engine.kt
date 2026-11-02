package to.appy.sdk.internal

import org.json.JSONObject
import to.appy.sdk.AppyAttribution
import to.appy.sdk.AppyConfig
import to.appy.sdk.AppyError
import to.appy.sdk.AppyLogLevel
import to.appy.sdk.BuildConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal interface Scheduler {
    fun now(): Long

    fun post(task: () -> Unit)

    fun postDelayed(delayMillis: Long, task: () -> Unit): () -> Unit

    fun io(task: () -> Unit)
}

internal interface Store {
    operator fun get(key: String): String?

    operator fun set(key: String, value: String?)
}

internal interface Transport {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String): HttpResult

    @Throws(IOException::class)
    fun get(url: String): HttpResult
}

internal fun interface ReferrerSource {
    fun fetch(onResult: (String?) -> Unit): () -> Unit
}

internal fun interface MainPoster {
    fun post(task: () -> Unit)
}

internal class Services(
    val store: Store,
    val transport: Transport,
    val install: InstallSource,
    val openUrl: (String) -> Unit,
    val metaReferrer: (String) -> JSONObject? = { null },
    val environment: () -> Pair<JSONObject, JSONObject>,
)

internal class Settings(config: AppyConfig) {
    val key: String = config.publishableKey.trim()
    val domains: List<String> = config.linkDomains.mapNotNull(Links::normalizeDomain).distinct()
    val emailDomains: List<String> = config.emailLinkDomains.mapNotNull(Links::normalizeDomain).distinct().filter { it !in domains }
    val baseUrl: String = config.apiBaseUrl.trim().trimEnd('/')
    val logLevel: AppyLogLevel = config.logLevel
    val linkWaitMillis: Long = config.launchLinkWaitMillis.coerceAtLeast(0)
    val timeoutMillis: Int = config.requestTimeoutMillis.coerceAtLeast(1)
    val collectReferrer: Boolean = config.collectInstallReferrer
    val openDeferred: Boolean = config.openDeferredDeepLinks
    val deferredTimeoutMillis: Long = config.deferredDeepLinkTimeoutMillis.coerceAtLeast(0)
    val metaAppId: String? = config.metaAppId?.trim()?.takeIf { META_APP_ID.matches(it) }

    private companion object {
        val META_APP_ID = Regex("^[0-9]{5,20}$")
    }
}

internal class ResolvedLink(
    val url: String?,
    val deeplinkUrl: String?,
    val slug: String,
    val parameters: Map<String, String>,
    val isDeferred: Boolean,
    val clickedAt: Long?,
)

internal class LinkResult(val link: ResolvedLink?, val error: AppyError? = null)

internal class Logger(private val level: AppyLogLevel, private val sink: (AppyLogLevel, String) -> Unit) {
    fun error(message: String) = write(AppyLogLevel.ERROR, message)

    fun warning(message: String) = write(AppyLogLevel.WARNING, message)

    fun info(message: String) = write(AppyLogLevel.INFO, message)

    fun debug(message: String) = write(AppyLogLevel.DEBUG, message)

    private fun write(at: AppyLogLevel, message: String) {
        if (level != AppyLogLevel.NONE && at.ordinal <= level.ordinal) sink(at, message)
    }
}

internal class Mailbox<T : Any>(private val main: MainPoster) {
    private var receiver: ((T) -> Unit)? = null
    private var pending: T? = null

    fun listen(newReceiver: ((T) -> Unit)?) {
        val buffered = synchronized(this) {
            receiver = newReceiver
            if (newReceiver == null) null else pending.also { pending = null }
        }
        if (buffered != null) main.post { dispatch(buffered) }
    }

    fun post(value: T, then: (() -> Unit)? = null) = main.post {
        dispatch(value)
        then?.invoke()
    }

    private fun dispatch(value: T) {
        val target = synchronized(this) { receiver.also { if (it == null) pending = value } }
        target?.invoke(value)
    }
}

internal class Delivery(main: MainPoster) {
    val links = Mailbox<LinkResult>(main)
    val attributions = Mailbox<AppyAttribution>(main)

    @Volatile
    var latest: ResolvedLink? = null
        private set

    fun deliver(result: LinkResult, then: (() -> Unit)? = null) {
        result.link?.let { latest = it }
        links.post(result, then)
    }
}

internal class Engine(
    private val scheduler: Scheduler,
    main: MainPoster,
    private val sink: (AppyLogLevel, String) -> Unit,
) {
    val delivery = Delivery(main)

    @Volatile
    var log = Logger(AppyLogLevel.ERROR, sink)
        private set

    @Volatile
    private var settings: Settings? = null

    @Volatile
    private var store: Store? = null

    @Volatile
    private var trackingOverride: Boolean? = null

    private val initialized = AtomicBoolean()
    private var session: Session? = null
    private var earlyUserId: String? = null
    private var earlyUserIdSet = false
    private val earlyLinks = ArrayDeque<(Session) -> Unit>()
    private val earlyEvents = ArrayDeque<(Session) -> Unit>()

    var trackingEnabled: Boolean
        get() = trackingOverride ?: store?.let { it[TRACKING_KEY] != "false" } ?: true
        set(value) {
            trackingOverride = value
            scheduler.post { session?.setTracking(value) }
        }

    val attribution: AppyAttribution?
        get() = store?.get(Session.ATTRIBUTION_KEY)?.let(::storedAttribution)

    fun init(config: AppyConfig, services: (Settings) -> Services): Boolean {
        if (!initialized.compareAndSet(false, true)) {
            log.warning("Appy.init was called more than once; the repeated call was ignored")
            return false
        }
        val newSettings = Settings(config)
        log = Logger(newSettings.logLevel, sink)
        if (!newSettings.key.startsWith("appy_pk_")) log.error("publishableKey must start with appy_pk_; never ship a secret key")
        if (newSettings.domains.isEmpty()) log.warning("No linkDomains: only URLs with appy_click_id are recognised")
        val newServices = services(newSettings)
        store = newServices.store
        settings = newSettings
        log.info("Appy ${BuildConfig.SDK_VERSION} initialised with key ${newSettings.key.take(12)}... for ${newSettings.domains}, email domains ${newSettings.emailDomains}")
        scheduler.post { start(newSettings, newServices) }
        return true
    }

    fun handleIntent(action: String?, url: String?, alreadyHandled: Boolean, markHandled: () -> Unit): Boolean {
        if (action != ACTION_VIEW || url.isNullOrBlank() || !accepts(url)) return false
        if (!alreadyHandled) {
            markHandled()
            submit(url)
        }
        return true
    }

    fun handleUrl(url: String): Boolean = accepts(url).also { accepted -> if (accepted) submit(url) }

    fun track(name: String, properties: Map<String, Any?>, revenue: Double?, currency: String?, deduplicationId: String?) {
        val snapshot = LinkedHashMap(properties)
        val trackedAt = scheduler.now()
        onSession(earlyEvents, MAX_EARLY_EVENTS) { it.track(name, snapshot, revenue, currency, deduplicationId, trackedAt) }
    }

    fun setUserId(userId: String?) {
        scheduler.post {
            val active = session
            if (active != null) {
                active.setUserId(userId)
            } else {
                earlyUserId = userId
                earlyUserIdSet = true
            }
        }
    }

    fun buildLink(slug: String, parameters: Map<String, String>): String? {
        val current = settings
        val link = current?.let { Links.build(it.domains.firstOrNull(), slug, parameters) }
        if (link == null) log.warning("buildLink needs Appy.init, a link domain and a slug matching [a-z0-9-]{1,64}")
        return link
    }

    fun flush() = scheduler.post { session?.flush() }

    private fun accepts(url: String): Boolean {
        val current = settings ?: return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) || Links.hasClickId(url)
        return Links.isAppyLink(url, current.domains) || Links.isEmailLink(url, current.emailDomains)
    }

    private fun submit(url: String) = onSession(earlyLinks, MAX_EARLY_LINKS) { it.handleCandidate(url) }

    private fun onSession(buffer: ArrayDeque<(Session) -> Unit>, capacity: Int, call: (Session) -> Unit) {
        scheduler.post {
            val active = session
            when {
                active != null -> call(active)
                buffer.size < capacity -> buffer.addLast(call)
                else -> log.warning("Too many Appy calls before Appy.init; this one was dropped")
            }
        }
    }

    private fun start(newSettings: Settings, newServices: Services) {
        val active = Session(newSettings, newServices, scheduler, delivery, log)
        session = active
        trackingOverride?.let(active::setTracking)
        if (earlyUserIdSet) active.setUserId(earlyUserId)
        while (earlyLinks.isNotEmpty()) earlyLinks.removeFirst()(active)
        while (earlyEvents.isNotEmpty()) earlyEvents.removeFirst()(active)
        active.start()
    }

    companion object {
        const val ACTION_VIEW: String = "android.intent.action.VIEW"
        const val TRACKING_KEY: String = "trackingEnabled"
        private const val MAX_EARLY_LINKS = 10
        private const val MAX_EARLY_EVENTS = 100
    }
}
