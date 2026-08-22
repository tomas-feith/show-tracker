package com.showtracker.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writing exports into a folder the user picked, through the Storage Access Framework.
 *
 * SAF rather than a storage permission, and rather than the Drive API: the app asks for no
 * account and no broad file access, the user hands it exactly one folder, and that grant
 * survives reboots once taken persistably. Point it at a folder that a sync app already
 * mirrors - Drive, Dropbox, Nextcloud - and the exports leave the phone without this app
 * ever holding a credential.
 *
 * [DocumentsContract] directly rather than the `documentfile` library: this needs four
 * calls, all of them here, and the library is a thin wrapper over exactly these.
 */
class BackupFolder(
    private val context: Context,
) {
    /**
     * Write [json] as a new dated file, then delete the oldest beyond [BACKUPS_KEPT].
     *
     * Returns the name written. Throws if the folder is gone or the grant was revoked,
     * which the caller reports rather than swallowing - a backup that has quietly stopped
     * working is worse than no backup, because it is trusted.
     */
    suspend fun write(
        tree: Uri,
        name: String,
        json: String,
    ): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val dir = directoryUri(tree)

            val file =
                DocumentsContract.createDocument(resolver, dir, MIME, name)
                    ?: error("Could not create a file in the backup folder.")

            resolver.openOutputStream(file).use { stream ->
                requireNotNull(stream) { "Could not write to the backup folder." }
                    .write(json.toByteArray(Charsets.UTF_8))
            }

            prune(resolver, tree)
            name
        }

    /** Names of this app's exports already in the folder, newest first. */
    suspend fun list(tree: Uri): List<String> =
        withContext(Dispatchers.IO) {
            children(context.contentResolver, tree)
                .map { it.second }
                .filter(::isBackupFileName)
                .sortedDescending()
        }

    /**
     * Is the grant still good?
     *
     * A folder can be deleted, or its permission revoked, long after it was chosen. The
     * settings screen asks this so it can say so, instead of showing a folder that has not
     * actually accepted a write in months.
     */
    suspend fun isUsable(tree: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { children(context.contentResolver, tree) }.isSuccess
        }

    private fun prune(
        resolver: ContentResolver,
        tree: Uri,
    ) {
        val present = children(resolver, tree)
        val doomed = backupsToPrune(present.map { it.second }).toSet()

        present
            .filter { it.second in doomed }
            .forEach { (documentId, _) ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                    )
                }
                // A failed delete is not a failed backup. The new file is already written,
                // which is the part that matters; the folder just keeps one extra.
            }
    }

    /** Document id and display name for everything directly inside the folder. */
    private fun children(
        resolver: ContentResolver,
        tree: Uri,
    ): List<Pair<String, String>> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )

        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )

        return resolver.query(childrenUri, columns, null, null, null).use { cursor ->
            requireNotNull(cursor) { "The backup folder is no longer available." }
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getString(1))
                }
            }
        }
    }

    private fun directoryUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )

    private companion object {
        const val MIME = "application/json"
    }
}
