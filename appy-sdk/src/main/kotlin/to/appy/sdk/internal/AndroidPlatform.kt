package to.appy.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONObject
import to.appy.sdk.AppyConfig
import to.appy.sdk.AppyLogLevel
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object AndroidPlatform {
    private const val TAG = "Appy"
    private const val HANDLED_EXTRA = "to.appy.sdk.handled"
    private const val APPGALLERY_REFERRER_URI = "content://com.huawei.appmarket.commondata/item/5"
    private const val APPGALLERY_REFERRER_COLUMN = 5
    private const val APPGALLERY_LEGACY_REFERRER_COLUMN = 0
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun createEngine(): Engine {
        lateinit var engine: Engine
        val scheduler = ExecutorScheduler { failure ->
            engine.log.error("Unexpected internal error; the SDK kept running\n${failure.stackTraceToString()}")
        }
        engine = Engine(scheduler, { task -> mainHandler.post { task() } }, ::write)
        return engine
    }

    fun init(engine: Engine, context: Context, config: AppyConfig) {
        val firstCall = engine.init(config) { settings ->
            val install = InstallSource({ installer(context) }, PlayReferrerSource(context, engine.log), AppGalleryReferrerSource(context, engine.log))
            Services(
                PreferencesStore(context),
                UrlConnectionTransport(settings.timeoutMillis),
                install,
                { url -> openInApp(context, url, engine.log) },
                { appId -> MetaReferrerSource.read(context, appId, engine.log) },
            ) {
                environment(context)
            }
        }
        if (!firstCall) return
        val application = context as? Application
        if (application == null) engine.log.warning("Appy.init needs an Application context to handle launch intents")
        application?.registerActivityLifecycleCallbacks(LifecycleRelay(engine))
    }

    fun handleIntent(engine: Engine, intent: Intent?): Boolean {
        if (intent == null) return false
        val handled = runCatching { intent.getBooleanExtra(HANDLED_EXTRA, false) }.getOrDefault(false)
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        return engine.handleIntent(intent.action, intent.dataString, handled || fromRecents) {
            runCatching { intent.putExtra(HANDLED_EXTRA, true) }
        }
    }

    private fun openInApp(context: Context, url: String, log: Logger) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(HANDLED_EXTRA, true)
        runCatching { context.startActivity(intent) }
            .onFailure { log.warning("No activity of this app opens the deferred deep link $url: ${it.javaClass.simpleName}") }
    }

    private fun write(level: AppyLogLevel, message: String) {
        when (level) {
            AppyLogLevel.ERROR -> Log.e(TAG, message)
            AppyLogLevel.WARNING -> Log.w(TAG, message)
            AppyLogLevel.INFO -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    private fun environment(context: Context): Pair<JSONObject, JSONObject> {
        val locale = Locale.getDefault()
        val device = JSONObject()
            .put("platform", "android")
            .put("osVersion", Build.VERSION.RELEASE.orEmpty())
            .put("model", Build.MODEL.orEmpty())
            .put("locale", if (locale.country.isEmpty()) locale.language else "${locale.language}_${locale.country}")
        val info = runCatching { packageInfo(context) }.getOrNull()
        val app = JSONObject()
            .put("bundleId", context.packageName)
            .putOpt("version", info?.versionName?.takeIf { it.isNotBlank() })
            .putOpt("build", info?.let { versionCode(it).toString() })
        return device to app
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private class ExecutorScheduler(private val onFailure: (Exception) -> Unit) : Scheduler {
        private val state = ScheduledThreadPoolExecutor(1) { Thread(it, "appy-sdk").apply { isDaemon = true } }
        private val network = Executors.newSingleThreadExecutor { Thread(it, "appy-sdk-io").apply { isDaemon = true } }

        override fun now(): Long = System.currentTimeMillis()

        override fun post(task: () -> Unit) = state.execute(guard(task))

        override fun postDelayed(delayMillis: Long, task: () -> Unit): () -> Unit {
            val future = state.schedule(guard(task), delayMillis, TimeUnit.MILLISECONDS)
            return { future.cancel(false) }
        }

        override fun io(task: () -> Unit) = network.execute(guard(task))

        private fun guard(task: () -> Unit) = Runnable {
            try {
                task()
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }

    private class PreferencesStore(context: Context) : Store {
        private val preferences by lazy { context.getSharedPreferences("to.appy.sdk", Context.MODE_PRIVATE) }

        override fun get(key: String): String? = preferences.all[key]?.toString()

        override fun set(key: String, value: String?) {
            val editor = preferences.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            editor.apply()
        }
    }

    private class PlayReferrerSource(private val context: Context, private val log: Logger) : ReferrerSource {
        override fun fetch(onResult: (String?) -> Unit): () -> Unit {
            val finished = AtomicBoolean()
            val client = runCatching { InstallReferrerClient.newBuilder(context).build() }.getOrNull()
            fun finish(referrer: String?, problem: String?) {
                if (!finished.compareAndSet(false, true)) return
                runCatching { client?.endConnection() }
                if (problem != null) log.debug("Install referrer unavailable: $problem")
                onResult(referrer)
            }
            if (client == null) {
                finish(null, "Install Referrer library unavailable")
                return {}
            }
            val listener = object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) = when (responseCode) {
                    InstallReferrerResponse.OK -> finish(runCatching { client.installReferrer.installReferrer }.getOrNull(), null)
                    InstallReferrerResponse.SERVICE_UNAVAILABLE -> finish(null, "Play Store service unavailable")
                    InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> finish(null, "not supported by this Play Store")
                    InstallReferrerResponse.DEVELOPER_ERROR -> finish(null, "developer error")
                    else -> finish(null, "response code $responseCode")
                }

                override fun onInstallReferrerServiceDisconnected() = finish(null, "service disconnected")
            }
            runCatching { client.startConnection(listener) }.onFailure { finish(null, it.javaClass.simpleName) }
            return { if (finished.compareAndSet(false, true)) runCatching { client.endConnection() } }
        }
    }

    private class AppGalleryReferrerSource(private val context: Context, private val log: Logger) : ReferrerSource {
        override fun fetch(onResult: (String?) -> Unit): () -> Unit {
            val pending = AtomicBoolean(true)
            fun finish(referrer: String?) {
                if (pending.compareAndSet(true, false)) onResult(referrer)
            }
            runCatching { Thread({ finish(read()) }, "appy-sdk-referrer").apply { isDaemon = true }.start() }
                .onFailure { finish(null) }
            return { pending.set(false) }
        }

        private fun read(): String? = runCatching {
            context.contentResolver.query(Uri.parse(APPGALLERY_REFERRER_URI), null, null, arrayOf(context.packageName), null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                listOf(APPGALLERY_REFERRER_COLUMN, APPGALLERY_LEGACY_REFERRER_COLUMN).firstNotNullOfOrNull { index ->
                    if (cursor.columnCount > index) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
                }
            }
        }.onFailure { log.debug("AppGallery install referrer unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private object MetaReferrerSource {
        private val providers = listOf(
            "facebook" to "com.facebook.katana.provider.InstallReferrerProvider",
            "instagram" to "com.instagram.contentprovider.InstallReferrerProvider",
            "facebook_lite" to "com.facebook.lite.provider.InstallReferrerProvider",
        )
        private val columns = arrayOf("install_referrer", "is_ct", "actual_timestamp")

        fun read(context: Context, appId: String, log: Logger): JSONObject? {
            for ((source, authority) in providers) {
                val row = runCatching { query(context, authority, appId) }
                    .onFailure { log.debug("Meta install referrer unavailable from $source: ${it.javaClass.simpleName}") }
                    .getOrNull()
                if (row != null) return row.put("sourceApp", source)
            }
            return null
        }

        private fun query(context: Context, authority: String, appId: String): JSONObject? =
            context.contentResolver.query(Uri.parse("content://$authority/$appId"), columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val referrerIndex = cursor.getColumnIndex("install_referrer")
                val referrer = if (referrerIndex >= 0) cursor.getString(referrerIndex) else null
                if (referrer.isNullOrBlank() || referrer.length > MAX_META_REFERRER_LENGTH) return@use null
                val clickIndex = cursor.getColumnIndex("is_ct")
                val timeIndex = cursor.getColumnIndex("actual_timestamp")
                JSONObject()
                    .put("installReferrer", referrer)
                    .putOpt("isClickThrough", if (clickIndex >= 0 && !cursor.isNull(clickIndex)) cursor.getInt(clickIndex) == 1 else null)
                    .putOpt("actualTimestamp", if (timeIndex >= 0 && !cursor.isNull(timeIndex)) cursor.getLong(timeIndex) else null)
            }

        private const val MAX_META_REFERRER_LENGTH = 8_192
    }

    private class LifecycleRelay(private val engine: Engine) : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (savedInstanceState == null) handleIntent(engine, activity.intent)
        }

        override fun onActivityStarted(activity: Activity) {
            started++
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0 && !activity.isChangingConfigurations) engine.flush()
        }

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
