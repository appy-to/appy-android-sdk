package to.appy.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTest {

    private val harness = Harness().apply { completeFirstOpen() }

    @Test
    fun `links that open the app reach the listener on the main thread as found`() {
        harness.transport.openResult = FakeTransport.withLink(FakeTransport.link())
        harness.listen()
        harness.init()
        harness.engine.handleUrl(Harness.LINK)
        harness.scheduler.advance(0)
        assertTrue(harness.delivered.isEmpty())
        assertEquals(1, harness.mainPending)
        harness.runMain()
        val link = harness.delivered.single().link!!
        assertEquals("https://acme.appy.to/spring?item=42", link.url)
        assertEquals("acme://items?item=42", link.deeplinkUrl)
        assertEquals("spring", link.slug)
        assertEquals(mapOf("item" to "42", "utm_source" to "instagram"), link.parameters)
        assertFalse(link.isDeferred)
        assertEquals(FakeScheduler.START, link.clickedAt)
        assertSame(link, harness.engine.delivery.latest)
        harness.advance(60_000)
        assertEquals(1, harness.delivered.size)
    }

    @Test
    fun `a later cold start without a link delivers nothing`() {
        harness.transport.openResult = FakeTransport.deferred()
        harness.listen()
        harness.listenAttribution()
        harness.init()
        harness.advance(60_000)
        assertEquals(1, harness.opens().size)
        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.attributions.isEmpty())
        assertNull(harness.engine.attribution)
        assertNull(harness.engine.delivery.latest)
    }

    @Test
    fun `a listener set late receives the pending result once`() {
        harness.transport.openResult = FakeTransport.withLink(FakeTransport.link())
        harness.init()
        harness.engine.handleUrl(Harness.LINK)
        harness.advance(60_000)
        assertTrue(harness.delivered.isEmpty())
        harness.listen()
        assertTrue(harness.delivered.isEmpty())
        harness.runMain()
        assertEquals(listOf("found spring"), harness.delivered.map { it.describe() })
        harness.listen()
        harness.runMain()
        assertEquals(1, harness.delivered.size)
    }

    @Test
    fun `only the most recent result waits, and clearing the listener keeps it waiting`() {
        harness.transport.enqueue(
            Session.OPEN_PATH,
            FakeTransport.status(200, FakeTransport.NO_LINK),
            FakeTransport.withLink(FakeTransport.link(url = "https://acme.appy.to/first")),
            FakeTransport.withLink(FakeTransport.link(url = "https://acme.appy.to/second")),
        )
        harness.init()
        harness.advance(500)
        harness.engine.handleUrl("https://acme.appy.to/first")
        harness.engine.handleUrl("https://acme.appy.to/second")
        harness.advance()
        harness.listen()
        harness.engine.delivery.links.listen(null)
        harness.runMain()
        assertTrue(harness.delivered.isEmpty())
        harness.listen()
        harness.runMain()
        harness.listen()
        harness.runMain()
        assertEquals(listOf("https://acme.appy.to/second"), harness.delivered.map { it.link?.url })
    }

    @Test
    fun `an unreachable API yields the local link after three attempts`() {
        harness.transport.openResult = FakeTransport.OFFLINE
        harness.listen()
        harness.init()
        val url = "https://acme.appy.to/spring?item=42&item=43&APPY_click_id=abc&Appy_Source=x&utm_source=mail"
        harness.engine.handleUrl(url)
        harness.advance(60_000)
        assertEquals(3, harness.opens().size)
        val times = harness.transport.calls.map { it.at }
        assertTrue(times[1] - times[0] in 1_000..1_100)
        assertTrue(times[2] - times[1] in 2_000..2_200)
        val link = harness.delivered.single().link!!
        assertEquals(url, link.url)
        assertEquals("spring", link.slug)
        assertEquals(mapOf("item" to "42", "utm_source" to "mail"), link.parameters)
        assertFalse(link.isDeferred)
        assertNull(link.deeplinkUrl)
        assertNull(link.clickedAt)
    }

    @Test
    fun `a null link for an opened url falls back locally, a launch without url delivers nothing`() {
        harness.listen()
        harness.init()
        harness.advance(500)
        assertTrue(harness.delivered.isEmpty())
        harness.engine.handleUrl("acme://items/42?appy_click_id=abc&color=blue")
        harness.advance()
        val link = harness.delivered.single().link!!
        assertEquals("", link.slug)
        assertEquals(mapOf("color" to "blue"), link.parameters)
    }

    @Test
    fun `urls longer than the API accepts are resolved locally without a request`() {
        harness.listen()
        harness.init()
        harness.advance(500)
        harness.engine.handleUrl("https://acme.appy.to/spring?note=" + "a".repeat(2_048))
        harness.advance()
        assertEquals(1, harness.opens().size)
        assertEquals("spring", harness.delivered.single().link?.slug)
    }

    @Test
    fun `tracking off skips the launch open but still resolves links without the user id`() {
        harness.engine.trackingEnabled = false
        harness.engine.setUserId("u_123")
        harness.transport.openResult = FakeTransport.withLink(FakeTransport.link())
        harness.listen()
        harness.init()
        harness.advance(60_000)
        assertTrue(harness.opens().isEmpty())
        assertEquals(0, harness.referrer.requests)
        assertTrue(harness.engine.handleUrl(Harness.LINK))
        harness.advance()
        val open = harness.singleOpen()
        assertFalse(open.getBoolean("trackingEnabled"))
        assertFalse(open.has("userId"))
        assertEquals(1, harness.delivered.size)
        assertEquals("false", harness.store[Engine.TRACKING_KEY])
        assertFalse(harness.engine.trackingEnabled)
    }

    @Test
    fun `turning tracking back on sends the skipped launch open with the referrer`() {
        val fresh = Harness()
        fresh.engine.trackingEnabled = false
        fresh.listen()
        fresh.init()
        fresh.advance(60_000)
        assertTrue(fresh.opens().isEmpty())
        assertTrue(fresh.delivered.isEmpty())
        fresh.engine.trackingEnabled = true
        fresh.advance()
        fresh.referrer.complete(Harness.REFERRER)
        fresh.advance()
        val open = fresh.singleOpen()
        assertTrue(open.getBoolean("firstLaunch"))
        assertTrue(open.getBoolean("trackingEnabled"))
        assertEquals(Harness.REFERRER, open.getString("installReferrer"))
        assertEquals(listOf("not found"), fresh.delivered.map { it.describe() })
    }

    @Test
    fun `consent on a later launch starts the first launch lookup and its timeout then`() {
        val first = Harness()
        first.engine.trackingEnabled = false
        first.init()
        first.track("before_consent")
        first.advance(60_000)
        assertTrue(first.transport.calls.isEmpty())

        val later = Harness(first.store)
        later.listen()
        later.init()
        later.advance(60_000)
        assertTrue(later.transport.calls.isEmpty() && later.delivered.isEmpty() && later.referrer.requests == 0)
        later.scheduler.latencyMillis = 10_000
        later.engine.trackingEnabled = true
        later.advance(4_999)
        assertEquals(1, later.referrer.requests)
        assertTrue(later.delivered.isEmpty())
        later.advance(1)
        assertEquals(listOf("failed TIMEOUT"), later.delivered.map { it.describe() })
        later.advance(60_000)
        assertTrue(later.singleOpen().getBoolean("firstLaunch"))
        assertTrue(later.transport.batches().isEmpty())
    }

    @Test
    fun `turning tracking off during the launch window resolves the pending link untracked`() {
        val fresh = Harness()
        fresh.listen()
        fresh.init()
        fresh.engine.handleUrl(Harness.LINK)
        fresh.advance(100)
        fresh.engine.trackingEnabled = false
        fresh.advance(60_000)
        val open = fresh.singleOpen()
        assertEquals(Harness.LINK, open.getString("url"))
        assertFalse(open.getBoolean("trackingEnabled"))
        assertFalse(open.has("installReferrer"))
        assertEquals(1, fresh.referrer.cancels)
        assertEquals(listOf(Harness.LINK), fresh.delivered.map { it.link?.url })
    }
}
