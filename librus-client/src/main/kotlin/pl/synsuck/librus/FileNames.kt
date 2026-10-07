package pl.synsuck.librus

/** Bare, filesystem-safe file name (no directories, control or reserved characters), or null if nothing is left. */
public fun sanitizeFileName(name: String): String? = Decoding.sanitizeFileName(name)

/** Decodes a message-list preview (see [MessageSummary.preview]); plain text is returned unchanged. */
public fun decodePreview(raw: String): String = Decoding.preview(raw)
