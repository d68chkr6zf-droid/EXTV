package com.extv.player.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Bouton « Assistance à distance » : ouvre AnyDesk sur le boîtier pour que l'installateur prenne la main.
 * Si AnyDesk n'est pas installé, ouvre sa page du Play Store (ou, à défaut, renvoie un message à afficher).
 */
object RemoteSupport {
    const val ANYDESK = "com.anydesk.anydeskandroid"
    private const val WEB_URL = "https://anydesk.com/fr/downloads/android"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(ANYDESK, 0); true }.getOrDefault(false)

    /** Ouvre AnyDesk ou sa page d'installation. Renvoie le message à afficher sous le bouton. */
    fun open(context: Context): String {
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(ANYDESK) ?: pm.getLaunchIntentForPackage(ANYDESK)
        if (launch != null && start(context, launch)) {
            return "AnyDesk s'ouvre : donnez à votre installateur le numéro à 9 chiffres affiché, puis acceptez sa demande."
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ANYDESK"))
        if (start(context, store)) {
            return "Installez AnyDesk (et « AnyDesk Control Plugin » s'il le demande), puis revenez appuyer sur ce bouton."
        }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ANYDESK"))
        if (start(context, web)) {
            return "Installez AnyDesk, puis revenez appuyer sur ce bouton."
        }
        return "AnyDesk n'est pas installé et ce boîtier n'a pas de Play Store. Téléchargez-le depuis $WEB_URL " +
            "(ou demandez à votre installateur), puis revenez appuyer sur ce bouton."
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
