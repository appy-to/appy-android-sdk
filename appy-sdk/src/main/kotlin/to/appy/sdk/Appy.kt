package to.appy.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import to.appy.sdk.internal.AndroidPlatform
import to.appy.sdk.internal.Engine
import to.appy.sdk.internal.LinkResult
import to.appy.sdk.internal.ResolvedLink

public object Appy {

    public const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    private val engine: Engine by lazy { AndroidPlatform.createEngine() }

    @JvmStatic
    public fun init(context: Context, config: AppyConfig) {
        AndroidPlatform.init(engine, context.applicationContext ?: context, config)
    }

    @JvmStatic
    public fun setDeepLinkListener(listener: AppyDeepLinkListener?) {
        engine.delivery.links.listen(listener?.let { target -> { result: LinkResult -> target.onDeepLink(result.toPublic()) } })
    }

    @JvmStatic
    public fun setAttributionListener(listener: AppyAttributionListener?) {
        engine.delivery.attributions.listen(listener?.let { target -> { attribution: AppyAttribution -> target.onAttribution(attribution) } })
    }

    @JvmStatic
    public val latestDeepLink: AppyDeepLink?
        get() = engine.delivery.latest?.toPublic()

    @JvmStatic
    public val attribution: AppyAttribution?
        get() = engine.attribution

    @JvmStatic
    public fun handleIntent(intent: Intent?): Boolean = AndroidPlatform.handleIntent(engine, intent)

    @JvmStatic
    public fun handleUri(uri: Uri): Boolean = engine.handleUrl(uri.toString())

    @JvmStatic
    @JvmOverloads
    public fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        revenue: Double? = null,
        currency: String? = null,
        deduplicationId: String? = null,
    ) {
        engine.track(name, properties, revenue, currency, deduplicationId)
    }

    @JvmStatic
    public fun setUserId(userId: String?) {
        engine.setUserId(userId)
    }

    @JvmStatic
    public var isTrackingEnabled: Boolean
        get() = engine.trackingEnabled
        set(value) {
            engine.trackingEnabled = value
        }

    @JvmStatic
    @JvmOverloads
    public fun buildLink(slug: String, parameters: Map<String, String> = emptyMap()): Uri? =
        engine.buildLink(slug, parameters)?.let(Uri::parse)

    @JvmStatic
    public fun flush() {
        engine.flush()
    }

    private fun LinkResult.toPublic(): AppyDeepLinkResult = when {
        link != null -> AppyDeepLinkResult.Found(link.toPublic())
        error != null -> AppyDeepLinkResult.Failed(error)
        else -> AppyDeepLinkResult.NotFound
    }

    private fun ResolvedLink.toPublic(): AppyDeepLink =
        AppyDeepLink(url?.let(Uri::parse), deeplinkUrl?.let(Uri::parse), slug, parameters, isDeferred, clickedAt)
}
