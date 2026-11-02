package com.mattdixon.jobjar.data

import java.time.LocalDate

/** Weekly backups keep this many files and remove older ones. */
const val BACKUPS_KEPT = 4

/** Days between automatic backups. */
const val BACKUP_EVERY_DAYS = 7L

/** "job-jar-2026-11-02.json": sorts by date, so the newest are easy to find. */
fun backupFileName(day: LocalDate): String = "job-jar-$day.json"

private val BACKUP_NAME = Regex("""job-jar-\d{4}-\d{2}-\d{2}\.json""")

/** Of the files in the backup folder, the older backups to remove (anything else is left alone). */
fun backupsToRemove(names: List<String>, keep: Int = BACKUPS_KEPT): List<String> =
    names.filter { BACKUP_NAME.matches(it) }.sortedDescending().drop(keep)

/** A backup's due when there's never been one, or the last was [BACKUP_EVERY_DAYS] days ago or more. */
fun backupDue(lastBackupAt: Long?, now: Long): Boolean =
    lastBackupAt == null || now - lastBackupAt >= BACKUP_EVERY_DAYS * 24 * 60 * 60 * 1000
