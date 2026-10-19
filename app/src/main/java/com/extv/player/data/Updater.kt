package com.extv.player.data

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.extv.player.BuildConfig
import com.extv.player.MainActivity
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

    /** Message d'Android quand l'installation échoue ou est annulée (affiché par la fenêtre de mise à jour). */
    val installFailed = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * Installe l'APK par une session PackageInstaller. Sur Android 12+, si EXTV a lui-même installé la
     * version en place, Android accepte la mise à jour sans confirmation ; sinon il affiche « Installer ».
     */
    fun install(context: Context, apk: File) {
        installFailed.value = null
        runCatching { installWithSession(context, apk) }.onFailure { installWithIntent(context, apk) }
    }

    private fun installWithSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("EXTV.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallReceiver::class.java).setAction("com.extv.player.INSTALL_RESULT"),
                flags,
            )
            // Filet de sécurité : si Android ferme EXTV sans prévenir, l'appli se rouvre d'elle-même.
            scheduleRestart(context, 90_000)
            session.commit(callback.intentSender)
        }
    }

    private fun installWithIntent(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Relance d'EXTV après la mise à jour. Depuis Android 10, une appli fermée ne peut plus ouvrir son
     * écran toute seule ; seule une « alarme réveil » (AlarmClock) en a le droit. On en programme une
     * qui ouvre l'accueil d'EXTV, annulée dès que l'appli redémarre ou si l'installation échoue.
     */
    fun scheduleRestart(context: Context, delayMs: Long) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + delayMs
            val pi = restartIntent(context)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi) else am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelRestart(context: Context) {
        runCatching { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(restartIntent(context)) }
    }

    private fun restartIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        // Android 14+ : EXTV autorise explicitement l'ouverture de son écran depuis l'arrière-plan.
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        return PendingIntent.getActivity(context, 4242, intent, flags, options)
    }
}
