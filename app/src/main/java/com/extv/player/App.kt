package com.extv.player

import android.app.Application
import com.extv.player.data.IptvRepository
import com.extv.player.data.LocalStore
import com.extv.player.data.M3uRepository
import com.extv.player.data.Parental
import com.extv.player.data.Profile
import com.extv.player.data.ProfileType
import com.extv.player.data.XtreamRepository
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var store: LocalStore
        private set
    lateinit var parental: Parental
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", store.userAgent).build())
            }
            .build()
    }

    private var current: Pair<String, IptvRepository>? = null
    private var repositoryOverride: IptvRepository? = null

    /** Moteur VLC partagé par les aperçus intégrés (TV en direct, multi-écrans). */
    val libVlc: LibVLC by lazy {
        LibVLC(this, arrayListOf("--network-caching=3000", "--http-reconnect", "--clock-jitter=0", "--clock-synchro=0"))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = LocalStore(this)
        parental = Parental(store)
        // Compte les allumages de l'écran (TV sortie de veille) : l'alerte de fin d'abonnement se réaffiche.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    screenOnCount.intValue++
                }
            },
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Nombre de fois où l'écran a été rallumé depuis le lancement d'EXTV. */
    val screenOnCount = androidx.compose.runtime.mutableIntStateOf(0)

    fun createRepository(p: Profile): IptvRepository = when (p.type) {
        ProfileType.XTREAM -> XtreamRepository(p, http, store)
        ProfileType.M3U -> M3uRepository(p, http)
    }

    /** Source du profil actif (gardée en mémoire tant que le profil ne change pas). */
    @Synchronized
    fun repository(): IptvRepository? {
        repositoryOverride?.let { return it }
        val p = store.activeProfile() ?: return null
        current?.let { if (it.first == p.id) return it.second }
        return createRepository(p).also { current = p.id to it }
    }

    /** Oublie les catalogues en mémoire (changement de profil ou « Actualiser les listes »). */
    @Synchronized
    fun resetRepository() {
        current = null
    }

    companion object {
        lateinit var instance: App
            private set

        /** Pour les captures d'écran automatiques : données d'exemple, sans réseau. */
        fun installForScreenshots(store: LocalStore, repository: IptvRepository) {
            instance = App().apply {
                this.store = store
                parental = Parental(store)
                repositoryOverride = repository
            }
        }
    }
}
