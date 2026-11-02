package to.appy.sdk.internal

internal class InstallReferrer(val value: String, val source: String)

internal class InstallSource(
    readInstaller: () -> String?,
    private val play: ReferrerSource,
    private val appGallery: ReferrerSource,
) {
    val installer: String? by lazy { readInstaller()?.takeIf { it.isNotBlank() && it.length <= MAX_INSTALLER_LENGTH } }

    fun referrerSources(): List<Pair<String, ReferrerSource>> = when (installer) {
        APPGALLERY_PACKAGE -> listOf(HUAWEI_APPGALLERY to appGallery, GOOGLE_PLAY to play)
        else -> listOf(GOOGLE_PLAY to play)
    }

    companion object {
        const val GOOGLE_PLAY: String = "google_play"
        const val HUAWEI_APPGALLERY: String = "huawei_appgallery"
        const val APPGALLERY_PACKAGE: String = "com.huawei.appmarket"
        private const val MAX_INSTALLER_LENGTH = 128
    }
}
