package to.appy.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyLogLevel

class InstallSourceTest {

    private val harness = Harness()

    private fun appGalleryInstall(): Harness = Harness().apply {
        installer = InstallSource.APPGALLERY_PACKAGE
        init()
        advance()
    }

    private fun storesFor(installer: String?): List<String> =
        InstallSource({ installer }, FakeReferrer(), FakeReferrer()).referrerSources().map { it.first }

    @Test
    fun `AppGallery installs read AppGallery first and everything else reads Play`() {
        assertEquals(listOf("huawei_appgallery", "google_play"), storesFor("com.huawei.appmarket"))
        listOf(null, "", "com.android.vending", "com.sec.android.app.samsungapps", "com.xiaomi.mipicks", "com.google.android.packageinstaller")
            .forEach { assertEquals(listOf("google_play"), storesFor(it)) }
        val play = FakeReferrer()
        val appGallery = FakeReferrer()
        val sources = InstallSource({ "com.huawei.appmarket" }, play, appGallery).referrerSources()
        assertSame(appGallery, sources[0].second)
        assertSame(play, sources[1].second)
    }

    @Test
    fun `the installer is read once and dropped when blank or longer than 128 characters`() {
        var reads = 0
        val install = InstallSource({ reads++; "com.huawei.appmarket" }, FakeReferrer(), FakeReferrer())
        install.referrerSources()
        install.referrerSources()
        assertEquals("com.huawei.appmarket", install.installer)
        assertEquals(1, reads)
        assertEquals("a".repeat(128), InstallSource({ "a".repeat(128) }, FakeReferrer(), FakeReferrer()).installer)
        assertNull(InstallSource({ "a".repeat(129) }, FakeReferrer(), FakeReferrer()).installer)
        assertNull(InstallSource({ " " }, FakeReferrer(), FakeReferrer()).installer)
    }

    @Test
    fun `an AppGallery install sends its referrer unmodified with the source and the installer`() {
        val referrer = "appy_slug%3Dspring%26appy_click_id%3D01J8Z3K4M5N6P7Q8R9S0T1V2W3"
        val install = appGalleryInstall()
        install.advance(100)
        install.appGallery.complete(referrer)
        install.advance(400)
        val open = install.singleOpen()
        assertEquals(referrer, open.getString("installReferrer"))
        assertEquals("huawei_appgallery", open.getString("installReferrerSource"))
        assertEquals("com.huawei.appmarket", open.getJSONObject("app").getString("installer"))
        assertEquals("com.acme.shop", open.getJSONObject("app").getString("bundleId"))
        assertEquals(500L, install.transport.calls.single().at)
        assertEquals(0, install.referrer.requests)
    }

    @Test
    fun `a failed AppGallery read falls back to the Play referrer`() {
        val install = appGalleryInstall()
        install.advance(100)
        assertEquals(0, install.referrer.requests)
        install.appGallery.complete(null)
        install.advance()
        assertEquals(1, install.referrer.requests)
        install.referrer.complete(Harness.REFERRER)
        install.advance(400)
        val open = install.singleOpen()
        assertEquals(Harness.REFERRER, open.getString("installReferrer"))
        assertEquals("google_play", open.getString("installReferrerSource"))
        assertEquals("com.huawei.appmarket", open.getJSONObject("app").getString("installer"))
    }

    @Test
    fun `blank or oversized AppGallery referrers fall back, and a failing fallback sends no referrer`() {
        listOf(" ", "a".repeat(2_049)).forEach { value ->
            val install = appGalleryInstall()
            install.appGallery.complete(value)
            install.advance()
            assertEquals(1, install.referrer.requests)
            install.referrer.complete(null)
            install.advance(500)
            val open = install.singleOpen()
            assertFalse(open.has("installReferrer") || open.has("installReferrerSource"))
            assertTrue(install.messages(AppyLogLevel.WARNING).any { it.contains("huawei_appgallery") })
        }
    }

    @Test
    fun `every source shares the three second budget`() {
        val silent = appGalleryInstall()
        silent.advance(2_999)
        assertTrue(silent.opens().isEmpty())
        silent.advance(1)
        assertFalse(silent.singleOpen().has("installReferrer") || silent.singleOpen().has("installReferrerSource"))
        assertEquals(3_000L, silent.transport.calls.single().at)
        assertEquals(1, silent.appGallery.cancels)
        assertEquals(0, silent.referrer.requests)

        val late = appGalleryInstall()
        late.advance(2_500)
        late.appGallery.complete(null)
        late.advance(499)
        assertEquals(1, late.referrer.requests)
        assertTrue(late.opens().isEmpty())
        late.advance(1)
        assertFalse(late.singleOpen().has("installReferrer"))
        assertEquals(1, late.referrer.cancels)
        late.referrer.complete(Harness.REFERRER)
        late.appGallery.complete(Harness.REFERRER)
        late.advance(60_000)
        assertEquals(1, late.opens().size)
    }

    @Test
    fun `Play and unknown installers read Play and report the installer only when known`() {
        harness.installer = "com.android.vending"
        harness.init()
        harness.advance()
        harness.referrer.complete(Harness.REFERRER)
        harness.advance(500)
        val open = harness.singleOpen()
        assertEquals("google_play", open.getString("installReferrerSource"))
        assertEquals("com.android.vending", open.getJSONObject("app").getString("installer"))
        assertEquals(0, harness.appGallery.requests)

        val sideloaded = Harness()
        sideloaded.init()
        sideloaded.advance(3_000)
        assertFalse(sideloaded.singleOpen().getJSONObject("app").has("installer"))
        assertEquals(1, sideloaded.referrer.requests)
        assertEquals(0, sideloaded.appGallery.requests)
    }

    @Test
    fun `later opens carry the installer without a referrer or its source`() {
        harness.installer = InstallSource.APPGALLERY_PACKAGE
        harness.completeFirstOpen()
        harness.init()
        harness.advance(500)
        harness.engine.handleUrl(Harness.LINK)
        harness.advance()
        assertEquals(2, harness.opens().size)
        assertEquals(0, harness.appGallery.requests)
        harness.opens().forEach { open ->
            assertEquals("com.huawei.appmarket", open.getJSONObject("app").getString("installer"))
            assertFalse(open.has("installReferrer") || open.has("installReferrerSource"))
        }
    }
}
