package pl.synsuck.librus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * Talks to Librus the same way the web UI does. One instance per account: each instance
 * owns an in-memory cookie jar, so sessions are never written to disk.
 *
 * See docs/librus-api.md for the flow this implements.
 */
public class LibrusClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val endpoints: LibrusEndpoints = LibrusEndpoints.PRODUCTION,
    /** Optional debug sink: one redacted line per HTTP exchange (no bodies, header or cookie values). */
    private val trace: ((String) -> Unit)? = null,
    private val download: DownloadPolicy = DownloadPolicy(),
) {
    private val cookies = InMemoryCookieJar()

    // Redirects are followed manually so every hop can be checked against the host allowlist.
    private val http: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Full login: OAuth on api.librus.pl, then handoff to the messages module. */
    public suspend fun login(login: String, password: CharArray): Me = io {
        cookies.clear()

        // Start at Synergia: it generates the OAuth `state` and keeps it in its session,
        // then redirects to api.librus.pl/OAuth/Authorization?…&state=… (login form).
        followRedirects(get(endpoints.synergia.resolve("/loguj/portalRodzina")!!)).close()

        val captcha = post(
            endpoints.api.resolve("/OAuth/Captcha")!!,
            FormBody.Builder().add("username", login).add("is_needed", "1").build(),
            xhr = true,
        ).use { parse(it, CaptchaResponse.serializer()) }
        if (captcha.isNeeded) throw LibrusException.CaptchaRequired()

        // FormBody needs a String; the copy is short-lived and never logged.
        val form = FormBody.Builder()
            .add("action", "login")
            .add("login", login)
            .add("pass", String(password))
            .build()
        val loginUrl = endpoints.api.newBuilder()
            .addPathSegments("OAuth/Authorization")
            .addQueryParameter("client_id", CLIENT_ID)
            .build()
        val result = post(loginUrl, form, xhr = true).use { response ->
            val body = response.body.string()
            try {
                LibrusJson.decodeFromString(LoginResponse.serializer(), body)
            } catch (e: SerializationException) {
                throw LibrusException.UnexpectedResponse("Login response HTTP ${response.code} not JSON", e)
            }
        }
        val goTo = result.goTo
        if (result.status != "ok" || goTo == null) {
            throw LibrusException.AuthFailed(result.errors.firstNotNullOfOrNull { it.message })
        }

        // 2FA → PerformLogin → Grant → synergia.librus.pl/loguj/portalRodzina?code=…
        followRedirects(get(endpoints.resolveRedirect(endpoints.api, goTo))).close()

        // synergia/wiadomosci3 → MultiDomainLogon → wiadomosci.librus.pl session
        followRedirects(get(endpoints.synergia.resolve("/wiadomosci3")!!)).close()

        try {
            fetchMe()
        } catch (e: LibrusException.SessionExpired) {
            throw LibrusException.UnexpectedResponse("Messages handoff failed (${e.message})", e)
        }
    }

    public fun clearSession() {
        cookies.clear()
    }

    public suspend fun me(): Me = io { fetchMe() }

    private fun fetchMe(): Me {
        val dto = api("api/me", MeDto.serializer())
        return Me(dto.firstName, dto.lastName, dto.accountId)
    }

    public suspend fun unreadCounts(): UnreadCounts = io {
        val dto = api("api/inbox/unreadMessagesCount", DataEnvelope.serializer(UnreadCountsDto.serializer())).data
        UnreadCounts(dto.inbox, dto.archiveInbox)
    }

    /** Message list. Does NOT mark messages as read. [page] is 1-based. */
    public suspend fun listMessages(folder: Folder, page: Int = 1, limit: Int = 50): MessagePage = io {
        val url = messagesUrl(listPath(folder)).newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val dto = api(url, MessageListDto.serializer())
        MessagePage(
            items = dto.data.map { m ->
                MessageSummary(
                    id = m.messageId,
                    folder = folder,
                    senderName = senderName(m.senderName, m.senderFirstName, m.senderLastName),
                    topic = m.topic,
                    preview = Decoding.preview(m.content.orEmpty()),
                    sentAt = Decoding.parseDate(m.sendDate),
                    readAt = Decoding.parseDateOrNull(m.readDate),
                    hasAttachments = m.isAnyFileAttached,
                )
            },
            total = dto.total,
        )
    }

    /** Full message. Librus marks it as read server-side. */
    public suspend fun getMessage(folder: Folder, messageId: String): MessageDetail = io {
        val url = messagesUrl(listPath(folder)).newBuilder().addPathSegment(messageId).build()
        val m = api(url, DataEnvelope.serializer(MessageDetailDto.serializer())).data
        MessageDetail(
            id = m.messageId,
            folder = folder,
            senderName = senderName(m.senderName, m.senderFirstName, m.senderLastName),
            senderGroup = m.senderGroup,
            topic = m.topic,
            sentAt = Decoding.parseDate(m.sendDate),
            readAt = Decoding.parseDateOrNull(m.readDate),
            body = Decoding.messageBody(m.message),
            attachmentInfo = Decoding.maybeBase64(m.attachmentInfo),
            attachments = m.attachments.map { Attachment(it.id, it.filename) },
            noReply = m.noReply != 0,
        )
    }

    /**
     * Downloads an attachment into [target].
     *
     * Librus hands out a one-time link `https://sandbox.librus.pl/GetFile/<token>` (validated
     * against the host allowlist). That URL is an info page; the browser's script then fetches
     * `<link>/get`, which returns the file once the server has prepared it.
     */
    public suspend fun downloadAttachment(
        folder: Folder,
        attachmentId: String,
        messageId: String,
        target: File,
    ): DownloadResult {
        val pageUrl = io {
            val prefix = if (folder == Folder.ARCHIVE) "api/archive/attachments" else "api/attachments"
            val url = messagesUrl(prefix).newBuilder()
                .addPathSegment(attachmentId)
                .addPathSegment("messages")
                .addPathSegment(messageId)
                .build()
            val link = api(url, DataEnvelope.serializer(AttachmentLinkDto.serializer())).data
            val downloadLink = link.downloadLink
            if (link.status != "ok" || downloadLink == null) {
                throw LibrusException.UnexpectedResponse("No download link (status=${link.status})")
            }
            endpoints.resolveRedirect(url, downloadLink).also { page ->
                followRedirects(get(page)).close()
            }
        }
        val fileUrl = pageUrl.newBuilder().addPathSegment("get").build()

        repeat(download.attempts) { attempt ->
            if (attempt > 0) delay(download.retryDelayMillis)
            io { fetchFile(fileUrl, referer = pageUrl, target) }?.let { return it }
        }
        throw LibrusException.UnexpectedResponse("Attachment not ready after ${download.attempts} attempts")
    }

    /** Returns null while the server still answers with its HTML "please wait" page. */
    private fun fetchFile(url: HttpUrl, referer: HttpUrl, target: File): DownloadResult? {
        val request = baseRequest(url).header("Referer", referer.toString()).build()
        followRedirects(execute(request)).use { response ->
            if (!response.isSuccessful) {
                throw LibrusException.UnexpectedResponse("Download failed: HTTP ${response.code}")
            }
            val contentType = response.header("Content-Type")
            if (contentType?.startsWith("text/html", ignoreCase = true) == true) return null

            val written = try {
                target.outputStream().use { out -> copyCapped(response.body.byteStream(), out) }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            val disposition = response.header("Content-Disposition")
            return DownloadResult(
                contentType = contentType,
                contentDisposition = disposition,
                fileName = Decoding.fileNameFromDisposition(disposition),
                bytesWritten = written,
            )
        }
    }

    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > download.maxBytes) {
                throw LibrusException.UnexpectedResponse("Attachment larger than ${download.maxBytes} bytes")
            }
            output.write(buffer, 0, n)
        }
    }

    // --- HTTP plumbing ---

    private fun messagesUrl(path: String): HttpUrl =
        endpoints.messages.newBuilder().addPathSegments(path).build()

    private fun listPath(folder: Folder) = when (folder) {
        Folder.INBOX -> "api/inbox/messages"
        Folder.ARCHIVE -> "api/archive/inbox/messages"
    }

    private fun <T> api(path: String, serializer: KSerializer<T>): T = api(messagesUrl(path), serializer)

    /** JSON API call. Not followed redirects or 401/403 mean the session is gone. */
    private fun <T> api(url: HttpUrl, serializer: KSerializer<T>): T {
        val request = baseRequest(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        return execute(request).use { response ->
            if (response.isRedirect || response.code == 401 || response.code == 403) {
                throw LibrusException.SessionExpired("HTTP ${response.code}")
            }
            if (!response.isSuccessful) {
                throw LibrusException.UnexpectedResponse("HTTP ${response.code} from ${url.encodedPath}")
            }
            parse(response, serializer)
        }
    }

    private fun <T> parse(response: Response, serializer: KSerializer<T>): T = try {
        LibrusJson.decodeFromString(serializer, response.body.string())
    } catch (e: SerializationException) {
        throw LibrusException.UnexpectedResponse("Unexpected JSON from ${response.request.url.encodedPath}", e)
    } catch (e: IllegalArgumentException) {
        throw LibrusException.UnexpectedResponse("Unexpected JSON from ${response.request.url.encodedPath}", e)
    }

    private fun get(url: HttpUrl): Response = execute(baseRequest(url).build())

    private fun post(url: HttpUrl, body: FormBody, xhr: Boolean): Response {
        val builder = baseRequest(url).post(body)
        if (xhr) builder.header("X-Requested-With", "XMLHttpRequest")
        return execute(builder.build())
    }

    private fun followRedirects(first: Response): Response {
        var response = first
        repeat(MAX_REDIRECTS) {
            if (!response.isRedirect) return response
            val location = response.header("Location")
            val from = response.request.url
            response.close()
            if (location == null) throw LibrusException.UnexpectedResponse("Redirect without Location")
            response = get(endpoints.resolveRedirect(from, location))
        }
        response.close()
        throw LibrusException.UnexpectedResponse("Too many redirects")
    }

    private fun execute(request: Request): Response {
        if (!endpoints.allows(request.url)) {
            throw LibrusException.UnexpectedResponse("Refusing request to disallowed host: ${request.url.host}")
        }
        val response = http.newCall(request).execute()
        trace?.invoke(describe(request, response))
        return response
    }

    private fun describe(request: Request, response: Response): String = buildString {
        append("${request.method} ${redact(request.url)} -> ${response.code}")
        response.header("Location")?.let { loc ->
            append(" Location: ")
            append(request.url.resolve(loc)?.let(::redact) ?: "<malformed>")
        }
        response.header("Refresh")?.let { refresh ->
            append(" Refresh: ")
            append(LONG_TOKEN.replace(refresh.substringBefore('?'), "…"))
        }
        val names = response.headers("Set-Cookie").map { it.substringBefore('=').trim() }
        if (names.isNotEmpty()) append(" set-cookie: $names")
        append(" headers: ${response.headers.names()}")
    }

    private fun baseRequest(url: HttpUrl) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")

    /** Network problems surface as [IOException] (retryable); protocol problems as [LibrusException]. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun senderName(full: String?, first: String?, last: String?): String =
        full?.takeIf { it.isNotBlank() } ?: listOfNotNull(first, last).joinToString(" ")

    private companion object {
        const val CLIENT_ID = "46"
        val LONG_TOKEN = Regex("[A-Za-z0-9_-]{20,}")

        /** host + path only; long path segments (one-time tokens) elided, query dropped. */
        fun redact(url: HttpUrl): String =
            url.host + url.encodedPathSegments.joinToString("/", prefix = "/") { LONG_TOKEN.replace(it, "…") }
        const val MAX_REDIRECTS = 10
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}

public data class DownloadPolicy(
    val attempts: Int = 5,
    val retryDelayMillis: Long = 2_000,
    val maxBytes: Long = 50L * 1024 * 1024,
)

internal class InMemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            store.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) store += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = store.clear()
}
