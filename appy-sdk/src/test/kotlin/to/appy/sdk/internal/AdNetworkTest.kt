package to.appy.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyAdNetwork

class AdNetworkTest {

    private val harness = Harness()
    private val metaRow = JSONObject()
        .put("installReferrer", """{"utm_source":"apps.facebook.com","utm_content":{"source":{"data":"ab","nonce":"cd"}}}""")
        .put("isClickThrough", true)
        .put("actualTimestamp", 1_790_000_000L)
        .put("sourceApp", "facebook")

    @Test
    fun `the first launch sends the Meta install referrer when a Meta app id is configured`() {
        harness.metaRow = metaRow
        harness.transport.openResult = FakeTransport.status(200, FakeTransport.ORGANIC)
        harness.init(Harness.config(collectInstallReferrer = false, metaAppId = " 1234567890 "))
        harness.advance(60_000)
        val sent = harness.singleOpen().getJSONObject("metaInstallReferrer")
        assertEquals(listOf("1234567890"), harness.metaQueries)
        assertEquals(metaRow.getString("installReferrer"), sent.getString("installReferrer"))
        assertTrue(sent.getBoolean("isClickThrough"))
        assertEquals("facebook", sent.getString("sourceApp"))

        val next = Harness(harness.store)
        next.metaRow = metaRow
        next.init(Harness.config(collectInstallReferrer = false, metaAppId = "1234567890"))
        next.advance(60_000)
        assertFalse(next.singleOpen().has("metaInstallReferrer"))
        assertTrue(next.metaQueries.isEmpty())
    }

    @Test
    fun `no Meta query without a valid Meta app id`() {
        harness.metaRow = metaRow
        harness.init(Harness.config(collectInstallReferrer = false, metaAppId = "not-an-id"))
        harness.advance(60_000)
        assertTrue(harness.metaQueries.isEmpty())
        assertFalse(harness.singleOpen().has("metaInstallReferrer"))
    }

    @Test
    fun `a missing Meta row leaves the open body unchanged`() {
        harness.init(Harness.config(collectInstallReferrer = false, metaAppId = "1234567890"))
        harness.advance(60_000)
        assertEquals(1, harness.metaQueries.size)
        assertFalse(harness.singleOpen().has("metaInstallReferrer"))
    }

    @Test
    fun `ad network attribution is parsed and kept`() {
        val attribution = JSONObject()
            .put("status", "attributed").put("link", JSONObject.NULL).put("source", "google").put("medium", "cpc")
            .put("campaign", JSONObject.NULL).put("clickedAt", JSONObject.NULL)
            .put("network", JSONObject().put("name", "google_ads").put("clickId", "Cj0KCQjw").put("touch", "click"))
        harness.transport.openResult = FakeTransport.status(200,
            JSONObject().put("installId", "x").put("firstLaunch", true).put("deepLink", JSONObject.NULL).put("attribution", attribution).toString())
        harness.listenAttribution()
        harness.init(Harness.config(collectInstallReferrer = false))
        harness.advance(60_000)
        val received = harness.attributions.single()
        assertFalse(received.isOrganic)
        assertNull(received.linkSlug)
        assertEquals(AppyAdNetwork("google_ads", null, null, null, null, null, "Cj0KCQjw", "click"), received.network)
        assertEquals(received, storedAttribution(harness.store[Session.ATTRIBUTION_KEY]!!))
    }
}
