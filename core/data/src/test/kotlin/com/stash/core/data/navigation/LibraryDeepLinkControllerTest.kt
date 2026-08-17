package com.stash.core.data.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryDeepLinkControllerTest {

    @Test
    fun `consume returns the pending focus exactly once`() {
        val controller = LibraryDeepLinkController()
        controller.request(LibraryFocus.LIKED)
        assertEquals(LibraryFocus.LIKED, controller.consume())
        assertNull("second consume must be empty", controller.consume())
    }

    @Test
    fun `consume with nothing pending is null`() {
        assertNull(LibraryDeepLinkController().consume())
    }
}
