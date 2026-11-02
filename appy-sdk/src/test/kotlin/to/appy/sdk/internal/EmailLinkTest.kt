package to.appy.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyLogLevel

class EmailLinkTest {

    private val harness = Harness()
    private val emailLink = "https://links.example.com/uni/ls/click?upn=abc123"
    private val config = Harness.config(emailLinkDomains = listOf("links.example.com"))

    private fun launched() {
        harness.completeFirstOpen()
        harness.listen()
        harness.init(config)
        harness.advance(600)
    }

    @Test
    fun `an email link is resolved to the Appy link it points to`() {
        launched()
        harness.transport.redirect(emailLink, Harness.LINK)
        assertTrue(harness.engine.handleUrl(emailLink))
        harness.advance()
        assertEquals(listOf(emailLink), harness.transport.gets)
        assertEquals(Harness.LINK, harness.opens().last().getString("url"))
        assertEquals("spring", harness.delivered.last().link?.slug)
    }

    @Test
    fun `an email link to a website is delivered without an open`() {
        launched()
        harness.transport.redirect(emailLink, "https://shop.example.com/shoes?color=red")
        harness.engine.handleUrl(emailLink)
        harness.advance()
        assertEquals(1, harness.opens().size)
        val link = harness.delivered.last().link!!
        assertEquals("https://shop.example.com/shoes?color=red", link.url)
        assertEquals("", link.slug)
        assertEquals(mapOf("color" to "red"), link.parameters)
    }

    @Test
    fun `relative and chained redirects are followed across email domains`() {
        harness.completeFirstOpen()
        harness.listen()
        harness.init(Harness.config(emailLinkDomains = listOf("links.example.com", "click.example.net")))
        harness.advance(600)
        harness.transport.redirect(emailLink, "/uni/hop", status = 301)
        harness.transport.redirect("https://links.example.com/uni/hop", "https://click.example.net/c/1", status = 307)
        harness.transport.redirect("https://click.example.net/c/1", Harness.LINK)
        harness.engine.handleUrl(emailLink)
        harness.advance()
        assertEquals(listOf(emailLink, "https://links.example.com/uni/hop", "https://click.example.net/c/1"), harness.transport.gets)
        assertEquals("spring", harness.delivered.last().link?.slug)
    }

    @Test
    fun `an unreachable email link is delivered as it is`() {
        launched()
        harness.transport.pages[emailLink] = FakeTransport.OFFLINE
        harness.engine.handleUrl(emailLink)
        harness.advance()
        val link = harness.delivered.last().link!!
        assertEquals(emailLink, link.url)
        assertEquals("", link.slug)
        assertTrue(harness.messages(AppyLogLevel.WARNING).any { "Could not resolve the email link" in it })
    }

    @Test
    fun `a redirect loop stops after five requests`() {
        launched()
        harness.transport.redirect(emailLink, emailLink)
        harness.engine.handleUrl(emailLink)
        harness.advance()
        assertEquals(Session.MAX_EMAIL_REDIRECTS, harness.transport.gets.size)
        assertEquals(emailLink, harness.delivered.last().link?.url)
    }

    @Test
    fun `an email link that launched the app becomes the launch open`() {
        harness.completeFirstOpen()
        harness.listen()
        harness.scheduler.latencyMillis = 2_000
        harness.transport.redirect(emailLink, Harness.LINK)
        assertTrue(harness.engine.handleUrl(emailLink))
        harness.init(config)
        harness.advance(1_500)
        assertTrue(harness.opens().isEmpty())
        harness.advance(10_000)
        assertEquals(Harness.LINK, harness.singleOpen().getString("url"))
        assertEquals("spring", harness.delivered.single().link?.slug)
    }

    @Test
    fun `the launch open still leaves when the email link leads elsewhere`() {
        harness.completeFirstOpen()
        harness.listen()
        harness.transport.redirect(emailLink, "https://shop.example.com/")
        harness.engine.handleUrl(emailLink)
        harness.init(config)
        harness.advance(60_000)
        assertFalse(harness.singleOpen().has("url"))
        assertEquals("https://shop.example.com/", harness.delivered.single().link?.url)
    }

    @Test
    fun `link domains win over email domains and other hosts are not accepted`() {
        harness.completeFirstOpen()
        harness.init(Harness.config(emailLinkDomains = listOf("acme.appy.to", "links.example.com")))
        harness.advance(600)
        assertTrue(harness.engine.handleUrl(Harness.LINK))
        assertFalse(harness.engine.handleUrl("https://elsewhere.example.org/uni/ls/click?upn=1"))
        harness.advance()
        assertTrue(harness.transport.gets.isEmpty())
        assertEquals(Harness.LINK, harness.opens().last().getString("url"))
    }
}
