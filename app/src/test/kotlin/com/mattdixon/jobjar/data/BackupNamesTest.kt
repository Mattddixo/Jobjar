package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupNamesTest {
    @Test
    fun keepsTheNewestFourAndLeavesOtherFilesAlone() {
        val names = listOf(
            "job-jar-2026-10-05.json", "job-jar-2026-10-12.json", "notes.txt", "job-jar-2026-10-19.json",
            "job-jar-2026-10-26.json", "job-jar-2026-11-02.json", "job-jar-export.json"
        )
        assertEquals(listOf("job-jar-2026-10-05.json"), backupsToRemove(names))
        assertEquals("job-jar-2026-11-02.json", backupFileName(LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun dueWeekly() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(backupDue(null, 0))
        assertFalse(backupDue(0, 6 * day))
        assertTrue(backupDue(0, 7 * day))
    }
}
