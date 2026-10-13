package com.extv.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.extv.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mise à jour depuis la page de téléchargement GitHub : version.json donne le numéro de la dernière
 * version publiée, EXTV.apk est installé par-dessus l'actuelle (même clé de signature).
 */
object Updater {
    class Release(val versionCode: Int, val versionName: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Résultat de la dernière vérification automatique (affiché dans les paramètres). */
    val lastAutoCheck = androidx.compose.runtime.mutableStateOf("pas encore faite")

    /** Dernière version publiée si elle est plus récente que celle installée, sinon null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.UPDATE_URL + "version.json?t=${System.currentTimeMillis()}").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val o = JSONObject(response.body!!.string())
            val release = Release(o.getInt("versionCode"), o.optString("versionName", ""))
            release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    /** Télécharge l'APK dans le cache de l'appli ; progress reçoit une valeur entre 0 et 1. */
    suspend fun download(context: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "EXTV.apk")
        val request = Request.Builder().url(BuildConfig.UPDATE_URL + "EXTV.apk").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body!!
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        }
        check(file.length() > 0) { "fichier vide" }
        file
    }

    /** Android 8+ : l'appli doit être autorisée à installer des applications. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Numéro de version contenu dans l'APK téléchargé (0 si le fichier est illisible ou incomplet). */
    fun apkVersionCode(context: Context, apk: File): Long {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** L'APK téléchargé est-il bien plus récent que la version installée ? */
    fun isNewer(context: Context, apk: File): Boolean = apkVersionCode(context, apk) > BuildConfig.VERSION_CODE

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
