package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIdentityTest {

    @Test
    fun `identity is source-qualified`() {
        val item = MediaItem(id = "42", sourceId = "primary", title = "a", uri = "content://a")
        assertEquals("primary:42", item.identity)
    }

    @Test
    fun `same id from different sources is not merged`() {
        val items = listOf(
            MediaItem(id = "42", sourceId = "primary", title = "a", uri = "content://a"),
            MediaItem(id = "42", sourceId = "saf", title = "a", uri = "content://b"),
        )
        assertEquals(2, items.distinctBy { it.identity }.size)
        assertEquals(1, items.distinctBy { it.id }.size)
    }
}
