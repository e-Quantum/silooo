package com.silooo.core.wording

import java.text.Normalizer
import java.util.regex.Pattern

/** Un tic d'IA trouvé dans un texte : le nom de la règle (celui de tools/anti_ia.py) et la ligne qui le contient. */
data class Finding(val rule: String, val excerpt: String)

/**
 * Le garde anti-IA de tools/anti_ia.py, porté en Kotlin pour que le bot Discord relise un texte du staff avant de
 * l'afficher (design/README.md, « Écrire sans slop »). Mêmes listes, mêmes expressions, même pliage des accents et de la
 * casse, mêmes noms de règles : si une règle change dans le Python, elle change ici.
 *
 * Le paquet s'appelle `wording` et pas `discord` : le garde de fichiers lit les chaînes de `discord/` et de `site/`, et
 * refuserait les mots bannis écrits dans ce code.
 *
 * Deux écarts voulus avec le Python :
 *  - le texte est vu comme un fichier de texte ordinaire : les règles regardent le texte entier, mais la règle des
 *    majuscules juge chaque ligne (un titre « MAINTENANCE » seul sur sa ligne passe, comme dans un .txt ou un .md) ;
 *  - pas d'exception de boutique : un « Ultime » seul sur sa ligne est signalé, il ne passe qu'après « grade ».
 */
object AntiIa {
    // Mots bannis, sans accent et en minuscules : le texte est plié avant la recherche.
    private val MOTS_BANNIS = listOf(
        "epiques?",
        "incroyables?",
        "ultimes?",
        "legendaires?",
        "plong(?:e|es|ez|er|eons|ent)",
        "decouvr(?:e|es|ez|ons)",
        "aventures?",
        "univers",
        "experiences?",
        "immersi(?:f|fs|ve|ves)",
        "sans\\s+limites?",
        "a\\s+portee\\s+de\\s+main",
        "n'attend\\s+que\\s+toi",
        "le\\s+meilleur\\s+serveur",
    )

    // Ceux-là passent après « grade » : le début de chacun, cinq lettres (g[:5] du Python).
    private val DEBUTS_MOTS_DE_GRADE = listOf("ultim", "legen")

    // Sigles permis en majuscules (4 lettres et plus ; les plus courts ne sont jamais regardés).
    private val SIGLES = setOf(
        "HUD", "PDG", "VIP", "MVIP", "JSON", "UDP", "TCP", "DNS", "API",
        "HTTP", "HTTPS", "POST", "PATCH", "HEAD", "DELETE", "OPTIONS", "UUID", "XUID", "HTML",
        "ASCII", "NULL", "TRUE", "FALSE", "TODO", "FIXME", "NOTE", "BDS",
    )

    // La mention légale exigée par Mojang : masquée pour la seule règle des majuscules.
    private val MENTIONS_PERMISES = listOf(
        "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.",
    )

    private const val LIMITE = "(?<![a-z0-9_])"
    private const val FIN = "(?![a-z0-9_])"

    // Comme `re` en Python sur du texte : \s et \w couvrent Unicode (espace insécable comprise), ^ ne regarde que \n.
    private const val BASE = Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNIX_LINES
    private const val SANS_CASSE = BASE or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    private fun sansCasse(motif: String) = Pattern.compile(motif, SANS_CASSE).toRegex()

    private val RE_MOTS = MOTS_BANNIS.map { sansCasse(LIMITE + it + FIN) }
    private val RE_QUESTION_PUB = Pattern.compile(
        """(?:^[\s\-*•>#]*|(?<=[.!?:;»“(\[\"])\s*)(prete?s?\s+a)(?![a-z0-9_])""",
        SANS_CASSE or Pattern.MULTILINE,
    ).toRegex()
    private val RE_QUE_TU_SOIS = sansCasse(LIMITE + """que\s+(?:tu\s+sois|vous\s+soyez)""" + FIN)
    private val RE_SUSPENSION = Pattern.compile("""\.{3,}|…""", BASE).toRegex()
    private val RE_EXCLAMATIONS = Pattern.compile("!{2,}", BASE).toRegex()
    private val RE_TIRET = Pattern.compile("—", BASE).toRegex()
    private val RE_EMOJI = Pattern.compile(
        """[\x{1F000}-\x{1FAFF}☀-➿⭐⭕⬛⬜⌚⌛⏩-⏳⏸-⏺️‍]""",
        BASE,
    ).toRegex()
    private val RE_MAJUSCULES = Pattern.compile("""(?<![\w])[A-ZÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ]{4,}(?![\w])""", BASE).toRegex()
    private val RE_MINUSCULE = Regex("[a-zàâäçéèêëîïôöùûüÿœæ]")
    private val RE_APRES_GRADE = sansCasse(LIMITE + """grade\s+[«"“']?\s*\z""")

    /** Retire les accents et unifie les apostrophes, sans changer la longueur du texte (plier du Python). */
    internal fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '’' || c == '‘' -> out.append('\'')
                c.code < 128 || Character.isSurrogate(c) -> out.append(c)
                else -> out.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0])
            }
        }
        return out.toString()
    }

    private class Hit(val start: Int, val line: Int, val rule: String, val excerpt: String)

    /** Les tics d'IA du texte, dans l'ordre où ils apparaissent, sans doublon. Vide si le texte est propre. */
    fun check(text: String): List<Finding> {
        val hits = mutableListOf<Hit>()

        fun add(rule: String, start: Int, end: Int) {
            val line = text.take(start).count { it == '\n' }
            hits += Hit(start, line, rule, excerpt(text, start, end))
        }

        val folded = fold(text)

        for (regex in RE_MOTS) {
            for (m in regex.findAll(folded)) {
                val start = m.range.first
                val end = m.range.last + 1
                val word = m.value.lowercase()
                if (DEBUTS_MOTS_DE_GRADE.any { word.startsWith(it) } &&
                    RE_APRES_GRADE.containsMatchIn(folded.substring(0, start))
                ) {
                    continue
                }
                add("mot-banni (« ${text.substring(start, end)} »)", start, end)
            }
        }
        for (m in RE_QUESTION_PUB.findAll(folded)) {
            val g = m.groups[1]!!.range
            add("question-de-pub", g.first, g.last + 1)
        }
        for (m in RE_QUE_TU_SOIS.findAll(folded)) add("que-tu-sois", m.range.first, m.range.last + 1)
        for (m in RE_SUSPENSION.findAll(text)) add("points-de-suspension", m.range.first, m.range.last + 1)
        for (m in RE_EXCLAMATIONS.findAll(text)) add("exclamations-en-serie", m.range.first, m.range.last + 1)
        for (m in RE_EMOJI.findAll(text)) add("emoji", m.range.first, m.range.last + 1)
        for (m in RE_TIRET.findAll(text)) add("tiret-cadratin", m.range.first, m.range.last + 1)

        // Un titre ou une étiquette tout en capitales est voulu par le design system : on ne regarde les capitales
        // qu'au milieu d'une ligne qui a des minuscules. La mention de Mojang est masquée, positions inchangées.
        var masked = text
        for (mention in MENTIONS_PERMISES) masked = masked.replace(mention, " ".repeat(mention.length))
        for (m in RE_MAJUSCULES.findAll(masked)) {
            if (m.value in SIGLES) continue
            val start = m.range.first
            val end = m.range.last + 1
            if (RE_MINUSCULE.containsMatchIn(lineAround(text, start, end))) add("majuscules-d-insistance", start, end)
        }

        val seen = HashSet<Triple<Int, String, String>>()
        return hits.sortedBy { it.start }
            .filter { seen.add(Triple(it.line, it.rule, it.excerpt)) }
            .map { Finding(it.rule, it.excerpt) }
    }

    /** Une phrase pour le staff : les règles distinctes, dans l'ordre du texte. */
    fun summary(findings: List<Finding>): String {
        val rules = findings.map { it.rule }.distinct()
        return if (rules.isEmpty()) "Aucun tic d'IA." else "Tics d'IA : ${rules.joinToString(", ")}."
    }

    private fun lineStart(text: String, start: Int) = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1

    private fun lineAround(text: String, start: Int, end: Int): String {
        val to = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return text.substring(lineStart(text, start), to)
    }

    /** La ligne qui contient la trouvaille, rognée autour d'elle si elle est longue (_extrait du Python). */
    private fun excerpt(text: String, start: Int, end: Int): String {
        var line = lineAround(text, start, end)
        val from = start - lineStart(text, start)
        val to = from + (end - start)
        if (line.length > 100) {
            var a = maxOf(0, from - 35)
            var b = minOf(line.length, to + 35)
            if (a > 0 && Character.isLowSurrogate(line[a])) a--
            if (b < line.length && Character.isHighSurrogate(line[b - 1])) b++
            line = (if (a > 0) "…" else "") + line.substring(a, b) + (if (b < line.length) "…" else "")
        }
        return line.trim()
    }
}
