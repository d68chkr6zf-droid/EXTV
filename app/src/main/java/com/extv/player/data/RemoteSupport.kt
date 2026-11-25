package com.extv.player.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bouton « Assistance à distance » : ouvre AnyDesk sur le boîtier pour que l'installateur prenne la main.
 * S'il n'est pas installé, EXTV le télécharge depuis le site d'AnyDesk et lance son installation ;
 * en cas d'échec, ouvre sa page du Play Store.
 */
object RemoteSupport {
    const val ANYDESK = "com.anydesk.anydeskandroid"
    private const val APK_URL = "https://download.anydesk.com/anydesk.apk"
    private const val WEB_URL = "anydesk.com/fr/downloads/android"

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(SafeDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(ANYDESK, 0); true }.getOrDefault(false)

    /** Ouvre AnyDesk s'il est installé. */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(ANYDESK) ?: pm.getLaunchIntentForPackage(ANYDESK) ?: return false
        return start(context, intent)
    }

    /**
     * Télécharge AnyDesk et ouvre la fenêtre « Installer » d'Android. Renvoie le message à afficher.
     * progress reçoit une valeur entre 0 et 1.
     */
    suspend fun downloadAndInstall(context: Context, progress: (Float) -> Unit): String {
        if (!Updater.canInstall(context)) {
            Updater.openInstallPermission(context)
            return "Autorisez EXTV à installer des applications, puis revenez appuyer sur ce bouton."
        }
        val apk = runCatching { download(context, progress) }.getOrNull()
        if (apk != null && install(context, apk)) {
            return "Appuyez sur « Installer ». Ouvrez ensuite AnyDesk avec ce bouton " +
                "(installez « AnyDesk Control Plugin » s'il vous le propose)."
        }
        // Téléchargement impossible : Play Store, puis message.
        if (start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ANYDESK")))) {
            return "Installez AnyDesk depuis le Play Store, puis revenez appuyer sur ce bouton."
        }
        return "Téléchargement impossible. Installez AnyDesk depuis $WEB_URL (ou demandez à votre installateur), " +
            "puis revenez appuyer sur ce bouton."
    }

    private suspend fun download(context: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "AnyDesk.apk")
        client.newCall(Request.Builder().url(APK_URL).build()).execute().use { response ->
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
        // On n'installe que le vrai AnyDesk (pas une page d'erreur ni un autre fichier).
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        check(info?.packageName == ANYDESK) { "fichier inattendu" }
        file
    }

    private fun install(context: Context, apk: File): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", apk) }.getOrNull() ?: return false
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
