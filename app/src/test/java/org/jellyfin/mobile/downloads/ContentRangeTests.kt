package org.jellyfin.mobile.downloads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContentRangeTests {
    @Test
    fun `resumed media reports the actual byte offset and complete file size`() {
        assertEquals(ContentRange(4096, 8191, 8192), ContentRange.fromContentRangeHeader("bytes 4096-8191/8192"))
    }

    @Test
    fun `already complete file has a total even when no range is satisfiable`() {
        assertEquals(8192, ContentRange.fromContentRangeHeader("bytes */8192").total)
    }

    @Test
    fun `a complete response has an inclusive last byte`() {
        assertEquals(ContentRange(0, 8191, 8192), ContentRange.fromContentLengthHeader("8192"))
    }

    @Test
    fun `malformed ranges cannot validate an offline file`() {
        listOf("items 0-9/10", "bytes 10-9/10", "bytes 0-10/10", "bytes 0-9/*", "bytes 0-9/-1").forEach {
            assertThrows(IllegalArgumentException::class.java) { ContentRange.fromContentRangeHeader(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ContentRange.fromContentLengthHeader("-1") }
    }
}
