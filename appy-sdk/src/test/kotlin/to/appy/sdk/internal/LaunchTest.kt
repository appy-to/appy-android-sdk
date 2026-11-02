package to.appy.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyLogLevel

class LaunchTest {

    private val harness = Harness()

    @Test
    fun `a link inside the launch window produces exactly one open carrying it`() {
        harness.completeFirstOpen()
        harness.init()
        harness.advance(200)
        assertTrue(harness.engine.handleUrl(Harness.LINK))
        harness.advance(60_000)
        assertEquals(Harness.LINK, harness.singleOpen().getString("url"))
        assertEquals(200L, harness.transport.calls.single().at)
    }

    @Test
    fun `without a link one open without url leaves when the window closes`() {
        harness.completeFirstOpen()
        harness.init()
        harness.advance(499)
        assertTrue(harness.opens().isEmpty())
        harness.advance(60_000)
        assertFalse(harness.singleOpen().has("url"))
        assertEquals(500L, harness.transport.calls.single().at)
    }

    @Test
    fun `a link after the launch open is sent as a later open`() {
        harness.completeFirstOpen()
        harness.init()
        harness.advance(600)
        harness.engine.handleUrl(Harness.LINK)
        harness.advance()
        assertEquals(listOf(false, true), harness.opens().map { it.has("url") })
        assertFalse(harness.opens()[1].getBoolean("firstLaunch"))
    }

    @Test
    fun `first launch waits for the install referrer and sends it with the link`() {
        harness.init()
        harness.advance(100)
        harness.engine.handleUrl(Harness.LINK)
        harness.advance(900)
        assertTrue(harness.opens().isEmpty())
        harness.referrer.complete(Harness.REFERRER)
        harness.advance()
        val open = harness.singleOpen()
        assertTrue(open.getBoolean("firstLaunch"))
        assertEquals(Harness.REFERRER, open.getString("installReferrer"))
        assertEquals(InstallSource.GOOGLE_PLAY, open.getString("installReferrerSource"))
        assertEquals(Harness.LINK, open.getString("url"))
        assertEquals("true", harness.store[Session.FIRST_OPEN_KEY])
    }

    @Test
    fun `the install referrer is awaited for at most three seconds`() {
        harness.init()
        harness.advance(2_999)
        assertTrue(harness.opens().isEmpty())
        harness.advance(1)
        assertFalse(harness.singleOpen().has("installReferrer"))
        assertEquals(1, harness.referrer.cancels)
        harness.referrer.complete(Harness.REFERRER)
        harness.advance(60_000)
        assertEquals(1, harness.opens().size)
    }

    @Test
    fun `the install referrer is only requested on first launch and when enabled`() {
        harness.completeFirstOpen()
        harness.init()
        harness.advance(500)
        assertEquals(0, harness.referrer.requests)

        val noReferrer = Harness()
        noReferrer.init(Harness.config(collectInstallReferrer = false))
        noReferrer.advance(500)
        assertEquals(0, noReferrer.referrer.requests)
        assertTrue(noReferrer.singleOpen().getBoolean("firstLaunch"))
    }

    @Test
    fun `first launch completion is persisted only after a 2xx`() {
        harness.transport.openResult = FakeTransport.OFFLINE
        harness.init(Harness.config(collectInstallReferrer = false))
        harness.advance(60_000)
        assertEquals(3, harness.opens().size)
        assertTrue(harness.opens().all { it.getBoolean("firstLaunch") })
        harness.transport.openResult = FakeTransport.status(200, FakeTransport.NO_LINK)
        harness.engine.handleUrl(Harness.LINK)
        harness.advance()
        assertFalse(harness.opens()[3].getBoolean("firstLaunch"))
        assertNull(harness.store[Session.FIRST_OPEN_KEY])

        val next = Harness(harness.store)
        next.transport.openResult = FakeTransport.status(403, """{"error":{"code":"plan_required","message":"no"}}""")
        next.init(Harness.config(collectInstallReferrer = false))
        next.advance(60_000)
        assertEquals(1, next.opens().size)
        assertNull(harness.store[Session.FIRST_OPEN_KEY])

        val third = Harness(harness.store)
        third.init(Harness.config(collectInstallReferrer = false))
        third.advance(500)
        assertTrue(third.singleOpen().getBoolean("firstLaunch"))
        assertEquals("true", harness.store[Session.FIRST_OPEN_KEY])
    }

    @Test
    fun `the open body follows the contract`() {
        harness.completeFirstOpen()
        harness.engine.setUserId("  u_123  ")
        harness.init(Harness.config(openDeferredDeepLinks = true, deferredTimeoutMillis = 1_000))
        harness.advance(500)
        val open = harness.singleOpen()
        assertEquals(harness.store[Session.INSTALL_ID_KEY], open.getString("installId"))
        assertEquals("u_123", open.getString("userId"))
        assertTrue(open.getBoolean("trackingEnabled"))
        assertFalse(open.has("minConfidence"))
        assertEquals("Pixel 8", open.getJSONObject("device").getString("model"))
        assertEquals("231", open.getJSONObject("app").getString("build"))
        assertEquals("1.0.0", open.getString("sdkVersion"))
        val headers = harness.transport.calls.single().headers
        assertEquals("Bearer ${Harness.KEY}", headers["Authorization"])
        assertEquals("android/1.0.0", headers["X-Appy-SDK"])
        assertEquals("application/json", headers["Accept"])

        val defaults = Harness()
        defaults.init()
        defaults.advance(3_000)
        listOf("url", "installReferrer", "installReferrerSource", "userId", "minConfidence").forEach { assertFalse(it, defaults.singleOpen().has(it)) }
        assertFalse(defaults.singleOpen().getJSONObject("app").has("installer"))
    }

    @Test
    fun `user ids longer than 128 characters are cleared`() {
        harness.completeFirstOpen()
        harness.init()
        harness.engine.setUserId("😀".repeat(128))
        harness.advance(500)
        assertEquals("😀".repeat(128), harness.singleOpen().getString("userId"))
        harness.engine.setUserId("x".repeat(129))
        harness.engine.handleUrl(Harness.LINK)
        harness.advance()
        assertFalse(harness.opens()[1].has("userId"))
        assertTrue(harness.messages(AppyLogLevel.ERROR).any { it.contains("128") })
    }

    @Test
    fun `view intents with Appy links are handled once`() {
        harness.completeFirstOpen()
        harness.init()
        var marks = 0
        assertTrue(harness.engine.handleIntent(Engine.ACTION_VIEW, Harness.LINK, alreadyHandled = false) { marks++ })
        assertTrue(harness.engine.handleIntent(Engine.ACTION_VIEW, Harness.LINK, alreadyHandled = true) { marks++ })
        assertTrue(harness.engine.handleIntent(Engine.ACTION_VIEW, "acme://items/42?appy_click_id=abc", alreadyHandled = false) { marks++ })
        assertFalse(harness.engine.handleIntent("android.intent.action.MAIN", Harness.LINK, alreadyHandled = false) { marks++ })
        assertFalse(harness.engine.handleIntent(Engine.ACTION_VIEW, "https://example.com/spring", alreadyHandled = false) { marks++ })
        assertFalse(harness.engine.handleIntent(Engine.ACTION_VIEW, null, alreadyHandled = false) { marks++ })
        harness.advance(60_000)
        assertEquals(2, marks)
        assertEquals(listOf(Harness.LINK, "acme://items/42?appy_click_id=abc"), harness.opens().map { it.getString("url") })
    }

    @Test
    fun `links before init are kept and resolved as the launch link`() {
        harness.completeFirstOpen()
        var marked = false
        assertTrue(harness.engine.handleIntent(Engine.ACTION_VIEW, Harness.LINK, alreadyHandled = false) { marked = true })
        assertTrue(harness.engine.handleUrl("https://example.com/not-appy"))
        assertTrue(harness.engine.handleUrl("acme://items?appy_click_id=abc"))
        assertFalse(harness.engine.handleUrl("acme://items/42"))
        assertTrue(marked)
        harness.init()
        harness.advance(60_000)
        assertEquals(listOf(Harness.LINK, "acme://items?appy_click_id=abc"), harness.opens().map { it.getString("url") })
    }

    @Test
    fun `at most ten links are kept before init`() {
        harness.completeFirstOpen()
        repeat(12) { harness.engine.handleUrl("https://acme.appy.to/link$it") }
        harness.init()
        harness.advance(60_000)
        assertEquals(10, harness.opens().size)
    }

    @Test
    fun `a second init is ignored`() {
        assertTrue(harness.init())
        assertFalse(harness.init())
        harness.advance(60_000)
        assertEquals(1, harness.opens().size)
        assertTrue(harness.messages(AppyLogLevel.WARNING).any { it.contains("more than once") })
    }

    @Test
    fun `a key without the publishable prefix is reported without printing it`() {
        harness.init(Harness.config(publishableKey = "appy_sk_live_secret_value"))
        assertTrue(harness.messages(AppyLogLevel.ERROR).any { it.contains("appy_pk_") })
        assertFalse(harness.logs.any { it.second.contains("appy_sk_live_secret_value") })
    }
}
