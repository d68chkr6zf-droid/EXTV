package com.extv.player.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Sauvegarde des favoris dans la mémoire interne du boîtier (dossier Download/EXTV),
 * pour les retrouver après une réinstallation de l'application.
 */
object FavoritesBackup {
    const val FILE_NAME = "extv-favoris.json"
    private const val FOLDER = "EXTV"

    /** Android 9 et moins : il faut l'autorisation d'accès au stockage. */
    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun save(context: Context, json: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val uri = findOwnFile(context) ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/")
                },
            ) ?: error("Impossible de créer le fichier de sauvegarde")
            resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                ?: error("Impossible d'écrire la sauvegarde")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(json)
        }
    }

    /** Contenu de la sauvegarde, ou null si elle est introuvable sans l'aide de l'utilisateur. */
    fun load(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findOwnFile(context) ?: return null
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
        @Suppress("DEPRECATION")
        val file = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER), FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun read(context: Context, uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }

    /** Fichier de sauvegarde déjà créé par cette installation de l'application (Android 10+). */
    private fun findOwnFile(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }
}
