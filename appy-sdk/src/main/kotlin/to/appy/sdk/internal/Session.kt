package to.appy.sdk.internal

import org.json.JSONObject
import to.appy.sdk.AppyError
import to.appy.sdk.BuildConfig
import java.net.URL
import java.util.UUID

internal class Session(
    private val settings: Settings,
    private val services: Services,
    private val scheduler: Scheduler,
    private val delivery: Delivery,
    private val log: Logger,
) {
    private enum class Phase { PENDING, COLLECTING, SENT, SKIPPED }

    private val store = services.store
    private val api = Api(settings, services.transport, scheduler, log)
    private val installId = store[INSTALL_ID_KEY] ?: UUID.randomUUID().toString().also { store[INSTALL_ID_KEY] = it }
    private val events = Events(store, api, scheduler, installId, log)
    private var tracking = store[Engine.TRACKING_KEY] != "false"
    private var userId = store[USER_ID_KEY]
    private var openSent = false
    private var phase = Phase.PENDING
    private var generation = 0
    private var launchUrl: String? = null
    private var referrer: InstallReferrer? = null
    private var waitingForLink = false
    private var waitingForReferrer = false
    private var cancelReferrer: (() -> Unit)? = null
    private val laterLinks = ArrayList<String>()
    private var launchPending = false
    private var launchAnswered = false
    private var resolvingEmailLinks = 0

    private val nextOpenIsFirstLaunch: Boolean get() = !openSent && store[FIRST_OPEN_KEY] != "true"

    fun start() {
        if (tracking) return begin(settings.linkWaitMillis)
        log.info("Tracking is disabled; the launch open is skipped")
        events.clear()
        skip()
    }

    fun handleCandidate(url: String) {
        when {
            Links.isEmailLink(url, settings.emailDomains) -> resolveEmailLink(url)
            !Links.isAppyLink(url, settings.domains) -> log.debug("Ignoring a URL that is not an Appy link: $url")
            url.toByteArray().size > MAX_URL_BYTES -> {
                log.warning("Link URL is longer than $MAX_URL_BYTES bytes; it is resolved locally only")
                deliver(Links.localLink(url, settings.domains))
            }
            phase == Phase.SENT || phase == Phase.SKIPPED -> open(url, null, answersLaunch = false)
            launchUrl == null -> {
                launchUrl = url
                waitingForLink = false
                dispatchIfReady()
            }
            else -> laterLinks.add(url)
        }
    }

    fun track(name: String, properties: Map<*, *>, revenue: Double?, currency: String?, deduplicationId: String?, trackedAt: Long) {
        if (!tracking) return log.debug("Tracking is disabled; event \"$name\" was dropped")
        eventJson(name, properties, revenue, currency, deduplicationId, trackedAt, userId, log)?.let(events::add)
    }

    fun setUserId(id: String?) {
        val trimmed = id?.trim()?.takeIf { it.isNotEmpty() }
        userId = trimmed?.takeIf { it.codePointCount(0, it.length) <= MAX_USER_ID_LENGTH }
        if (trimmed != null && userId == null) log.error("User id is longer than $MAX_USER_ID_LENGTH characters; it was cleared")
        store[USER_ID_KEY] = userId
    }

    fun setTracking(enabled: Boolean) {
        if (enabled == tracking) return
        tracking = enabled
        store[Engine.TRACKING_KEY] = if (enabled) null else "false"
        if (enabled) {
            log.info("Tracking enabled")
            if (phase == Phase.SKIPPED) begin(0)
        } else {
            log.info("Tracking disabled; queued events were cleared")
            events.clear()
            if (phase == Phase.COLLECTING) skip()
        }
    }

    fun flush() = events.flush()

    private fun begin(linkWaitMillis: Long) {
        val current = ++generation
        phase = Phase.COLLECTING
        referrer = null
        events.closeGate()
        launchPending = nextOpenIsFirstLaunch && !launchAnswered
        if (launchPending) {
            scheduler.postDelayed(settings.deferredTimeoutMillis) { if (current == generation) launchTimedOut() }
        }
        waitingForLink = launchUrl == null && linkWaitMillis > 0
        if (waitingForLink) {
            scheduler.postDelayed(linkWaitMillis) {
                if (current == generation && waitingForLink) {
                    waitingForLink = false
                    dispatchIfReady()
                }
            }
        }
        waitingForReferrer = settings.collectReferrer && nextOpenIsFirstLaunch
        if (waitingForReferrer) {
            scheduler.postDelayed(REFERRER_TIMEOUT_MILLIS) { if (current == generation) onReferrer(null, timedOut = true) }
            requestReferrer(current, services.install.referrerSources())
        }
        dispatchIfReady()
    }

    private fun requestReferrer(current: Int, sources: List<Pair<String, ReferrerSource>>) {
        val (name, source) = sources.first()
        cancelReferrer = source.fetch { value ->
            scheduler.post { if (current == generation) onSourceReferrer(current, name, value, sources.drop(1)) }
        }
    }

    private fun onSourceReferrer(current: Int, name: String, value: String?, fallbacks: List<Pair<String, ReferrerSource>>) {
        if (!waitingForReferrer) return
        val usable = value?.takeIf { it.isNotBlank() && it.toByteArray().size <= MAX_REFERRER_BYTES }
        log.debug("Install referrer from $name: ${value ?: "unavailable"}")
        if (value != null && usable == null) log.warning("Install referrer from $name is empty or too long and was not sent")
        if (usable == null && fallbacks.isNotEmpty()) return requestReferrer(current, fallbacks)
        onReferrer(usable?.let { InstallReferrer(it, name) }, timedOut = false)
    }

    private fun onReferrer(value: InstallReferrer?, timedOut: Boolean) {
        if (!waitingForReferrer) return
        waitingForReferrer = false
        if (timedOut) cancelReferrer?.invoke()
        cancelReferrer = null
        referrer = value
        if (timedOut) log.debug("Install referrer timed out")
        dispatchIfReady()
    }

    private fun resolveEmailLink(url: String) {
        resolvingEmailLinks++
        log.debug("Resolving the email link $url")
        followEmailLink(url, 0) { destination ->
            resolvingEmailLinks--
            finishEmailLink(url, destination)
        }
    }

    private fun followEmailLink(url: String, redirects: Int, onResult: (String?) -> Unit) {
        scheduler.io {
            val result = try {
                services.transport.get(url)
            } catch (failure: Exception) {
                log.warning("Could not resolve the email link $url: ${failure.javaClass.simpleName} ${failure.message.orEmpty()}")
                null
            }
            scheduler.post {
                val location = result?.location?.takeIf { result.status in 300..399 }
                val next = location?.let { runCatching { URL(URL(url), it).toString() }.getOrNull() }
                when {
                    result == null -> onResult(null)
                    next == null -> {
                        log.warning("Could not resolve the email link $url: HTTP ${result.status} without a redirect")
                        onResult(null)
                    }
                    !Links.isEmailLink(next, settings.emailDomains) -> onResult(next)
                    redirects + 1 >= MAX_EMAIL_REDIRECTS -> {
                        log.warning("The email link $url redirected more than $MAX_EMAIL_REDIRECTS times")
                        onResult(null)
                    }
                    else -> followEmailLink(next, redirects + 1, onResult)
                }
            }
        }
    }

    private fun finishEmailLink(emailUrl: String, destination: String?) {
        if (destination != null) log.info("The email link $emailUrl points to $destination")
        when {
            destination == null -> deliver(ResolvedLink(emailUrl, null, "", emptyMap(), isDeferred = false, clickedAt = null))
            Links.isAppyLink(destination, settings.domains) -> return handleCandidate(destination)
            else -> deliver(Links.localLink(destination, settings.domains))
        }
        dispatchIfReady()
    }

    private fun dispatchIfReady() {
        if (phase != Phase.COLLECTING || waitingForLink || waitingForReferrer || resolvingEmailLinks > 0) return
        phase = Phase.SENT
        open(launchUrl, referrer, launchPending) { events.openGate() }
        sendLaterLinks()
    }

    private fun skip() {
        generation++
        launchPending = false
        cancelReferrer?.invoke()
        cancelReferrer = null
        waitingForLink = false
        waitingForReferrer = false
        phase = Phase.SKIPPED
        events.openGate()
        launchUrl?.let { open(it, null, answersLaunch = false) }
        launchUrl = null
        sendLaterLinks()
    }

    private fun sendLaterLinks() {
        val links = laterLinks.toList()
        laterLinks.clear()
        links.forEach { open(it, null, answersLaunch = false) }
    }

    private fun launchTimedOut() {
        if (!launchPending) return
        log.warning("No deep link answer within ${settings.deferredTimeoutMillis} ms; a later answer only updates the attribution")
        answerLaunch { launchUrl?.let { LinkResult(localLink(it)) } ?: LinkResult(null, AppyError.TIMEOUT) }
    }

    private fun answerLaunch(answer: () -> LinkResult) {
        if (!launchPending) return
        launchPending = false
        launchAnswered = true
        val result = answer()
        val link = result.link
        if (link != null) return deliver(link)
        log.info(result.error?.let { "No deep link for this launch: $it" } ?: "No deep link for this install")
        delivery.deliver(result)
    }

    private fun open(url: String?, installReferrer: InstallReferrer?, answersLaunch: Boolean, onDone: () -> Unit = {}) {
        val firstLaunch = nextOpenIsFirstLaunch
        openSent = true
        val (device, app) = services.environment()
        val sentReferrer = installReferrer?.takeIf { firstLaunch }
        val body = JSONObject()
            .put("installId", installId)
            .put("firstLaunch", firstLaunch)
            .putOpt("url", url)
            .putOpt("installReferrer", sentReferrer?.value)
            .putOpt("installReferrerSource", sentReferrer?.source)
            .putOpt("userId", userId?.takeIf { tracking })
            .put("trackingEnabled", tracking)
            .put("device", device)
            .put("app", app.putOpt("installer", services.install.installer))
            .put("sdkVersion", BuildConfig.SDK_VERSION)
        if (firstLaunch) settings.metaAppId?.let { appId -> services.metaReferrer(appId)?.let { body.put("metaInstallReferrer", it) } }
        log.debug("Sending open: firstLaunch=$firstLaunch, url=${url ?: "none"}, referrer=${sentReferrer?.source ?: "none"}")
        api.post(OPEN_PATH, body) { result ->
            if (result.ok && firstLaunch) store[FIRST_OPEN_KEY] = "true"
            val response = if (result.ok) parseOpen(result.body) else null
            when {
                answersLaunch -> answerLaunch {
                    val link = response?.link ?: url?.let(::localLink)
                    LinkResult(link, if (link != null || response != null) null else failure(result))
                }
                url != null -> deliver(response?.link ?: localLink(url))
            }
            response?.attribution?.takeIf { firstLaunch }?.let { attribution ->
                store[ATTRIBUTION_KEY] = response.attributionJson
                log.info(
                    when {
                        attribution.isOrganic -> "Install attribution: organic"
                        attribution.network != null -> "Install attribution: ${attribution.network.name}"
                        else -> "Install attribution: link ${attribution.linkSlug}"
                    },
                )
                delivery.attributions.post(attribution)
            }
            onDone()
        }
    }

    private fun failure(result: HttpResult): AppyError =
        if (result.ok || !result.retryable) AppyError.INVALID_RESPONSE else AppyError.NETWORK

    private fun localLink(url: String): ResolvedLink {
        log.warning("The API did not resolve $url; delivering the locally parsed link")
        return Links.localLink(url, settings.domains)
    }

    private fun deliver(link: ResolvedLink) {
        log.info("Deep link: slug=${link.slug}, deferred=${link.isDeferred}")
        val target = link.deeplinkUrl?.takeIf { settings.openDeferred && link.isDeferred }
        delivery.deliver(LinkResult(link), target?.let { url -> { services.openUrl(url) } })
    }

    companion object {
        const val OPEN_PATH: String = "/v1/sdk/open"
        const val INSTALL_ID_KEY: String = "installId"
        const val FIRST_OPEN_KEY: String = "firstOpenCompleted"
        const val USER_ID_KEY: String = "userId"
        const val ATTRIBUTION_KEY: String = "attribution"
        const val REFERRER_TIMEOUT_MILLIS: Long = 3_000
        const val MAX_EMAIL_REDIRECTS: Int = 5
        private const val MAX_URL_BYTES = 2_048
        private const val MAX_REFERRER_BYTES = 2_048
        private const val MAX_USER_ID_LENGTH = 128
    }
}
