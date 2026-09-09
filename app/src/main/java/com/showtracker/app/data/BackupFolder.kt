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
     * Returns the name the provider actually gave the file, which is not always the one it
     * was asked for: SAF deduplicates a collision into "... (1).json". Reporting the
     * requested name would name a file that is not there, and - because
     * [isBackupFileName] tests an exact length - a deduplicated one is invisible to
     * retention as well, so it would sit in the user's folder for ever. Reading the name
     * back settles both.
     *
     * Throws if the folder is gone or the grant was revoked, which the caller reports
     * rather than swallowing - a backup that has quietly stopped working is worse than no
     * backup, because it is trusted.
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

            // After the write, so a provider that only settles the name on creation has
            // already done so. Falls back to the requested name: failing a backup that is
            // on disk over a cosmetic lookup would be the wrong way round.
            val written = displayNameOf(resolver, file) ?: name

            prune(resolver, tree)
            written
        }

    /** The display name of one document, or null if it cannot be read. */
    private fun displayNameOf(
        resolver: ContentResolver,
        document: Uri,
    ): String? =
        runCatching {
            resolver
                .query(
                    document,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()

    /**
     * The folder's name as its provider reports it.
     *
     * Asked rather than parsed out of the URI. A tree URI's document id is provider-
     * specific and frequently opaque - Google Drive's is a base64 blob - so the string
     * teased out of one is not a folder name, it is noise where a name should be.
     */
    suspend fun displayName(tree: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc =
                    DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree),
                    )
                context.contentResolver
                    .query(
                        doc,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull()
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
