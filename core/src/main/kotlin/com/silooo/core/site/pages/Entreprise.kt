package com.silooo.core.site.pages

import com.silooo.core.site.frenchDate
import com.silooo.core.sitedata.CompanyCard
import com.silooo.core.sitedata.MemberRole
import java.time.ZoneOffset
import kotlinx.html.HTML
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.caption
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

private val MEMBER_HEADERS = listOf("Joueur", "Rôle")

/**
 * La fiche publique d'une entreprise : ce qu'un joueur voit déjà en jeu, et rien d'autre (nom, époque, étoiles, prestiges, date de
 * création, membres). Pas de Donar, pas de Lingots, pas de XUID : [CompanyCard] ne les porte pas. Le nom et les gamertags sont des
 * textes de joueurs ; le DSL les échappe.
 */
internal fun HTML.entreprise(shell: SiteShell, card: CompanyCard) =
    gabarit(shell, PageFrame(card.name, "Fiche de l'entreprise ${card.name} : époque, étoiles et membres.")) {
        lecture(card.name) {
            p { +"Époque ${card.era}" }
            p { +if (card.stars == 1) "1 étoile" else "${card.stars} étoiles" }
            p {
                +when (card.prestiges) {
                    0 -> "Aucun prestige"
                    1 -> "1 prestige"
                    else -> "${card.prestiges} prestiges"
                }
            }
            p { +"Créée le ${frenchDate(card.createdAt.atZone(ZoneOffset.UTC).toLocalDate())}" }
            h2 { +"Membres" }
            table(classes = "tableau") {
                caption { +"Les membres de ${card.name}" }
                thead { tr { for (header in MEMBER_HEADERS) th(scope = ThScope.col) { +header } } }
                tbody {
                    for (member in card.members) {
                        tr {
                            th(scope = ThScope.row) { attributes["data-label"] = MEMBER_HEADERS[0]; +member.gamertag }
                            td { attributes["data-label"] = MEMBER_HEADERS[1]; +if (member.role == MemberRole.PDG) "PDG" else "Associé" }
                        }
                    }
                }
            }
            p { a(href = "/classements/", classes = "sa-bouton sa-bouton--laiton") { +"Voir les classements" } }
        }
    }
