package to.appy.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyLogLevel
import java.util.UUID

class DeduplicationTest {

    private val harness = Harness().apply { completeFirstOpen() }
    private val errors = mutableListOf<String>()
    private val log = Logger(AppyLogLevel.DEBUG) { level, message -> if (level == AppyLogLevel.ERROR) errors.add(message) }

    private fun started(): Harness = harness.apply {
        init()
        advance(500)
    }

    private fun idFor(deduplicationId: String?): String =
        eventJson("purchase", emptyMap<String, Any>(), 19.98, "USD", deduplicationId, FakeScheduler.START, null, log)!!.getString("id")

    private fun isGenerated(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun Harness.sendNow() {
        engine.flush()
        advance()
    }

    @Test
    fun `a deduplication id is trimmed and sent as the event id`() {
        started()
        harness.track("purchase", mapOf("sku" to "A1"), 19.98, "USD", deduplicationId = "  GPA.3372-1234-5678-90123 ")
        harness.track("signup")
        harness.sendNow()
        val (purchase, signup) = harness.transport.sentEvents()
        assertEquals("GPA.3372-1234-5678-90123", purchase.getString("id"))
        assertEquals("purchase", purchase.getString("name"))
        assertEquals("USD", purchase.getString("currency"))
        assertTrue(isGenerated(signup.getString("id")))
        assertTrue(harness.messages(AppyLogLevel.ERROR).isEmpty())
    }

    @Test
    fun `ids of 1 to 128 printable ASCII characters without spaces are kept`() {
        val printable = ('!'..'~').joinToString("")
        listOf("!", "~".repeat(128), printable, "2000000123456789", "\tord_981\n").forEach { candidate ->
            assertEquals(candidate.trim(), idFor(candidate))
        }
        assertTrue(isGenerated(idFor(null)))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `invalid ids fall back to a generated id with an error naming the problem`() {
        val cases = listOf(
            "" to "is empty",
            "   " to "is empty",
            "x".repeat(129) to "longer than 128",
            "order 42" to "contains a space",
            "ordér-42" to "outside printable ASCII",
            "order\t42" to "outside printable ASCII",
        )
        cases.forEach { (candidate, problem) ->
            errors.clear()
            val id = idFor(candidate)
            assertTrue("$candidate gave $id", isGenerated(id))
            assertTrue(errors.single().contains("deduplicationId") && errors.single().contains(problem))
        }
        assertNotEquals(idFor(""), idFor(""))
    }

    @Test
    fun `an event with an invalid id is still sent`() {
        started()
        harness.track("purchase", deduplicationId = "order 42")
        harness.sendNow()
        val event = harness.transport.sentEvents().single()
        assertEquals("purchase", event.getString("name"))
        assertTrue(isGenerated(event.getString("id")))
        assertTrue(harness.messages(AppyLogLevel.ERROR).single().contains("\"order 42\""))
    }

    @Test
    fun `a second event with an id still in the queue is dropped`() {
        started()
        harness.track("purchase", deduplicationId = "order-1")
        harness.track("purchase_retry", deduplicationId = " order-1 ")
        harness.track("purchase", deduplicationId = "order-2")
        harness.track("signup")
        harness.track("signup")
        harness.sendNow()
        assertEquals(listOf("order-1", "order-2"), harness.transport.sentEvents().take(2).map { it.getString("id") })
        assertEquals(listOf("purchase", "purchase", "signup", "signup"), harness.transport.eventNames())
        assertTrue(harness.messages(AppyLogLevel.INFO).any { it.contains("\"purchase_retry\"") && it.contains("order-1") })

        harness.track("purchase", deduplicationId = "order-1")
        harness.sendNow()
        assertEquals("order-1", harness.transport.sentEvents().last().getString("id"))
    }

    @Test
    fun `an id waiting for a retry blocks duplicates until it is delivered`() {
        harness.transport.enqueue(Events.EVENTS_PATH, FakeTransport.status(503))
        started()
        harness.track("purchase", deduplicationId = "order-1")
        harness.sendNow()
        harness.track("purchase", deduplicationId = "order-1")
        harness.advance(4_400)
        val batches = harness.transport.batches().map { it.getJSONArray("events").length() }
        assertEquals(listOf(1, 1), batches)
        assertEquals(listOf("order-1", "order-1"), harness.transport.sentEvents().map { it.getString("id") })
        assertTrue(harness.messages(AppyLogLevel.INFO).any { it.contains("order-1") && it.contains("dropped") })
    }

    @Test
    fun `a queued deduplication id survives a restart and still blocks duplicates`() {
        harness.transport.eventsResult = FakeTransport.OFFLINE
        started()
        harness.track("purchase", deduplicationId = "GPA.1")
        harness.sendNow()
        harness.advance(3_300)
        assertTrue(harness.store[Events.QUEUE_KEY]!!.contains("\"GPA.1\""))

        val next = Harness(harness.store)
        next.track("purchase", deduplicationId = "GPA.1")
        next.init()
        next.advance()
        next.track("purchase", deduplicationId = "GPA.1")
        next.advance(500)
        val event = next.transport.sentEvents().single()
        assertEquals("GPA.1", event.getString("id"))
        assertEquals(2, next.messages(AppyLogLevel.INFO).count { it.contains("GPA.1") && it.contains("dropped") })
    }
}
