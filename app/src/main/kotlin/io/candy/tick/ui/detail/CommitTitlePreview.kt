package io.candy.tick.ui.detail

import java.text.BreakIterator
import java.util.Locale

private const val TITLE_LIMIT = 512

/** Bound the text shaped for a three-line preview, preserving complete grapheme clusters. */
internal fun commitTitlePreview(message: String): String {
    for (index in 0 until minOf(message.length, TITLE_LIMIT + 1)) {
        if (message[index] == '\n' || message[index] == '\r') return message.substring(0, index)
    }
    if (message.length <= TITLE_LIMIT) return message

    // Keep room for the ellipsis. Two neighbouring printable ASCII characters always separate.
    val cut = TITLE_LIMIT - 1
    val end = if (message[cut - 1] in ' '..'~' && message[cut] in ' '..'~') {
        cut
    } else {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
        val nextCodePointEnd = cut + Character.charCount(message.codePointAt(cut))
        breaks.setText(message.substring(0, nextCodePointEnd))
        if (breaks.isBoundary(cut)) cut else breaks.preceding(cut)
    }
    return message.substring(0, end) + "…"
}
