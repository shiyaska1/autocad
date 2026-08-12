package com.sketchdxf.app.data

import android.content.Context
import android.net.Uri
import com.sketchdxf.app.dxf.SketchAttachmentStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bundles everything the app stores locally — the Room database (every work, shape, and block)
 * plus every attached photo/PDF/DXF/preview file — into a single .zip a user can save wherever
 * they choose and bring back later, independent of Android's own (Google-account-gated,
 * 25MB-capped) Auto Backup. The database connection is always closed around the raw file copy so
 * nothing is read or written mid-transaction; [AppDatabase.get] transparently reopens it after.
 */
object BackupManager {

    fun export(context: Context): File? = runCatching {
        AppDatabase.closeAndReset()
        val dbFile = context.getDatabasePath(AppDatabase.DB_FILE_NAME)
        val sketchesDir = SketchAttachmentStore.dir(context)
        // The "shared" cache path is already declared for FileProvider (see file_paths.xml), so
        // the result can be shared straight away without a separate copy.
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zipFile = File(sharedDir, "sketchdxf_backup_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { f ->
                if (f.exists()) addFile(zos, f, "db/${f.name}")
            }
            sketchesDir.walkTopDown().filter { it.isFile }.forEach { f ->
                addFile(zos, f, "sketches/${f.relativeTo(sketchesDir).path}")
            }
        }
        zipFile
    }.getOrNull()

    /** Overwrites the current database and attachments with the backup's contents. Returns true
     *  on success — the app must be restarted afterward so every screen picks up the restored
     *  data instead of stale in-memory state from before the restore. */
    fun import(context: Context, zipUri: Uri): Boolean = runCatching {
        AppDatabase.closeAndReset()
        val dbFile = context.getDatabasePath(AppDatabase.DB_FILE_NAME)
        val sketchesDir = SketchAttachmentStore.dir(context)
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        sketchesDir.listFiles()?.forEach { it.delete() }

        var wroteDb = false
        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val target = when {
                            entry.name.startsWith("db/") -> File(dbFile.parentFile, entry.name.removePrefix("db/")).also { wroteDb = true }
                            entry.name.startsWith("sketches/") -> File(sketchesDir, entry.name.removePrefix("sketches/"))
                            else -> null
                        }
                        if (target != null) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return@runCatching false
        wroteDb
    }.getOrDefault(false)

    private fun addFile(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
