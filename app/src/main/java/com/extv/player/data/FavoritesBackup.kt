package com.extv.player.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Sauvegarde des favoris dans la mémoire interne du boîtier, pour les retrouver après une réinstallation.
 *
 * Deux copies : un fichier JSON dans Download/EXTV, et une image dans DCIM/EXTV qui contient les
 * mêmes données. Après une réinstallation, Android n'autorise plus l'appli à relire son fichier JSON
 * sans passer par un sélecteur de fichiers (absent sur certaines TV) ; l'image, elle, se relit
 * avec la simple autorisation d'accès aux photos.
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

    const val IMAGE_NAME = "extv-favoris.png"
    private val MARKER = "\nEXTV-FAVORIS-V1\n".toByteArray()

    /** Autorisations nécessaires pour relire la copie image après une réinstallation. */
    val imageReadPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /** Copie image : une vignette EXTV suivie des données des favoris. */
    fun saveImage(context: Context, json: String) {
        val bytes = backupImage() + MARKER + json.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(IMAGE_NAME, "${Environment.DIRECTORY_DCIM}/$FOLDER/"),
                null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
            // Une image laissée par une installation précédente n'est plus modifiable : on en crée une nouvelle.
            val rewritten = existing != null && runCatching {
                resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            if (!rewritten) {
                val uri = resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, IMAGE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER/")
                    },
                ) ?: error("Impossible de créer la copie de sauvegarde")
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Impossible d'écrire la copie de sauvegarde")
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER)
            dir.mkdirs()
            File(dir, IMAGE_NAME).writeBytes(bytes)
        }
    }

    /** Relit la copie image la plus récente (y compris celle d'une installation précédente). */
    fun loadImage(context: Context): String? {
        val data: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uris = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("extv-favoris%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c -> buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0))) } }.orEmpty()
            uris.firstNotNullOfOrNull { uri ->
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    ?.takeIf { indexOf(it, MARKER) >= 0 }
            }
        } else {
            @Suppress("DEPRECATION")
            // DCIM/EXTV, ou Pictures/EXTV pour les sauvegardes des versions précédentes.
            listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES)
                .map { File(File(Environment.getExternalStoragePublicDirectory(it), FOLDER), IMAGE_NAME) }
                .firstOrNull { it.exists() }?.readBytes()
        }
        data ?: return null
        val at = indexOf(data, MARKER)
        if (at < 0) return null
        return data.copyOfRange(at + MARKER.size, data.size).decodeToString()
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in data.size - pattern.size downTo 0) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun backupImage(): ByteArray {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 8, 12))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        paint.color = Color.rgb(232, 176, 75)
        paint.textSize = 96f
        paint.isFakeBoldText = true
        canvas.drawText("EXTV", 320f, 190f, paint)
        paint.color = Color.WHITE
        paint.textSize = 30f
        paint.isFakeBoldText = false
        canvas.drawText("Sauvegarde des favoris", 320f, 250f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
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
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }
}
