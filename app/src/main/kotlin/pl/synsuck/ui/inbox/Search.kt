package pl.synsuck.ui.inbox

import pl.synsuck.data.db.MessageRow

/** Longer input is cut off; nobody types a search phrase this long. */
const val MAX_QUERY_LENGTH = 100

/**
 * Case-insensitive match on sender, subject and text. Unopened messages only have the
 * truncated list preview; the full body is searched once the message has been opened.
 */
fun MessageRow.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return senderName.contains(q, ignoreCase = true) ||
        topic.contains(q, ignoreCase = true) ||
        preview.contains(q, ignoreCase = true) ||
        body?.contains(q, ignoreCase = true) == true
}
