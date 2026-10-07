package pl.synsuck.librus

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.File
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Simulates the Librus login chain + messages API on one local server. */
class LibrusClientTest {
    private val server = MockWebServer()
    private lateinit var client: LibrusClient
    private val requests = mutableListOf<String>()
    private var captchaNeeded = false
    private var notReadyResponses = 1

    @BeforeTest
    fun setUp() {
        server.dispatcher = FakeLibrus()
        server.start()
        client = LibrusClient(endpoints = LibrusEndpoints.local(server.url("/")))
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `login walks the OAuth chain and messages handoff`() = runTest {
        val me = client.login("1234567u", "good".toCharArray())

        assertEquals(Me("Jan", "Kowalski", 1000001), me)
        assertEquals(
            listOf(
                "GET /loguj/portalRodzina",
                "GET /OAuth/Authorization",
                "GET /OAuth/Authorization",
                "POST /OAuth/Captcha",
                "POST /OAuth/Authorization",
                "GET /OAuth/Authorization/2FA",
                "GET /OAuth/Authorization/Grant",
                "GET /loguj/portalRodzina",
                "GET /wiadomosci3",
                "GET /pobierz15/MultiDomainLogon/token/$TOKEN",
                "GET /nowy",
                "GET /api/me",
            ),
            requests,
        )
    }

    @Test
    fun `trace is redacted`() = runTest {
        val lines = mutableListOf<String>()
        val traced = LibrusClient(endpoints = LibrusEndpoints.local(server.url("/")), trace = { lines += it })
        traced.login("1234567u", "good".toCharArray())

        val all = lines.joinToString("\n")
        assertTrue(lines.size >= 11, all)
        assertTrue("pobierz15/MultiDomainLogon/token/…" in all, all)
        assertTrue("set-cookie: [DZIENNIKSID]" in all, all)
        for (secret in listOf("good", TOKEN, "syn", STATE, "code=abc", "pass")) {
            assertTrue(secret !in all, "trace leaked '$secret':\n$all")
        }
    }

    @Test
    fun `wrong password is AuthFailed with server message`() = runTest {
        val e = assertFailsWith<LibrusException.AuthFailed> { client.login("1234567u", "bad".toCharArray()) }
        assertEquals("Nieprawidłowy login lub hasło", e.serverMessage)
    }

    @Test
    fun `captcha requirement is reported`() = runTest {
        captchaNeeded = true
        assertFailsWith<LibrusException.CaptchaRequired> { client.login("1234567u", "good".toCharArray()) }
    }

    @Test
    fun `api calls without session are SessionExpired`() = runTest {
        assertFailsWith<LibrusException.SessionExpired> { client.listMessages(Folder.INBOX) }
    }

    @Test
    fun `lists inbox and archive`() = runTest {
        client.login("1234567u", "good".toCharArray())

        val inbox = client.listMessages(Folder.INBOX, page = 1, limit = 10)
        assertEquals(2, inbox.total)
        val (unread, read) = inbox.items
        assertEquals("1028812", unread.id)
        assertEquals("Maria Kowalska", unread.senderName)
        assertEquals(Instant.parse("2026-10-06T19:49:51Z"), unread.sentAt)
        assertTrue(unread.isUnread)
        assertEquals(Instant.parse("2026-10-06T19:44:35Z"), read.readAt)
        assertTrue(read.hasAttachments)

        val archive = client.listMessages(Folder.ARCHIVE)
        assertEquals(Folder.ARCHIVE, archive.items.single().folder)
        assertTrue(requests.contains("GET /api/archive/inbox/messages"))
    }

    @Test
    fun `message detail decodes body and attachments`() = runTest {
        client.login("1234567u", "good".toCharArray())

        val m = client.getMessage(Folder.INBOX, "1028350")
        assertEquals("Dzień dobry,\nw załączniku zgoda.", m.body)
        assertEquals(listOf(Attachment("5496406", "zgoda.pdf")), m.attachments)
        assertEquals("Wiadomość zawiera załączniki.", m.attachmentInfo)
        assertEquals(false, m.noReply)
        assertEquals("Nauczyciel", m.senderGroup)
    }

    @Test
    fun `downloads attachment via one-time link`() = runTest {
        client.login("1234567u", "good".toCharArray())
        val target: File = createTempFile().toFile()
        try {
            val result = client.downloadAttachment(Folder.INBOX, "5496406", "1028350", target)
            assertEquals("application/pdf", result.contentType)
            assertEquals("zgoda wycieczka.pdf", result.fileName)
            assertEquals("%PDF-1.4 fake", target.readText())
            // info page first, then /get retried until the file is ready
            assertEquals(
                listOf("GET /GetFile/tok", "GET /GetFile/tok/get", "GET /GetFile/tok/get"),
                requests.filter { "GetFile" in it },
            )
        } finally {
            target.delete()
        }
    }

    @Test
    fun `gives up when file never becomes ready`() = runTest {
        notReadyResponses = Int.MAX_VALUE
        client.login("1234567u", "good".toCharArray())
        val target = createTempFile().toFile()
        try {
            assertFailsWith<LibrusException.UnexpectedResponse> {
                client.downloadAttachment(Folder.INBOX, "5496406", "1028350", target)
            }
            assertEquals(5, requests.count { it == "GET /GetFile/tok/get" })
        } finally {
            target.delete()
        }
    }

    @Test
    fun `oversized attachment is aborted and partial file removed`() = runTest {
        notReadyResponses = 0
        val small = LibrusClient(
            endpoints = LibrusEndpoints.local(server.url("/")),
            download = DownloadPolicy(maxBytes = 4),
        )
        small.login("1234567u", "good".toCharArray())
        val target = createTempFile().toFile()
        try {
            assertFailsWith<LibrusException.UnexpectedResponse> {
                small.downloadAttachment(Folder.INBOX, "5496406", "1028350", target)
            }
            assertTrue(!target.exists())
        } finally {
            target.delete()
        }
    }

    @Test
    fun `download link to foreign host is refused`() = runTest {
        client.login("1234567u", "good".toCharArray())
        val target = createTempFile().toFile()
        try {
            assertFailsWith<LibrusException.UnexpectedResponse> {
                client.downloadAttachment(Folder.INBOX, "666", "1028350", target)
            }
            assertEquals(0, target.length())
        } finally {
            target.delete()
        }
    }

    private inner class FakeLibrus : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            requests += "${request.method} $path"
            val cookies = request.headers["Cookie"].orEmpty()
            val body = request.body?.utf8().orEmpty()

            return when {
                path == "/OAuth/Authorization" && request.method == "GET" ->
                    if (request.url.queryParameter("response_type") != null) {
                        redirect("/OAuth/Authorization?client_id=46", cookie = "oauth_token=o")
                    } else {
                        ok("<html>login form</html>")
                    }

                path == "/OAuth/Captcha" -> json("""{"is_needed": $captchaNeeded, "reCaptchaCode": ""}""")

                path == "/OAuth/Authorization" && request.method == "POST" -> {
                    check("oauth_token=o" in cookies) { "OAuth cookie missing" }
                    check(request.headers["X-Requested-With"] == "XMLHttpRequest")
                    if ("pass=good" in body && "action=login" in body) {
                        json("""{"status":"ok","goTo":"/OAuth/Authorization/2FA?client_id=46"}""")
                    } else {
                        json("""{"status":"error","errors":[{"message":"Nieprawidłowy login lub hasło"}]}""")
                    }
                }

                path == "/OAuth/Authorization/2FA" -> redirect("/OAuth/Authorization/Grant?client_id=46")
                path == "/OAuth/Authorization/Grant" -> redirect("/loguj/portalRodzina?code=abc&state=$STATE")
                // Synergia issues `state`, remembers it in its session, and only accepts a matching callback.
                path == "/loguj/portalRodzina" && request.url.queryParameter("code") == null ->
                    redirect(
                        "/OAuth/Authorization?client_id=46&response_type=code&scope=mydata&state=$STATE",
                        cookie = "DZIENNIKSID=pre",
                    )
                path == "/loguj/portalRodzina" ->
                    if (request.url.queryParameter("state") == STATE && "DZIENNIKSID=pre" in cookies) {
                        ok("<html>ok</html>", cookie = "DZIENNIKSID=syn")
                    } else {
                        ok("<html>bad state</html>")
                    }
                path == "/wiadomosci3" ->
                    if ("DZIENNIKSID=syn" in cookies) redirect("/pobierz15/MultiDomainLogon/token/$TOKEN") else redirect("/loguj")
                path.startsWith("/pobierz15/MultiDomainLogon") -> redirect("/nowy", cookie = "wiadomosci=w")
                path == "/nowy" -> ok("<html>app</html>")

                path.startsWith("/api/") && "wiadomosci=w" !in cookies -> redirect("/")

                path == "/api/me" -> json("""{"firstName":"Jan","lastName":"Kowalski","accountId":1000001,"groupId":"5"}""")
                path == "/api/inbox/messages" -> json(INBOX)
                path == "/api/archive/inbox/messages" -> json(ARCHIVE)
                path == "/api/inbox/messages/1028350" -> json(detail())
                path == "/api/attachments/5496406/messages/1028350" ->
                    json("""{"data":{"status":"ok","downloadLink":"${server.url("/GetFile/tok")}"}}""")
                path == "/api/attachments/666/messages/1028350" ->
                    json("""{"data":{"status":"ok","downloadLink":"https://evil.example/GetFile/tok"}}""")
                path == "/GetFile/tok" -> html("<html><body><h1>Pobieranie plików</h1></body></html>")
                path == "/GetFile/tok/get" -> {
                    check(request.headers["Referer"] == server.url("/GetFile/tok").toString()) { "Referer missing" }
                    if (notReadyResponses-- > 0) {
                        html("<html>please wait</html>")
                    } else {
                        MockResponse.Builder()
                            .addHeader("Content-Type", "application/pdf")
                            .addHeader("Content-Disposition", "attachment; filename*=UTF-8''..%2F..%2Fzgoda%20wycieczka.pdf")
                            .body("%PDF-1.4 fake")
                            .build()
                    }
                }

                else -> MockResponse.Builder().code(404).build()
            }
        }

        private fun ok(body: String, cookie: String? = null) = MockResponse.Builder().body(body)
            .apply { cookie?.let { addHeader("Set-Cookie", "$it; Path=/") } }.build()

        private fun html(body: String) = MockResponse.Builder()
            .addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

        private fun json(body: String) = MockResponse.Builder()
            .addHeader("Content-Type", "application/json").body(body).build()

        private fun redirect(location: String, cookie: String? = null) = MockResponse.Builder()
            .code(302).addHeader("Location", location)
            .apply { cookie?.let { addHeader("Set-Cookie", "$it; Path=/") } }.build()
    }

    private fun detail(): String {
        val envelope = "<Message><Content><![CDATA[Dzień dobry,\nw załączniku zgoda.]]></Content><Actions><Actions/></Actions></Message>"
        val encoded = Base64.getEncoder().encodeToString(envelope.toByteArray())
        return """{"data":{"messageId":"1028350","senderFirstName":"Maria","senderLastName":"Kowalska",
            "senderName":"Maria Kowalska","senderGroup":"Nauczyciel","topic":"Zgoda","Message":"$encoded",
            "sendDate":"2026-10-06T21:25:37","readDate":"2026-10-06T21:44:35",
            "attachementInfo":"Wiadomość zawiera załączniki.","noReply":0,
            "attachments":[{"filename":"zgoda.pdf","id":"5496406"}],"receivers":[],"tags":[]}}"""
    }

    private companion object {
        const val STATE = "st4te"
        const val TOKEN = "FakeOneTimeTokenAbcdefghijklmnop123"
        const val INBOX = """{"data":[
            {"messageId":"1028812","senderFirstName":"Maria","senderLastName":"Kowalska","senderName":"Maria Kowalska",
             "topic":"Wycieczka","content":"Szanowni Państwo...","sendDate":"2026-10-06T21:49:51","readDate":null,
             "tags":[],"category":null,"isAnyFileAttached":false},
            {"messageId":"1028350","senderFirstName":"Piotr","senderLastName":"Nowak","senderName":"Piotr Nowak",
             "topic":"Zgoda","content":"Dzień dobry","sendDate":"2026-10-06T21:25:37","readDate":"2026-10-06T21:44:35",
             "tags":[],"category":null,"isAnyFileAttached":true}
        ],"total":2}"""

        const val ARCHIVE = """{"data":[
            {"messageId":"900001","senderName":"Sekretariat","topic":"Stare","content":"...",
             "sendDate":"2025-06-01T10:00:00","readDate":null,"isAnyFileAttached":false}
        ],"total":1}"""
    }
}
