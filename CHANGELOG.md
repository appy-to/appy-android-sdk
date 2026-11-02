# Changelog

All notable changes to the Appy Android SDK are documented in this file. The SDK follows [Semantic Versioning](https://semver.org).

## 1.0.0 - Unreleased

First release.

### Added

- `Appy.init` with `AppyConfig(publishableKey, linkDomains)` and the properties `logLevel`, `openDeferredDeepLinks`, `deferredDeepLinkTimeoutMillis`, `launchLinkWaitMillis`, `requestTimeoutMillis`, `collectInstallReferrer` and `apiBaseUrl`, settable from Kotlin and Java.
- Automatic handling of Appy links in launch intents, `Appy.handleIntent` for `onNewIntent` and `Appy.handleUri`. Intents are processed once (`to.appy.sdk.handled` extra) and intents replayed from Recents are ignored. Before `init`, up to 10 URLs are kept and `http`/`https` URLs or URLs with `appy_click_id` return `true`.
- One launch open per cold start that waits for a link and, on a first launch, for the install referrer (at most 3 s for all referrer sources together).
- Deferred deep linking through the install referrer of the store that installed the app; Appy matches the install to the link on the server. Google Play installs and installs from unknown or other stores read the Play Install Referrer. Huawei AppGallery installs (`com.huawei.appmarket`) read AppGallery's attribution provider (`content://com.huawei.appmarket.commondata/item/5`, AppGallery 10.5.0.300 or newer, no HMS Core) and fall back to the Play Install Referrer. The referrer is sent unmodified as `installReferrer` with `installReferrerSource` (`google_play` or `huawei_appgallery`).
- The installing store's package name, from `InstallSourceInfo.getInstallingPackageName()` on Android 11 and newer and `getInstallerPackageName()` before, sent with every open as `app.installer` (up to 128 characters, omitted when unknown).
- Manifest `<queries>` entries for the `com.android.vending` package and the `com.huawei.appmarket.commondata` provider, so both referrer sources stay visible on Android 11 and newer.
- `Appy.setDeepLinkListener` with `AppyDeepLinkResult` (`Found(AppyDeepLink)`, `NotFound`, `Failed(AppyError)`), always on the main thread. The first launch after install is answered exactly once, at the latest after `deferredDeepLinkTimeoutMillis` (default 5 s, then `Failed(AppyError.TIMEOUT)`); every Appy link that opens the app is delivered as `Found`. The most recent result waits for a listener set later and is delivered once. The last link is available as `Appy.latestDeepLink`.
- `AppyDeepLink` with `url` and `deeplinkUri` (`Uri`), `slug`, `parameters`, `isDeferred` and `clickedAt`.
- `openDeferredDeepLinks`: after the listener, the SDK opens a deferred link's `deeplinkUri` with an `ACTION_VIEW` intent restricted to the app's own package.
- `AppyConfig.emailLinkDomains` for email click-tracking domains that open the app. Launch intents, `handleIntent` and `handleUri` resolve their links with GET requests that do not follow redirects (at most 5, while they stay on those domains), then treat an Appy destination like any Appy link and deliver any other destination, or the tracking URL when it cannot be resolved, as `Found` with an empty slug. An email link in the launch window holds the launch open until it is resolved.
- Meta Install Referrer: with `AppyConfig.metaAppId` set, the first launch reads the Facebook, Instagram or Facebook Lite install referrer provider and sends it as `metaInstallReferrer`. The manifest declares `<queries>` for `com.facebook.katana`, `com.instagram.android` and `com.facebook.lite`.
- `AppyAttribution.network` (`AppyAdNetwork`) when Google Ads (a `gclid` in the Play install referrer) or Meta attributed the install instead of an Appy link.
- Install attribution: `Appy.setAttributionListener` with `AppyAttribution` (organic or the link, its title, `utm_source`, `utm_medium`, `utm_campaign` and the tap time), called once when the first launch is answered, also after a timeout, and `Appy.attribution`, kept across launches.
- Local fallback link, read from the URL, when the API is unreachable or does not resolve an opened link. Reserved `appy_` parameters are removed case-insensitively.
- Event tracking with validation that matches the API, a persisted queue of up to 1000 events, batches of up to 100 events and 256 KB, and logging of events the API rejects individually.
- `deduplicationId` on `Appy.track` (Kotlin default `null`, Java overload with a fifth argument): sent as the event id, so the same purchase tracked twice, or from the app and from your backend's server events with the same id (such as the Google Play order id), counts once. It is trimmed and must be 1 to 128 printable ASCII characters without spaces; an invalid one is logged as an error and replaced with a generated id, and the event is kept. A second event whose id is still waiting in the queue is dropped with an info log.
- Retries with exponential backoff for network errors, `429` and `5xx`, honouring `Retry-After` (inline up to 10 s).
- `Appy.setUserId` (up to 128 characters) and the stored `Appy.isTrackingEnabled` switch; turning tracking back on sends the launch open that was skipped.
- `Appy.buildLink` for share links.
- Small footprint: 9 source files, one dependency (Install Referrer 2.2), two background threads created on first use and a short-lived one for the AppGallery referrer.
