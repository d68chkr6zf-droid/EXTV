package com.extv.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Lecture d'une bande-annonce dans l'appli, avec le lecteur YouTube intégrable officiel
 * (pas besoin de l'appli YouTube sur la TV). RETOUR ferme la bande-annonce.
 */
class TrailerActivity : ComponentActivity() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val trailer = intent.getStringExtra(EXTRA_TRAILER)?.trim().orEmpty()
        if (trailer.isEmpty()) {
            finish()
            return
        }
        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        web = view
        setContentView(view)

        val id = youtubeId(trailer)
        if (id != null) {
            val html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>
                </head><body>
                <iframe src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&hl=fr&cc_lang_pref=fr"
                  allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            // Une adresse d'origine est exigée par le lecteur YouTube intégré.
            view.loadDataWithBaseURL("https://com.extv.player", html, "text/html", "utf-8", null)
        } else {
            view.loadUrl(trailer)
        }
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TRAILER = "trailer"

        fun youtubeId(trailer: String): String? {
            val t = trailer.trim()
            if (!t.startsWith("http")) return t.takeIf { Regex("[\\w-]{6,20}").matches(it) }
            return Regex("(?:v=|youtu\\.be/|embed/|shorts/)([\\w-]{6,20})").find(t)?.groupValues?.get(1)
        }

        fun start(context: Context, trailer: String) {
            context.startActivity(Intent(context, TrailerActivity::class.java).putExtra(EXTRA_TRAILER, trailer))
        }
    }
}
