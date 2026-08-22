package com.showtracker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AutoBackupTest {
    private fun name(
        day: Int,
        hour: Int = 3,
        minute: Int = 0,
        second: Int = 0,
    ) = backupFileName(LocalDateTime.of(2026, 8, day, hour, minute, second))

    @Test
    fun `names carry the time to the second so two runs in a day cannot collide`() {
        val morning = backupFileName(LocalDateTime.of(2026, 8, 22, 3, 0, 0))
        val evening = backupFileName(LocalDateTime.of(2026, 8, 22, 21, 30, 15))

        assertEquals("show-tracker-backup-2026-08-22-030000.json", morning)
        assertEquals("show-tracker-backup-2026-08-22-213015.json", evening)
    }

    @Test
    fun `the name format sorts lexicographically in time order`() {
        // The retention rule sorts by name rather than parsing dates, so this is the
        // property it rests on.
        val names = listOf(name(9), name(10), name(22), name(1))
        assertEquals(listOf(name(1), name(9), name(10), name(22)), names.sorted())
    }

    @Test
    fun `keeps the newest and prunes the rest`() {
        val names = (1..20).map { name(it) }
        val pruned = backupsToPrune(names, keep = 14)

        assertEquals(6, pruned.size)
        // Days 1..6 are the oldest six.
        assertEquals((1..6).map { name(it) }.toSet(), pruned.toSet())
    }

    @Test
    fun `prunes nothing while under the limit`() {
        val names = (1..5).map { name(it) }
        assertEquals(emptyList<String>(), backupsToPrune(names, keep = 14))
    }

    @Test
    fun `never proposes deleting a file this app did not write`() {
        // The folder is the user's, not ours. Anything unrecognised is invisible to
        // retention rather than treated as an old backup.
        val strangers =
            listOf(
                "taxes.pdf",
                "show-tracker-2026-08-22.json", // a manual export, different prefix
                "show-tracker-backup-notadate.json",
                "show-tracker-backup-2026-08-22-030000.json.bak",
                "SHOW-TRACKER-BACKUP-2026-08-22-030000.json",
                "",
            )
        val ours = (1..20).map { name(it) }

        val pruned = backupsToPrune(strangers + ours, keep = 0)

        assertEquals(ours.toSet(), pruned.toSet())
        assertTrue(strangers.none { it in pruned })
    }

    @Test
    fun `recognises its own names and nothing else`() {
        assertTrue(isBackupFileName(name(22)))
        assertFalse(isBackupFileName("show-tracker-2026-08-22.json"))
        assertFalse(isBackupFileName("show-tracker-backup-2026-08-22.json"))
        assertFalse(isBackupFileName("holiday.jpg"))
    }

    @Test
    fun `a keep of zero prunes every backup, and a negative keep is treated as zero`() {
        val names = (1..3).map { name(it) }
        assertEquals(names.size, backupsToPrune(names, keep = 0).size)
        // Guards against `drop(-1)`, which throws rather than dropping nothing.
        assertEquals(names.size, backupsToPrune(names, keep = -5).size)
    }

    @Test
    fun `handles an empty folder`() {
        assertEquals(emptyList<String>(), backupsToPrune(emptyList()))
    }
}
