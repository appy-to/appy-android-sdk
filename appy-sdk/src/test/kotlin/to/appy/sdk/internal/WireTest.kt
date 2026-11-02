package to.appy.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.appy.sdk.AppyAttribution
import to.appy.sdk.AppyConfig
import to.appy.sdk.AppyLogLevel
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class WireTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport(scheduler)
    private val logs = mutableListOf<String>()
    private val settings = Settings(AppyConfig(Harness.KEY).apply { apiBaseUrl = "https://api.appy.to/" })
    private val api = Api(settings, transport, scheduler, Logger(AppyLogLevel.DEBUG) { _, message -> logs.add(message) })

    private fun post(): HttpResult {
        var result: HttpResult? = null
        api.post(Session.OPEN_PATH, JSONObject().put("hello", "world")) { result = it }
        scheduler.advance(600_000)
        return checkNotNull(result)
    }

    @Test
    fun `retry-after is honoured inline up to ten seconds and other 4xx are final`() {
        transport.enqueue(Session.OPEN_PATH, FakeTransport.status(429, retryAfter = "7"))
        assertTrue(post().ok)
        assertEquals(listOf(0L, 7_000L), transport.calls.map { it.at })
        assertEquals("https://api.appy.to/v1/sdk/open", "https://api.appy.to" + transport.calls[0].path)

        transport.openResult = FakeTransport.status(429, retryAfter = "120")
        assertEquals(429, post().status)
        assertEquals(3, transport.calls.size)

        transport.openResult = FakeTransport.status(401, """{"error":{"code":"unauthorized","message":"Unknown key","requestId":"req_9"}}""")
        assertEquals(401, post().status)
        assertEquals(4, transport.calls.size)
        assertTrue(logs.any { it.contains("HTTP 401 unauthorized: Unknown key (requestId req_9)") })
        assertFalse(logs.any { it.contains(Harness.KEY) })
    }

    @Test
    fun `retry-after is parsed as seconds for 429 only`() {
        assertEquals(3_000L, retryAfterMillis(HttpResult(429, "", " 3 ")))
        assertNull(retryAfterMillis(HttpResult(429, "", "Wed, 21 Oct 2026 07:28:00 GMT")))
        assertNull(retryAfterMillis(HttpResult(503, "", "3")))
        assertTrue(backoffMillis(3) in 4_000..4_400)
        assertTrue(backoffMillis(30) in 300_000..330_000)
    }

    @Test
    fun `the open response yields the deep link and the attribution`() {
        val response = parseOpen(
            """
            {
              "installId": "b0000000-0000-4000-8000-000000000002",
              "firstLaunch": true,
              "deepLink": {
                "url": "https://acme.appy.to/spring?item=42",
                "deeplinkUrl": "acme://items?item=42",
                "slug": "spring",
                "parameters": {"item": "42", "utm_source": "instagram"},
                "isDeferred": true,
                "clickedAt": "2026-09-29T10:00:00Z"
              },
              "attribution": {
                "status": "attributed",
                "link": {"slug": "spring", "title": "Spring campaign"},
                "source": "instagram",
                "medium": "story",
                "campaign": "spring_sale",
                "clickedAt": "2026-09-29T10:00:00Z"
              }
            }
            """,
        )!!
        val link = response.link!!
        assertEquals("https://acme.appy.to/spring?item=42", link.url)
        assertEquals("acme://items?item=42", link.deeplinkUrl)
        assertEquals("spring", link.slug)
        assertEquals(mapOf("item" to "42", "utm_source" to "instagram"), link.parameters)
        assertTrue(link.isDeferred)
        assertEquals(CLICKED_AT, link.clickedAt)
        val expected = AppyAttribution(false, "spring", "Spring campaign", "instagram", "story", "spring_sale", CLICKED_AT)
        assertEquals(expected, response.attribution)
        assertEquals(expected, storedAttribution(response.attributionJson!!))
    }

    @Test
    fun `missing and null fields have safe defaults`() {
        val minimal = parseOpen("""{"deepLink":{"slug":"s","url":null,"parameters":{"a":"1","b":2,"c":null},"clickedAt":"2026-09-25T10:04:05.5Z"}}""")!!
        val link = minimal.link!!
        assertNull(link.url)
        assertNull(link.deeplinkUrl)
        assertFalse(link.isDeferred)
        assertEquals(mapOf("a" to "1", "b" to "2"), link.parameters)
        assertEquals(FakeScheduler.START + 500, link.clickedAt)
        assertNull(minimal.attribution)
        assertNull(minimal.attributionJson)
        val empty = parseOpen("""{"deepLink":{"clickedAt":"yesterday"}}""")!!.link!!
        assertEquals("", empty.slug)
        assertEquals(emptyMap<String, String>(), empty.parameters)
        assertNull(empty.clickedAt)

        val organic = parseOpen(FakeTransport.ORGANIC)!!
        assertNull(organic.link)
        assertEquals(AppyAttribution(true, null, null, null, null, null, null), organic.attribution)
        assertTrue(parseOpen("""{"attribution":{}}""")!!.attribution!!.isOrganic)

        val none = parseOpen(FakeTransport.NO_LINK)!!
        assertNull(none.link)
        assertNull(none.attribution)
        assertNull(parseOpen("<html>"))
        assertNull(parseOpen(""))
        assertNull(storedAttribution("not json"))
    }

    @Test
    fun `appy links are detected by link domain or click id`() {
        val domains = listOf("acme.appy.to")
        assertTrue(Links.isAppyLink("https://ACME.appy.to/spring", domains))
        assertTrue(Links.isAppyLink("acme://items?appy_click_id=h3k9", domains))
        assertFalse(Links.isAppyLink("https://evil.acme.appy.to/spring", domains))
        assertFalse(Links.isAppyLink("https://acme.appy.to.evil.com/spring", domains))
        assertFalse(Links.isAppyLink("https://www.acme.com/spring#appy_click_id=abc", domains))
        assertEquals(
            listOf("acme.appy.to", "go.acme.com"),
            Settings(AppyConfig("k", listOf(" https://ACME.appy.to/ ", "acme.appy.to", "go.acme.com:443", ""))).domains,
        )
        assertEquals(listOf("q" to "summer sale", "name" to "çay", "bad" to "%zz"), Links.query("x://h/?q=summer+sale&name=%C3%A7ay&bad=%zz"))
    }

    @Test
    fun `share links are built on the first domain with sorted encoded parameters`() {
        assertEquals("https://acme.appy.to/spring", Links.build("acme.appy.to", "Spring", emptyMap()))
        assertEquals(
            "https://acme.appy.to/spring-sale?item=42&note=a%20b%26c%3Dd&utm_source=mail",
            Links.build("acme.appy.to", "spring-sale", mapOf("utm_source" to "mail", "item" to "42", "note" to "a b&c=d")),
        )
        listOf("", "with space", "a/b", "ü", "a".repeat(65)).forEach { assertNull(it, Links.build("acme.appy.to", it, emptyMap())) }
        assertNull(Links.build(null, "spring", emptyMap()))
        val harness = Harness()
        assertNull(harness.engine.buildLink("spring", emptyMap()))
        harness.init()
        assertEquals("https://acme.appy.to/spring?item=42", harness.engine.buildLink("spring", mapOf("item" to "42")))
    }

    @Test
    fun `timestamps are written in UTC with milliseconds and read with or without fractions`() {
        assertEquals("2026-09-25T10:04:05.000Z", Links.formatTimestamp(FakeScheduler.START))
        assertEquals(FakeScheduler.START, Links.parseTimestamp("2026-09-25T10:04:05Z"))
        assertEquals(FakeScheduler.START + 123, Links.parseTimestamp("2026-09-25T10:04:05.123456789Z"))
        assertEquals(FakeScheduler.START, Links.parseTimestamp("2026-09-25T13:04:05+03:00"))
        assertNull(Links.parseTimestamp("2026-02-30T10:00:00Z"))
        assertNull(Links.parseTimestamp("2026-09-25T10:04:05"))
    }

    @Test
    fun `the http transport posts json and reads bodies and headers`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            var request = ""
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = head.first { it.startsWith("Content-Length", ignoreCase = true) }.substringAfter(':').trim().toInt()
                    request = head.joinToString("\n") + "\n" + String(CharArray(length) { input.read().toChar() })
                    val body = """{"error":{"code":"rate_limited"}}"""
                    socket.getOutputStream().write(
                        "HTTP/1.1 429 Too Many Requests\r\nRetry-After: 9\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                    )
                }
            }
            val result = UrlConnectionTransport(2_000).post(
                "http://127.0.0.1:${server.localPort}/v1/sdk/open",
                mapOf("Authorization" to "Bearer ${Harness.KEY}", "Content-Type" to "application/json"),
                """{"installId":"abc"}""",
            )
            worker.join(5_000)
            assertEquals(429, result.status)
            assertEquals("9", result.retryAfter)
            assertTrue(result.body.contains("rate_limited"))
            assertTrue(request.startsWith("POST /v1/sdk/open HTTP/1.1"))
            assertTrue(request.contains("Authorization: Bearer ${Harness.KEY}"))
            assertTrue(request.endsWith("""{"installId":"abc"}"""))
        }
    }

    @Test
    fun `an email link GET reads the redirect without following it and sends no Appy headers`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            var request = ""
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    request = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                    socket.getOutputStream().write(
                        "HTTP/1.1 302 Found\r\nLocation: ${Harness.LINK}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
            val result = UrlConnectionTransport(2_000).get("http://127.0.0.1:${server.localPort}/uni/ls/click?upn=abc")
            worker.join(5_000)
            assertEquals(302, result.status)
            assertEquals(Harness.LINK, result.location)
            assertTrue(request.startsWith("GET /uni/ls/click?upn=abc HTTP/1.1"))
            assertFalse(request.contains("Authorization"))
            assertFalse(request.contains("X-Appy-SDK"))
        }
    }

    @Test(expected = IOException::class)
    fun `connection failures surface as IOException`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        UrlConnectionTransport(2_000).post("http://127.0.0.1:$port/v1/sdk/open", emptyMap(), "{}")
    }

    private companion object {
        const val CLICKED_AT: Long = 1_790_676_000_000L
    }
}
