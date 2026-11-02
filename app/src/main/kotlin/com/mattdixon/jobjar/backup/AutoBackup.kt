package com.mattdixon.jobjar.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.backupDue
import com.mattdixon.jobjar.data.backupFileName
import com.mattdixon.jobjar.data.backupsToRemove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Weekly backups to a folder you pick (Settings > Backup). Each is a normal backup file named by
 * date, the last few are kept, and it runs when the app opens and a week has passed. Off until a
 * folder is chosen.
 */
object AutoBackup {
    private const val PREFS_NAME = "job_jar_settings"
    private const val KEY_FOLDER = "backup_folder"
    private const val KEY_LAST = "backup_last"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun lastBackupAt(context: Context): Long? = prefs(context).getLong(KEY_LAST, 0L).takeIf { it > 0 }

    /** Keeps access to the folder across restarts, then backs up straight away. */
    suspend fun setFolder(context: Context, tree: Uri, repository: JobRepository): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
        prefs(context).edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_LAST).apply()
        return backUp(context, repository)
    }

    fun turnOff(context: Context) {
        folder(context)?.let { tree ->
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, flags) }
        }
        prefs(context).edit().remove(KEY_FOLDER).remove(KEY_LAST).apply()
    }

    suspend fun runIfDue(context: Context, repository: JobRepository) {
        if (folder(context) == null || !backupDue(lastBackupAt(context), System.currentTimeMillis())) return
        backUp(context, repository)
    }

    /** Writes today's backup and removes older ones past the few kept. False if it couldn't. */
    suspend fun backUp(context: Context, repository: JobRepository): Boolean {
        val tree = folder(context) ?: return false
        val now = System.currentTimeMillis()
        val text = repository.backupText(now)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                val folderDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
                val existing = children(context, tree, treeId)
                val name = backupFileName(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate())
                // Backing up twice in a day replaces that day's file rather than adding "(1)".
                existing[name]?.let { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
                val file = DocumentsContract.createDocument(resolver, folderDoc, "application/json", name) ?: return@runCatching false
                resolver.openOutputStream(file, "wt")?.bufferedWriter()?.use { it.write(text) } ?: return@runCatching false
                backupsToRemove((existing.keys + name).toList()).forEach { old ->
                    existing[old]?.let { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
                }
                true
            }.getOrDefault(false)
        }
        if (ok) prefs(context).edit().putLong(KEY_LAST, now).apply()
        return ok
    }

    /** The folder's name, for Settings ("Downloads"). */
    fun folderName(context: Context): String? {
        val tree = folder(context) ?: return null
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /** File name to document id, for what's in the folder. */
    private fun children(context: Context, tree: Uri, treeId: String): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(0)) }
        }.orEmpty()
    }
}
