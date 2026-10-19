package com.extv.player.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.extv.player.MainActivity

/**
 * Résultat de l'installation lancée par [Updater.install]. Si Android exige une confirmation
 * (première mise à jour, ou Android 11 et moins), on ouvre son écran « Installer ».
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Nouvelle version installée : EXTV se relance tout seul. Jusqu'à Android 9 on ouvre l'écran
            // directement ; ensuite Android l'interdit depuis l'arrière-plan, on passe par une alarme réveil.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    runCatching {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } else {
                    Updater.scheduleRestart(context, 1_000)
                }
            }
            else -> when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // L'abonné doit valider « Installer » : pas de relance forcée par-dessus cet écran.
                    Updater.cancelRestart(context)
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> {
                    Updater.cancelRestart(context)
                    Updater.installFailed.value = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "installation annulée"
                }
            }
        }
    }
}
