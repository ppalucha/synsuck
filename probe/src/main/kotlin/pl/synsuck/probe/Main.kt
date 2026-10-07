package pl.synsuck.probe

import kotlinx.coroutines.runBlocking
import pl.synsuck.librus.Folder
import pl.synsuck.librus.LibrusClient
import pl.synsuck.librus.LibrusException
import pl.synsuck.librus.MessageSummary
import kotlin.io.path.createTempFile
import kotlin.system.exitProcess

/**
 * Exercises librus-client against a real account and prints a redacted report.
 * Names, subjects and message text are hidden unless --show-content is passed.
 * Never opens unread messages (that would mark them as read).
 *
 * Usage: probe <login> [--show-content] [--trace]
 * Password: from the LIBRUS_PASS environment variable if set, otherwise prompted (needs a TTY).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val login = args.firstOrNull { !it.startsWith("--") } ?: run {
        System.err.println("usage: probe <login> [--show-content] [--trace]")
        exitProcess(2)
    }
    val showContent = "--show-content" in args
    val trace: ((String) -> Unit)? = if ("--trace" in args) { line -> println("    · $line") } else null
    val password = readPassword(login)
    val client = LibrusClient(trace = trace)

    try {
        step("login") {
            val me = try {
                client.login(login, password)
            } finally {
                password.fill('\u0000')
            }
            "ok, accountId present=${me.accountId != 0L}" + if (showContent) ", name=${me.firstName} ${me.lastName}" else ""
        }

        step("unread counts") {
            val c = client.unreadCounts()
            "inbox=${c.inbox}, archiveInbox=${c.archiveInbox}"
        }

        val all = mutableListOf<MessageSummary>()
        for (folder in Folder.entries) {
            step("list $folder page 1") {
                val page = client.listMessages(folder, page = 1, limit = 20)
                all += page.items
                buildString {
                    append("total=${page.total}, onPage=${page.items.size}, ")
                    append("unread=${page.items.count { it.isUnread }}, withAttachments=${page.items.count { it.hasAttachments }}")
                    for (m in page.items) {
                        append("\n      ${m.id} ${if (m.isUnread) "UNREAD" else "read  "} ")
                        append(if (m.hasAttachments) "📎 " else "   ")
                        append("sent=${m.sentAt} readAt=${m.readAt}")
                        if (showContent) append(" | ${m.senderName} | ${m.topic}")
                    }
                }
            }
        }

        // Only already-read messages: opening an unread one would mark it read in Librus.
        val candidate = all.firstOrNull { !it.isUnread && it.hasAttachments } ?: all.firstOrNull { !it.isUnread }
        if (candidate == null) {
            println("• detail: skipped (no already-read messages to open safely)")
            return@runBlocking
        }

        val detail = step(
            "detail ${candidate.folder} ${candidate.id}",
            describe = { d ->
                buildString {
                    append("bodyChars=${d.body.length}, lines=${d.body.lines().size}, ")
                    append("looksHtml=${Regex("</?[a-zA-Z][^>]*>").containsMatchIn(d.body)}, ")
                    append("attachments=${d.attachments.size}, noReply=${d.noReply}, attachmentInfo=${d.attachmentInfo != null}")
                    if (showContent) append("\n--- body ---\n${d.body}\n------------")
                }
            },
        ) { client.getMessage(candidate.folder, candidate.id) }

        val attachment = detail.attachments.firstOrNull()
        if (attachment == null) {
            println("• download: skipped (no attachments on that message)")
        } else {
            step("download attachment ${attachment.id}") {
                val tmp = createTempFile("probe", ".bin").toFile()
                try {
                    val r = client.downloadAttachment(detail.folder, attachment.id, detail.id, tmp)
                    val head = tmp.inputStream().use { it.readNBytes(16) }
                    val kind = when {
                        head.decodeToString().startsWith("%PDF") -> "PDF"
                        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> "ZIP/Office"
                        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "JPEG"
                        head.size >= 4 && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() -> "PNG"
                        head.decodeToString().trimStart().startsWith("<") -> "HTML/XML (intermediate page?)"
                        else -> "other (" + head.take(8).joinToString("") { "%02x".format(it) } + ")"
                    }
                    if (kind.startsWith("HTML")) println(htmlSkeleton(tmp))
                    "contentType=${r.contentType}, fileName=" + (if (showContent) r.fileName else (r.fileName != null)) +
                        ", bytes=${r.bytesWritten}, kind=$kind"
                } finally {
                    tmp.delete()
                }
            }
        }

        step("session reuse (/api/me again)") { client.me(); "ok" }
    } finally {
        client.clearSession()
    }
}

/**
 * Structure of an HTML page with secrets and visible text removed: scripts, forms, inputs,
 * links and meta refresh are kept, tokens become <TOKEN>, attribute values of inputs are dropped.
 */
private fun htmlSkeleton(file: java.io.File, maxChars: Int = 6000): String {
    val html = file.inputStream().use { it.readNBytes(64 * 1024) }.decodeToString()
    val token = Regex("[A-Za-z0-9_\\-]{20,}")
    val interesting = Regex(
        "<script\\b[^>]*>.*?</script>|<(form|input|a|meta|iframe|button|link|img)\\b[^>]*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    val skeleton = interesting.findAll(html).joinToString("\n") { m ->
        m.value
            .replace(Regex("(value\\s*=\\s*)(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE), "$1\"…\"")
            .replace(token, "<TOKEN>")
    }
    val tags = Regex("<([a-zA-Z][a-zA-Z0-9]*)").findAll(html)
        .map { it.groupValues[1].lowercase() }.groupingBy { it }.eachCount()
    val text = html
        .replace(Regex("<(style|script)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace(token, "<TOKEN>")
        .replace(Regex("\\s+"), " ")
        .trim()
    // Strip control characters before printing to the terminal.
    val cntrl = Regex("[\\p{Cntrl}&&[^\\n\\t]]")
    return buildString {
        append("--- page skeleton (${html.length} chars) ---\n")
        append(skeleton.replace(cntrl, "").take(maxChars))
        append("\n--- tags: $tags")
        append("\n--- visible text: ${text.replace(cntrl, "").take(1500)}")
        append("\n---")
    }
}

private fun readPassword(login: String): CharArray {
    // The env String itself can't be wiped; the CharArray copy is zeroed after login.
    System.getenv("LIBRUS_PASS")?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
    val console = System.console() ?: run {
        System.err.println("set LIBRUS_PASS or run from an interactive terminal")
        exitProcess(2)
    }
    return console.readPassword("Librus password for %s: ", login)
}

private inline fun <T> step(name: String, describe: (T) -> String = { it.toString() }, block: () -> T): T {
    try {
        val result = block()
        println("✓ $name: ${describe(result)}")
        return result
    } catch (e: LibrusException) {
        println("✗ $name: ${e::class.simpleName}: ${e.message}" + (e.cause?.let { " (cause: ${it::class.simpleName}: ${it.message})" } ?: ""))
        exitProcess(1)
    } catch (e: java.io.IOException) {
        println("✗ $name: network: ${e::class.simpleName}: ${e.message}")
        exitProcess(1)
    }
}
