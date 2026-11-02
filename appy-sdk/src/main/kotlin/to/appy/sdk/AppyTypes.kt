package to.appy.sdk

import android.net.Uri

public enum class AppyLogLevel { NONE, ERROR, WARNING, INFO, DEBUG }

public enum class AppyError { TIMEOUT, NETWORK, INVALID_RESPONSE }

public fun interface AppyDeepLinkListener {
    public fun onDeepLink(result: AppyDeepLinkResult)
}

public fun interface AppyAttributionListener {
    public fun onAttribution(attribution: AppyAttribution)
}

public class AppyConfig @JvmOverloads public constructor(
    public val publishableKey: String,
    public val linkDomains: List<String> = emptyList(),
) {
    public var emailLinkDomains: List<String> = emptyList()
    public var apiBaseUrl: String = "https://api.appy.to"
    public var logLevel: AppyLogLevel = AppyLogLevel.ERROR
    public var launchLinkWaitMillis: Long = 500
    public var requestTimeoutMillis: Int = 10_000
    public var collectInstallReferrer: Boolean = true
    public var openDeferredDeepLinks: Boolean = false
    public var deferredDeepLinkTimeoutMillis: Long = 5_000
    public var metaAppId: String? = null
}

public sealed class AppyDeepLinkResult {
    public data class Found(public val link: AppyDeepLink) : AppyDeepLinkResult()

    public data object NotFound : AppyDeepLinkResult()

    public data class Failed(public val error: AppyError) : AppyDeepLinkResult()
}

public data class AppyDeepLink(
    public val url: Uri?,
    public val deeplinkUri: Uri?,
    public val slug: String,
    public val parameters: Map<String, String>,
    public val isDeferred: Boolean,
    public val clickedAt: Long?,
)

public data class AppyAttribution(
    public val isOrganic: Boolean,
    public val linkSlug: String?,
    public val linkTitle: String?,
    public val source: String?,
    public val medium: String?,
    public val campaign: String?,
    public val clickedAt: Long?,
    public val network: AppyAdNetwork? = null,
)

public data class AppyAdNetwork(
    public val name: String,
    public val campaignId: String?,
    public val campaignName: String?,
    public val adGroupId: String?,
    public val adGroupName: String?,
    public val adId: String?,
    public val clickId: String?,
    public val touch: String?,
)
