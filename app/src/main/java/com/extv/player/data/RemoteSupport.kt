package com.extv.player.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bouton « Assistance » : prépare le boîtier pour qu'un installateur prenne la main avec AnyDesk.
 * Trois étapes, faites une seule fois : AnyDesk, son « Control Plugin » (pour piloter, pas seulement
 * voir l'écran), puis l'activation du plugin dans Accessibilité (plugin générique ad1 uniquement).
 * Ensuite le bouton ouvre directement AnyDesk.
 */
object RemoteSupport {
    const val ANYDESK = "com.anydesk.anydeskandroid"
    private const val PLUGIN_PREFIX = "com.anydesk.adcontrol."
    const val PLUGIN_GENERIC = "com.anydesk.adcontrol.ad1"

    /** Plugins connus (doivent aussi figurer dans <queries> du manifeste pour être visibles). */
    private val KNOWN_PLUGINS = listOf(
        PLUGIN_GENERIC,
        "com.anydesk.adcontrol.formuler1",
        "com.anydesk.adcontrol.formulergtv1",
        "com.anydesk.adcontrol.ugoos1",
        "com.anydesk.adcontrol.aosp",
        "com.anydesk.adcontrol.aosp2",
    )

    private const val APK_URL = "https://download.anydesk.com/anydesk.apk"
    private const val PLUGIN_URL = "https://anydesk.com/downloads/android-plugin/"
    private const val WEB_URL = "anydesk.com/fr/downloads/android"

    enum class Step { ANYDESK, PLUGIN, ACCESSIBILITY, READY }

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(SafeDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun isInstalled(context: Context): Boolean = installed(context, ANYDESK)

    fun installedPlugin(context: Context): String? = KNOWN_PLUGINS.firstOrNull { installed(context, it) }

    /** Le plugin générique (ad1) est-il activé dans Accessibilité ? */
    fun accessibilityEnabled(context: Context): Boolean {
        val list = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return list.contains(PLUGIN_PREFIX)
    }

    /** Ce qu'il reste à faire avant de pouvoir ouvrir AnyDesk avec le contrôle à distance. */
    fun nextStep(context: Context): Step {
        if (!isInstalled(context)) return Step.ANYDESK
        // Le contrôle à distance demande Android 7 ; avant, AnyDesk ne fait que montrer l'écran.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return Step.READY
        val plugin = installedPlugin(context) ?: return Step.PLUGIN
        // Les plugins des fabricants n'ont pas besoin d'Accessibilité ; le générique, si.
        if (plugin == PLUGIN_GENERIC && !accessibilityEnabled(context)) return Step.ACCESSIBILITY
        return Step.READY
    }

    /**
     * Plugin adapté au boîtier, d'après la liste d'AnyDesk (support.anydesk.com, « Android Control Plugins »).
     * Sans plugin propre à la marque : le plugin générique ad1.
     */
    fun pluginForDevice(): String {
        val id = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}".lowercase()
        return when {
            "formuler" in id && "gtv" in id -> "com.anydesk.adcontrol.formulergtv1"
            "formuler" in id -> "com.anydesk.adcontrol.formuler1"
            "ugoos" in id -> "com.anydesk.adcontrol.ugoos1"
            else -> PLUGIN_GENERIC
        }
    }

    /** Ouvre AnyDesk s'il est installé. */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(ANYDESK) ?: pm.getLaunchIntentForPackage(ANYDESK) ?: return false
        return start(context, intent)
    }

    /** Ouvre les réglages d'Accessibilité pour activer le plugin. */
    fun openAccessibility(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) || start(context, Intent(Settings.ACTION_SETTINGS))

    /** Télécharge AnyDesk et ouvre « Installer ». Renvoie le message à afficher. */
    suspend fun installAnyDesk(context: Context, progress: (Float) -> Unit): String {
        if (!Updater.canInstall(context)) {
            Updater.openInstallPermission(context)
            return "Autorisez EXTV à installer des applications, puis revenez appuyer sur Réessayer."
        }
        val apk = runCatching { download(context, APK_URL, "AnyDesk.apk", progress) { it == ANYDESK } }.getOrNull()
        if (apk != null && install(context, apk)) return "Appuyez sur « Installer », puis revenez dans EXTV."
        if (start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ANYDESK")))) {
            return "Installez AnyDesk depuis le Play Store, puis revenez dans EXTV."
        }
        return "Téléchargement impossible. Installez AnyDesk depuis $WEB_URL (ou demandez à votre installateur)."
    }

    /** Télécharge le Control Plugin adapté (sinon le générique) et ouvre « Installer ». */
    suspend fun installPlugin(context: Context, progress: (Float) -> Unit): String {
        if (!Updater.canInstall(context)) {
            Updater.openInstallPermission(context)
            return "Autorisez EXTV à installer des applications, puis revenez appuyer sur Réessayer."
        }
        val candidates = listOf(pluginForDevice(), PLUGIN_GENERIC).distinct()
        for (pkg in candidates) {
            val apk = runCatching {
                download(context, PLUGIN_URL + pkg, "AnyDeskPlugin.apk", progress) { it == pkg }
            }.getOrNull() ?: continue
            if (install(context, apk)) return "Appuyez sur « Installer », puis revenez dans EXTV."
        }
        if (start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLUGIN_GENERIC")))) {
            return "Installez « AnyDesk Control Plugin » depuis le Play Store, puis revenez dans EXTV."
        }
        return "Téléchargement du plugin impossible. Ouvrez AnyDesk : il proposera lui-même le bon plugin."
    }

    private suspend fun download(
        context: Context,
        url: String,
        name: String,
        progress: (Float) -> Unit,
        expected: (String?) -> Boolean,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, name)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
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
        // On n'installe que le fichier attendu (pas une page web ni une autre appli).
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        check(expected(info?.packageName)) { "fichier inattendu" }
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
