package to.appy.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyLogLevel

class EventsTest {

    private val harness = Harness().apply { completeFirstOpen() }
    private val quiet = Logger(AppyLogLevel.NONE) { _, _ -> }

    private fun started(): Harness = harness.apply {
        init()
        advance(500)
    }

    private fun validate(name: String = "purchase", properties: Map<*, *> = emptyMap<String, Any>(), revenue: Double? = null, currency: String? = null) =
        eventJson(name, properties, revenue, currency, null, FakeScheduler.START, "u_1", quiet)

    @Test
    fun `event names are trimmed and must be 1 to 64 printable characters`() {
        assertEquals("purchase", validate("  purchase ")?.getString("name"))
        assertNull(validate("   "))
        assertNull(validate("e".repeat(65)))
        assertNull(validate("sign\nup"))
        assertEquals("e".repeat(64), validate("e".repeat(64))?.getString("name"))
    }

    @Test
    fun `properties keep strings, numbers and booleans within the limits`() {
        val properties = LinkedHashMap<Any?, Any?>()
        properties["sku"] = "A1"
        properties["qty"] = 2
        properties["price"] = 9.5f
        properties["gift"] = false
        properties["note"] = "x".repeat(511) + "😀"
        listOf("list" to listOf(1), "nan" to Double.NaN, "none" to null, "" to "empty key", 42 to "not a string").forEach { properties[it.first] = it.second }
        properties["k".repeat(65)] = "long key"
        repeat(60) { properties["extra$it"] = it }
        val accepted = validate(properties = properties)!!.getJSONObject("properties")
        assertEquals(50, accepted.length())
        assertEquals(2L, accepted.get("qty"))
        assertEquals(9.5, accepted.getDouble("price"), 0.0)
        assertFalse(accepted.getBoolean("gift"))
        assertEquals("x".repeat(511), accepted.getString("note"))
        assertFalse(accepted.has("list") || accepted.has("nan") || accepted.has("none") || accepted.has("k".repeat(65)))
    }

    @Test
    fun `revenue needs a finite amount below 1e12 and a 3-letter currency`() {
        val valid = validate(revenue = 19.98, currency = " usd ")!!
        assertEquals(19.98, valid.getDouble("revenue"), 0.0)
        assertEquals("USD", valid.getString("currency"))
        listOf(1e12 to "USD", Double.POSITIVE_INFINITY to "USD", 5.0 to "US", 5.0 to null).forEach { (amount, code) ->
            val event = validate(revenue = amount, currency = code)!!
            assertFalse(event.has("revenue") || event.has("currency"))
        }
        assertFalse(validate(currency = "EUR")!!.has("currency"))
        assertEquals("u_1", validate()!!.getString("userId"))
    }

    @Test
    fun `events wait for the launch open and flush two seconds after the last track`() {
        harness.transport.openResult = FakeTransport.OFFLINE
        harness.init()
        harness.track("signup")
        harness.advance(3_000)
        assertTrue(harness.transport.batches().isEmpty())
        harness.advance(1_000)
        assertEquals(listOf("signup"), harness.transport.eventNames())

        harness.track("a")
        harness.advance(1_500)
        harness.track("b")
        harness.advance(1_999)
        assertEquals(1, harness.transport.batches().size)
        harness.advance(1)
        assertEquals(listOf("signup", "a", "b"), harness.transport.eventNames())
    }

    @Test
    fun `twenty events, flush and backgrounding send immediately`() {
        started()
        repeat(19) { harness.track("e$it") }
        harness.advance()
        assertTrue(harness.transport.batches().isEmpty())
        harness.track("e19")
        harness.advance()
        assertEquals(20, harness.transport.eventNames().size)
        harness.track("manual")
        harness.engine.flush()
        harness.advance()
        assertEquals(2, harness.transport.batches().size)
    }

    @Test
    fun `batches hold at most 100 events and 256 KB`() {
        harness.init()
        harness.advance()
        repeat(250) { harness.track("e$it") }
        harness.advance(500)
        assertEquals(listOf(100, 100, 50), harness.transport.batches().map { it.getJSONArray("events").length() })

        val large = (1..50).associate { "key$it" to "v".repeat(512) }
        repeat(30) { harness.track("big$it", large) }
        harness.engine.flush()
        harness.advance()
        val bigBatches = harness.transport.calls.drop(4)
        assertTrue(bigBatches.size > 1)
        assertTrue(bigBatches.all { it.body.toString().toByteArray().size <= 256 * 1_024 })
        assertNull(harness.store[Events.QUEUE_KEY])
    }

    @Test
    fun `rejected events in an accepted batch are logged and not resent`() {
        harness.transport.eventsResult = FakeTransport.status(
            202,
            """{"accepted":1,"duplicates":0,"rejected":[{"index":1,"id":"x","message":"properties.note is too long"}]}""",
        )
        started()
        harness.track("first")
        harness.track("second")
        harness.engine.flush()
        harness.advance(60_000)
        assertEquals(1, harness.transport.batches().size)
        assertTrue(harness.messages(AppyLogLevel.WARNING).any { it.contains("\"second\"") && it.contains("too long") })
        assertNull(harness.store[Events.QUEUE_KEY])
    }

    @Test
    fun `client errors drop the batch with an error log`() {
        harness.transport.eventsResult = FakeTransport.status(400, """{"error":{"code":"invalid_request","message":"bad","requestId":"r1"}}""")
        started()
        harness.track("a")
        harness.engine.flush()
        harness.advance(600_000)
        assertEquals(1, harness.transport.batches().size)
        assertNull(harness.store[Events.QUEUE_KEY])
        assertTrue(harness.messages(AppyLogLevel.ERROR).any { it.contains("invalid_request") && it.contains("r1") })
    }

    @Test
    fun `server errors and rate limits keep events and retry with backoff`() {
        val limited = FakeTransport.status(429, retryAfter = "30")
        harness.transport.enqueue(Events.EVENTS_PATH, FakeTransport.status(503), FakeTransport.OFFLINE, FakeTransport.status(500), limited)
        started()
        harness.track("a")
        harness.engine.flush()
        harness.advance(3_300)
        assertEquals(3, harness.transport.batches().size)
        assertTrue(harness.store[Events.QUEUE_KEY]!!.contains("\"a\""))
        harness.advance(4_400)
        assertEquals(4, harness.transport.batches().size)
        harness.advance(29_000)
        assertEquals(4, harness.transport.batches().size)
        harness.advance(1_000)
        assertEquals(5, harness.transport.batches().size)
        assertNull(harness.store[Events.QUEUE_KEY])
    }

    @Test
    fun `queued events survive a restart and opting out clears them`() {
        harness.transport.eventsResult = FakeTransport.OFFLINE
        started()
        harness.track("kept")
        harness.engine.flush()
        harness.advance(3_300)

        val next = Harness(harness.store)
        next.init()
        next.advance(500)
        assertEquals(listOf("kept"), next.transport.eventNames())

        val optOut = Harness().apply { completeFirstOpen() }
        optOut.transport.eventsResult = FakeTransport.OFFLINE
        optOut.init()
        optOut.advance(500)
        optOut.track("a")
        optOut.advance()
        optOut.engine.trackingEnabled = false
        optOut.track("b")
        optOut.advance(60_000)
        assertNull(optOut.store[Events.QUEUE_KEY])
        assertTrue(optOut.transport.batches().isEmpty())
    }

    @Test
    fun `events tracked before init keep their time and at most 100 are kept`() {
        repeat(105) { harness.track("early$it") }
        harness.init()
        harness.advance(500)
        assertEquals((0 until 100).map { "early$it" }, harness.transport.eventNames())
        val first = harness.transport.batches().single().getJSONArray("events").getJSONObject(0)
        assertEquals(FakeScheduler.START, Links.parseTimestamp(first.getString("timestamp")))
    }
}
