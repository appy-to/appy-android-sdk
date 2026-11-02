# Android quickstart

Download the SDK from [GitHub Releases](https://github.com/appy-to/appy-android-sdk/releases/tag/1.0.0). Maven Central distribution is being prepared. The hosted SDK endpoints require an Appy Enterprise app.

## 1. Get your app values

Register your package name and signing certificate SHA-256 fingerprints in **Apps & SDK**. For Play App Signing use Google's **app signing** certificate, not only your upload certificate. Copy the publishable key and link domain.

## 2. Install

Requires Android API 21+. Download `appy-sdk-1.0.0.aar` from the release above, put it in `app/libs/` and add to the app module:

```kotlin
dependencies {
    implementation(files("libs/appy-sdk-1.0.0.aar"))
    implementation("com.android.installreferrer:installreferrer:2.2")
}
```

The Install Referrer library is obtained from Google's Maven repository (`google()`), under its own license. Public Maven coordinates will replace the AAR step after publication.

## 3. Enable App Links

Add an `android:autoVerify="true"` HTTPS intent filter for your link domain to your main activity, plus your custom-scheme filter. Copy the complete [intent filter example](README.md#intent-filters). Follow [backup exclusion](README.md#keep-the-first-launch-a-first-launch) so restored SDK state does not suppress first-install attribution.

## 4. Configure and receive links

In your `Application.onCreate()`:

```kotlin
import to.appy.sdk.Appy
import to.appy.sdk.AppyConfig
import to.appy.sdk.AppyDeepLinkResult

Appy.init(this, AppyConfig(
    "appy_pk_REPLACE_WITH_YOUR_PUBLISHABLE_KEY",
    listOf("YOUR_SUBDOMAIN.appy.to")
))
Appy.setDeepLinkListener { result ->
    if (result is AppyDeepLinkResult.Found) {
        // Route using result.link.slug and result.link.parameters.
    }
}
```

The SDK reads the initial activity intent. Forward warm links from your activity:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    Appy.handleIntent(intent)
}
```

## 5. Check the integration

Tap an Appy link on a device with the app installed; verify the callback and dashboard. Test a new store install through a link to verify deferred delivery and referrer attribution. Check `https://YOUR_SUBDOMAIN.appy.to/.well-known/assetlinks.json` if links stay in the browser.

Use only a publishable key in the app. Keep debug logs off in production and validate route parameters. See the [full guide](README.md) for events, consent and troubleshooting.
