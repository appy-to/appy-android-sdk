# Add Appy to your Android app

Use Appy to open the right screen in **your existing Android app** when someone taps one of your links. Requires Android API 21+.

## 1. Get the two values for your app

In the [Appy dashboard](https://appy.to), open **Apps & SDK → New app**, register your own app, and open its **SDK setup** card. Copy **Publishable key** and **Link domain**. If it is already registered, open that app instead of creating another one.

[Which dashboard fields do I fill in?](SETUP.md#1-add-your-app-in-the-dashboard)

In every snippet below:

- Replace `YOUR_PUBLISHABLE_KEY` with the **entire key** copied from the dashboard.
- Replace `your-app.appy.to` with the **Link domain** copied from the dashboard.
- Keep names such as `Appy`, `AppySDK` and the package imports. They name the library you are adding; your own app keeps its name and identifiers.

The hosted SDK features require an Enterprise plan. You do not need a secret API key or a `curl` registration request for these steps.

## 2. Add the SDK from Maven Central

Ensure your project's dependency repositories include `google()` and `mavenCentral()`, then add this to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("to.appy:appy-sdk:1.0.0")
}
```

Keep the `to.appy:appy-sdk` library coordinates. Gradle brings in its native dependencies automatically.

## 3. Connect your link domain to your app

Use the Link domain copied from the dashboard in an HTTPS App Link intent filter. [Copy the complete manifest example](README.md#intent-filters), replacing only the example domain and your custom scheme.

In the dashboard, register the certificate for the build you are testing. A Play Store build uses Google's **app signing certificate**; a debug build uses your debug certificate. [Where to find each value](SETUP.md#1-add-your-app-in-the-dashboard).

## 4. Paste your values and receive links

Add these imports at the top of your Kotlin file:

```kotlin
import to.appy.sdk.Appy
import to.appy.sdk.AppyConfig
import to.appy.sdk.AppyDeepLinkResult
```

In your own `Application` class, inside `onCreate()` after `super.onCreate()`:

```kotlin
Appy.init(this, AppyConfig(
    "YOUR_PUBLISHABLE_KEY",
    listOf("your-app.appy.to")
))
Appy.setDeepLinkListener { result ->
    if (result is AppyDeepLinkResult.Found) {
        // Use result.link.slug and result.link.parameters to choose your screen.
    }
}
```

Keep the `to.appy.sdk` imports: they identify the library. They do **not** replace your app's own package name. Declare your `Application` class in your manifest if you have not already done so; [full example](README.md#initialise).

The SDK reads the first activity intent. In your activity, forward links received while the app is already open:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    Appy.handleIntent(intent)
}
```

## 5. Test a real link

Create a link for this dashboard app, install your app and tap the link. Check the deep-link callback. Follow [backup exclusion](README.md#keep-the-first-launch-a-first-launch) before testing fresh installs.

For events, consent and troubleshooting, see the [full guide](README.md). If an example value is unclear, use [this replacement table](SETUP.md#2-know-which-values-to-replace).
