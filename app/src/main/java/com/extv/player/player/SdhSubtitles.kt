package com.extv.player.player

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Sous-titres pour sourds et malentendants (option « Malentendants » des Paramètres).
 *
 * Code couleur de la charte française du sous-titrage SME :
 *  - blanc  : personne visible à l'écran qui parle ;
 *  - jaune  : personne qui parle hors champ ;
 *  - rouge  : bruits et indications sonores ;
 *  - magenta: musique et paroles de chansons ;
 *  - cyan   : narrateur, voix off, pensées (le « bleu » des sous-titres TV).
 *
 * Quand la piste porte déjà ses couleurs (SRT avec balises <font color>, ASS, DVB, télétexte),
 * on les garde telles quelles. Sinon [colorize] retrouve les couleurs d'après la mise en forme
 * habituelle des sous-titres SME : [bruits] ou (bruits) ou MAJUSCULES, ♪ pour la musique,
 * italique pour les voix hors champ, « NARRATEUR : » / « VOIX OFF : » pour le narrateur.
 */
object SdhSubtitles {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val YELLOW = 0xFFFFE500.toInt()
    const val RED = 0xFFFF4040.toInt()
    const val MAGENTA = 0xFFFF55FF.toInt()
    const val CYAN = 0xFF33E6FF.toInt()

    /** Tailles proposées dans les Paramètres (facteur appliqué à la taille normale). */
    val SIZES = listOf(1f to "Normale", 1.3f to "Grande", 1.6f to "Très grande")

    private val SDH_WORDS = Regex("\\b(sdh|sme|hi|cc|malentendants?|sourds?|hearing|closed captions?)\\b|malentend")
    private val FRENCH_WORDS = listOf("fr", "fre", "fra", "french", "français", "francais", "vf")

    /**
     * Note d'une piste de sous-titres pour le choix automatique (nom/libellé et langue tels que
     * décrits par le fichier). Négative = ne pas la choisir automatiquement.
     */
    fun score(name: String?, language: String?, forced: Boolean = false, describesSound: Boolean = false): Int {
        val n = " " + name.orEmpty().lowercase() + " "
        val lang = language.orEmpty().lowercase()
        val sdh = describesSound || SDH_WORDS.containsMatchIn(n)
        val french = lang in FRENCH_WORDS || lang.startsWith("fr") ||
            Regex("\\b(fr|fre|fra|french|français|francais|vf|vff|vfq)\\b").containsMatchIn(n)
        val forcedTrack = forced || n.contains("forc")
        var s = 0
        if (sdh) s += 100
        if (french) s += 50
        if (forcedTrack) s -= 80
        // Sous-titres d'une autre langue (anglais…) : jamais choisis d'office.
        if (!french && !sdh && (lang.isNotEmpty() && lang != "und")) s -= 200
        return s
    }

    private val SPEAKER = Regex("^\\s*-?\\s*([\\p{L}' ]{2,30})\\s*:\\s*")
    private val NARRATOR = Regex("narrat|voix off|voix-off|commentat|pensée|pensee", RegexOption.IGNORE_CASE)
    private val OFF = Regex("\\(off\\)|\\[off\\]|hors champ|hors-champ|au téléphone|au telephone", RegexOption.IGNORE_CASE)
    private val SOUND_FRAGMENT = Regex("\\[[^\\]]{2,}]|\\([^)]{2,}\\)|\\*[^*]{2,}\\*")

    /** Ajoute les couleurs SME à un texte de sous-titre qui n'en a pas. */
    fun colorize(text: CharSequence): CharSequence {
        if (text is Spanned && text.getSpans(0, text.length, ForegroundColorSpan::class.java).isNotEmpty()) {
            return text // couleurs d'origine : on respecte la piste
        }
        val out = SpannableStringBuilder(text)
        val italic = out.getSpans(0, out.length, StyleSpan::class.java)
            .filter { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }
        var start = 0
        while (start <= out.length) {
            var end = out.indexOf('\n', start)
            if (end < 0) end = out.length
            colorLine(out, start, end, italic)
            start = end + 1
        }
        return out
    }

    private fun colorLine(out: SpannableStringBuilder, start: Int, end: Int, italic: List<StyleSpan>) {
        if (end <= start) return
        val line = out.substring(start, end)
        val body = line.trim().trimStart('-', '–', ' ')
        if (body.isEmpty()) return
        fun paint(color: Int, s: Int = start, e: Int = end) =
            out.setSpan(ForegroundColorSpan(color), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Musique
        if (body.contains('♪') || body.contains('♫') || body.startsWith("#")) return paint(MAGENTA)

        // « NARRATEUR : … », « VOIX OFF : … », « (OFF) … »
        val speaker = SPEAKER.find(line)?.groupValues?.get(1)
        if (speaker != null && NARRATOR.containsMatchIn(speaker)) return paint(CYAN)
        if (OFF.containsMatchIn(line) || (speaker != null && OFF.containsMatchIn(speaker))) return paint(YELLOW)

        // Indication sonore sur toute la ligne : [Rires], (Sonnerie), ou tout en majuscules.
        val letters = body.filter { it.isLetter() }
        val wrapped = (body.startsWith("[") && body.endsWith("]")) || (body.startsWith("(") && body.endsWith(")")) ||
            (body.startsWith("*") && body.endsWith("*"))
        if (wrapped || (letters.length >= 4 && letters.none { it.isLowerCase() })) return paint(RED)

        // Voix hors champ : ligne en italique.
        val lineItalic = italic.any { out.getSpanStart(it) <= start + line.indexOf(body.first()) && out.getSpanEnd(it) >= end - (line.length - line.trimEnd().length) }
        if (lineItalic) paint(YELLOW)

        // Bruit au milieu d'une réplique : « Je viens [porte qui claque] tout de suite ».
        for (m in SOUND_FRAGMENT.findAll(line)) paint(RED, start + m.range.first, start + m.range.last + 1)
    }
}
