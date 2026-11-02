# Before you start: your app and the Appy SDK

The **Appy SDK is a library you add to your own mobile app**. Your app keeps its own name, bundle ID and Android package name. You do not need to create an app called Appy.

## 1. Add your app in the dashboard

1. Sign in at [appy.to](https://appy.to) and open **Apps & SDK**.
2. Select **New app**. Enter your app's display name and choose its link subdomain.
3. Select the platforms you use and fill in their identifiers from the table below.
4. Select **Register app**, open that app, and find **SDK setup**.
5. Copy the **Publishable key** and **Link domain** shown there. These are the two values used by every SDK.

The hosted SDK features require an Enterprise plan. Creating the app through a terminal or calling the developer API is an alternative for server automation; it is not a step in this setup.

| Dashboard field | What you enter | Where it comes from |
| --- | --- | --- |
| App name | The name of your own app | Your product name; it does not have to be Appy |
| Subdomain | An available name for your links | Your choice in the dashboard; it becomes a host such as `your-app.appy.to` and cannot be changed later |
| iOS Team ID | The team that signs your iOS app | Apple Developer account → Membership details |
| iOS Bundle ID | Your existing iOS app identifier | Xcode → your app target → General → Bundle Identifier |
| Android Package name | Your existing Android app identifier | The `applicationId` in your app module's Gradle configuration |
| Android SHA-256 fingerprints | The signing certificate fingerprints for the builds you test or distribute | For Play builds: Play Console → App integrity → App signing. For debug builds: `./gradlew :app:signingReport` |

If the same product runs on iOS and Android, select both platforms in one dashboard app. Flutter and React Native apps use these same iOS and Android identifiers.

## 2. Know which values to replace

| You see in a guide | What to do |
| --- | --- |
| `Appy`, `AppySDK`, `AppyConfiguration`, `AppyConfig` | Keep these names. They belong to the library, like `Foundation` or `React`. |
| `appy_sdk`, `react-native-appy`, `to.appy.sdk` | Keep these package/import names. They are not your app's name or ID. |
| `YOUR_PUBLISHABLE_KEY` | Replace the entire placeholder with the full Publishable key copied from SDK setup. A real value starts with `appy_pk_`; do not add that prefix yourself. |
| `your-app.appy.to` | Example only. Replace it with the full Link domain copied from SDK setup. Use the host without `https://` or a path in `linkDomains`. |
| `com.example.yourapp` | Example only. Replace it with your own Bundle ID or Android application ID where the guide asks for one. |
| `yourapp` in a URL scheme | Example only. Use the scheme your own app registers, such as `yourapp://`. It can differ from the Appy link subdomain. |
| `YourApp`, `YourApplication`, `DeepLinkRouter`, `router`, or a routing comment | Your own app/widget/class/navigation code. The guide is showing where to connect the SDK to it. |

`appy.to` in the SDK download URL, package names and API service URL identifies the provider. Keep those addresses. Only customer-specific values marked above need replacing.

## 3. Use the right key

Use the **Publishable key** from the app's **SDK setup** card. It is designed to be included in a mobile app. A secret API key (`appy_sk_…`) is for your backend and is not needed for SDK setup.

The small example project in this repository is a demo, not your production app. When testing real links with it, register its actual iOS/Android identifiers in your dashboard app or change the demo identifiers to match a test app you own.

Return to the [quickstart](QUICKSTART.md) to install the library, paste the two dashboard values and connect incoming links.
