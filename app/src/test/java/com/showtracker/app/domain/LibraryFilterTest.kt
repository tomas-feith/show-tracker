package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Filtering the library by name. Pure, so the matching rules are pinned here rather than
 * discovered by typing into a phone.
 */
class LibraryFilterTest {
    private fun show(
        id: Int,
        name: String,
    ) = TrackedShow(id = id, name = name, addedAt = "2026-01-01T00:00:00Z")

    private val library =
        listOf(
            show(1, "Shōgun"),
            show(2, "Glória"),
            show(3, "The Bear"),
            show(4, "Reacher"),
            show(5, "Severance"),
        )

    private fun names(query: String) = filterLibrary(library, query).map { it.name }

    @Test
    fun `returns everything for a blank query`() {
        assertEquals(library, filterLibrary(library, ""))
        assertEquals(library, filterLibrary(library, "   "))
    }

    @Test
    fun `matches on a substring, not only a prefix`() {
        // "The Bear" is exactly the case a prefix match fails: nobody remembers the article.
        assertEquals(listOf("The Bear"), names("bear"))
    }

    @Test
    fun `ignores case`() {
        assertEquals(listOf("Reacher"), names("REACHER"))
    }

    @Test
    fun `matches an accented name typed without the accent`() {
        // The whole point: nobody long-presses O to find Shōgun.
        assertEquals(listOf("Shōgun"), names("shogun"))
        assertEquals(listOf("Glória"), names("gloria"))
    }

    @Test
    fun `still matches when the accent is typed`() {
        assertEquals(listOf("Glória"), names("Glória"))
    }

    @Test
    fun `ignores surrounding whitespace`() {
        assertEquals(listOf("Severance"), names("  severance  "))
    }

    @Test
    fun `returns nothing when no name matches`() {
        assertEquals(emptyList<String>(), names("succession"))
    }

    @Test
    fun `keeps the order it was given`() {
        // The caller sorts afterwards; a filter that reshuffled would make the result read
        // differently from the same shows in the unfiltered list.
        assertEquals(listOf("The Bear", "Reacher", "Severance"), names("e"))
    }
}
