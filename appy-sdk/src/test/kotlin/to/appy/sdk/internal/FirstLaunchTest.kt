package to.appy.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyAttribution
import to.appy.sdk.AppyError

class FirstLaunchTest {

    private val harness = Harness()
    private val noReferrer = Harness.config(collectInstallReferrer = false)
    private val attributed = AppyAttribution(false, "spring", "Spring campaign", "instagram", "story", "spring_sale", FakeScheduler.START)

    @Test
    fun `a deferred link is delivered once on the main thread after the install referrer`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.listen()
        harness.init()
        harness.advance(500)
        assertTrue(harness.opens().isEmpty())
        harness.referrer.complete(Harness.REFERRER)
        harness.scheduler.advance(0)
        assertTrue(harness.delivered.isEmpty())
        harness.runMain()
        val link = harness.delivered.single().link!!
        assertTrue(link.isDeferred)
        assertEquals("spring", link.slug)
        assertEquals("acme://items?item=42", link.deeplinkUrl)
        assertEquals(mapOf("item" to "42", "utm_source" to "instagram"), link.parameters)
        assertEquals(link, harness.engine.delivery.latest)
        harness.advance(60_000)
        assertEquals(1, harness.delivered.size)
        assertTrue(harness.opened.isEmpty())
    }

    @Test
    fun `an organic first launch is delivered once as not found`() {
        harness.transport.openResult = FakeTransport.status(200, FakeTransport.ORGANIC)
        harness.listen()
        harness.listenAttribution()
        harness.init(noReferrer)
        harness.advance(60_000)
        assertTrue(harness.delivered.single().isNotFound)
        assertNull(harness.engine.delivery.latest)
        assertEquals(AppyAttribution(true, null, null, null, null, null, null), harness.attributions.single())

        val next = Harness(harness.store)
        next.listen()
        next.init(noReferrer)
        next.advance(60_000)
        assertFalse(next.singleOpen().getBoolean("firstLaunch"))
        assertTrue(next.delivered.isEmpty())
    }

    @Test
    fun `errors before the timeout are delivered once as failed`() {
        val cases = listOf(
            FakeTransport.status(401, """{"error":{"code":"unauthorized"}}""") to AppyError.INVALID_RESPONSE,
            FakeTransport.status(200, "<html>") to AppyError.INVALID_RESPONSE,
            FakeTransport.OFFLINE to AppyError.NETWORK,
            FakeTransport.status(503) to AppyError.NETWORK,
        )
        for ((response, error) in cases) {
            val launch = Harness()
            launch.transport.openResult = response
            launch.listen()
            launch.init(noReferrer)
            launch.advance(4_999)
            assertEquals(listOf("failed $error"), launch.delivered.map { it.describe() })
            launch.advance(60_000)
            assertEquals(1, launch.delivered.size)
        }
    }

    @Test
    fun `no answer within the timeout is delivered as a timeout and a late answer only updates the attribution`() {
        harness.scheduler.latencyMillis = 6_000
        harness.transport.openResult = FakeTransport.deferred()
        harness.listen()
        harness.listenAttribution()
        harness.init(Harness.config(collectInstallReferrer = false, openDeferredDeepLinks = true))
        harness.advance(4_999)
        assertTrue(harness.delivered.isEmpty())
        harness.advance(1)
        assertEquals(listOf("failed TIMEOUT"), harness.delivered.map { it.describe() })
        harness.advance(60_000)
        assertEquals(1, harness.delivered.size)
        assertNull(harness.engine.delivery.latest)
        assertTrue(harness.opened.isEmpty())
        assertEquals(listOf(attributed), harness.attributions)
        assertEquals(attributed, harness.engine.attribution)
        assertEquals("true", harness.store[Session.FIRST_OPEN_KEY])
    }

    @Test
    fun `the timeout counts from the start and covers the install referrer wait`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.listen()
        harness.init(Harness.config(deferredTimeoutMillis = 1_000))
        harness.advance(1_000)
        assertTrue(harness.opens().isEmpty())
        assertEquals(listOf("failed TIMEOUT"), harness.delivered.map { it.describe() })
        harness.referrer.complete(Harness.REFERRER)
        harness.advance(60_000)
        assertEquals(1, harness.opens().size)
        assertEquals(1, harness.delivered.size)
    }

    @Test
    fun `a first launch opened by a link that times out delivers the link read from its url`() {
        harness.scheduler.latencyMillis = 10_000
        harness.transport.openResult = FakeTransport.withLink(FakeTransport.link(), FakeTransport.attribution())
        harness.listen()
        harness.init(noReferrer)
        harness.engine.handleUrl(Harness.LINK)
        harness.advance(5_000)
        val link = harness.delivered.single().link!!
        assertEquals(Harness.LINK, link.url)
        assertEquals("spring", link.slug)
        assertEquals(mapOf("item" to "42"), link.parameters)
        assertFalse(link.isDeferred)
        harness.advance(60_000)
        assertEquals(1, harness.delivered.size)
        assertTrue(harness.singleOpen().getBoolean("firstLaunch"))
    }

    @Test
    fun `a first launch opened by a link delivers that link once`() {
        harness.transport.openResult = FakeTransport.withLink(FakeTransport.link(), FakeTransport.attribution())
        harness.listen()
        harness.init()
        harness.engine.handleUrl(Harness.LINK)
        harness.advance(100)
        harness.referrer.complete(Harness.REFERRER)
        harness.advance(60_000)
        assertEquals(listOf("found spring"), harness.delivered.map { it.describe() })
        assertFalse(harness.delivered.single().link!!.isDeferred)
    }

    @Test
    fun `turning tracking off and on after a timeout does not deliver a second first launch result`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.listen()
        harness.init(Harness.config(deferredTimeoutMillis = 100))
        harness.advance(100)
        assertEquals(listOf("failed TIMEOUT"), harness.delivered.map { it.describe() })
        harness.engine.trackingEnabled = false
        harness.advance()
        harness.engine.trackingEnabled = true
        harness.advance()
        harness.referrer.complete(Harness.REFERRER)
        harness.advance(60_000)
        assertTrue(harness.singleOpen().getBoolean("firstLaunch"))
        assertEquals(1, harness.delivered.size)
    }

    @Test
    fun `a listener and an attribution listener set late receive the pending values once`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.init(noReferrer)
        harness.advance(60_000)
        assertTrue(harness.delivered.isEmpty() && harness.attributions.isEmpty())
        harness.listen()
        harness.listenAttribution()
        harness.runMain()
        harness.listen()
        harness.listenAttribution()
        harness.runMain()
        assertEquals(listOf("found spring"), harness.delivered.map { it.describe() })
        assertEquals(listOf(attributed), harness.attributions)
    }

    @Test
    fun `deferred deep links are opened in the app after the listener when enabled`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.listen()
        harness.init(Harness.config(collectInstallReferrer = false, openDeferredDeepLinks = true))
        harness.advance(60_000)
        assertEquals(listOf("listener found spring", "open acme://items?item=42"), harness.timeline)
    }

    @Test
    fun `deferred deep links are opened without a listener and the late listener still gets the result`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.init(Harness.config(collectInstallReferrer = false, openDeferredDeepLinks = true))
        harness.advance(60_000)
        assertEquals(listOf("acme://items?item=42"), harness.opened)
        harness.listen()
        harness.runMain()
        assertEquals(listOf("found spring"), harness.delivered.map { it.describe() })
        assertEquals(1, harness.opened.size)
    }

    @Test
    fun `only deferred links with an in-app deep link are opened, and only when enabled`() {
        val disabled = Harness()
        disabled.transport.openResult = FakeTransport.deferred()
        disabled.init(noReferrer)
        disabled.advance(60_000)
        assertTrue(disabled.opened.isEmpty())

        val withoutDeepLink = Harness()
        withoutDeepLink.transport.openResult = FakeTransport.withLink(FakeTransport.link(isDeferred = true).put("deeplinkUrl", JSONObject.NULL))
        withoutDeepLink.init(Harness.config(collectInstallReferrer = false, openDeferredDeepLinks = true))
        withoutDeepLink.advance(60_000)
        assertTrue(withoutDeepLink.opened.isEmpty())

        val direct = Harness().apply { completeFirstOpen() }
        direct.transport.openResult = FakeTransport.withLink(FakeTransport.link())
        direct.init(Harness.config(openDeferredDeepLinks = true))
        direct.engine.handleUrl(Harness.LINK)
        direct.advance(60_000)
        assertTrue(direct.opened.isEmpty())
    }

    @Test
    fun `the attribution is persisted and available on later launches without a callback`() {
        harness.transport.openResult = FakeTransport.deferred()
        assertNull(harness.engine.attribution)
        harness.init(noReferrer)
        assertNull(harness.engine.attribution)
        harness.advance(60_000)
        assertEquals(attributed, harness.engine.attribution)

        val next = Harness(harness.store)
        next.transport.openResult = FakeTransport.status(200, FakeTransport.NO_LINK)
        next.listenAttribution()
        next.init(noReferrer)
        assertEquals(attributed, next.engine.attribution)
        next.advance(60_000)
        assertTrue(next.attributions.isEmpty())
        assertEquals(attributed, next.engine.attribution)
    }

    @Test
    fun `a failed first launch keeps the stored attribution and retries on the next launch`() {
        harness.transport.openResult = FakeTransport.status(500)
        harness.init(noReferrer)
        harness.advance(60_000)
        assertNull(harness.engine.attribution)
        assertNull(harness.store[Session.FIRST_OPEN_KEY])

        val next = Harness(harness.store)
        next.transport.openResult = FakeTransport.deferred()
        next.listen()
        next.init(noReferrer)
        next.advance(60_000)
        assertTrue(next.singleOpen().getBoolean("firstLaunch"))
        assertTrue(next.delivered.single().link!!.isDeferred)
        assertEquals(attributed, next.engine.attribution)
    }
}
