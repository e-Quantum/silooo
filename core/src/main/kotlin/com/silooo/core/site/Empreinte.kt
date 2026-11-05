package com.silooo.core.site

/**
 * L'empreinte d'un gamertag, telle que l'add-on l'envoie dans le champ `xuid` du contrat (décision 25 de
 * `docs/passation.md`, `provisionalXuid` dans `addon/src/identity.ts`) : FNV-1a sur 64 bits des unités UTF-16 du gamertag
 * en minuscules et sans espaces autour, deux octets par unité, octet bas d'abord, écrite en décimal non signé. Même
 * gamertag, même empreinte : c'est par elle que la boutique, la liaison Discord et la connexion Xbox retrouvent un
 * joueur dans `players`. Le jour où l'add-on change de calcul, c'est ici, et seulement ici, que le site suit.
 */
object Empreinte {
    /** 14695981039346656037, le décalage de FNV-1a 64 bits, vu comme un `Long` signé. */
    private const val OFFSET_BASIS = -3750763034362895579L
    private const val PRIME = 1099511628211L

    /**
     * Ce que `String.prototype.trim` retire en JavaScript : les espaces Unicode et les fins de ligne. `kotlin.text.trim`
     * en oublie U+FEFF ; la liste est reprise telle quelle pour que les deux calculs ne divergent jamais.
     */
    private val JS_WHITESPACE = setOf(
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿',
    )

    /** L'empreinte d'un gamertag, en décimal (de 1 à 20 chiffres, le schéma `Xuid` du contrat). */
    fun duGamertag(gamertag: String): String {
        val normalized = gamertag.trim { it in JS_WHITESPACE }.lowercase()
        var hash = OFFSET_BASIS
        for (unit in normalized) {
            val code = unit.code
            for (byte in intArrayOf(code and 0xff, code ushr 8)) {
                hash = hash xor byte.toLong()
                // La multiplication d'un Long déborde modulo 2^64 : exactement le masque de l'add-on.
                hash *= PRIME
            }
        }
        return java.lang.Long.toUnsignedString(hash)
    }
}
