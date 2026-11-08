# Archives et fermeture des tickets

Lot C1b, 8 novembre 2026. Les nouveaux messages de fermeture restent **à valider par Kr**.

Le transcript conserve les textes bruts, auteurs, identifiants, dates, dates d'édition, cartes et références de
pièces jointes. Chaque page devient un fichier texte UTF-8 dans le journal privé du bot. Les médias restent sur
le CDN de Discord : leurs URL peuvent expirer, et leurs octets ne sont pas recopiés. Les anciens messages déjà
supprimés avant la lecture ne peuvent pas être retrouvés.

La migration V21 ajoute `discord_ticket_archives` et `discord_ticket_archive_parts`. L'ancien V20 reste inchangé.
Les règles sont dans `archivesTickets` de `definitions/discord.json` et contrôlées par son schéma et Kotlin.

## Parcours durable

Dès la première capture, l'écriture du ticket est bloquée sans retirer les droits de lecture. Ce blocage est
idempotent et ne touche que les droits d'écrire ou de créer des fils. Un verrouillage refusé interdit de poursuivre.
Les administrateurs restent soumis au fonctionnement natif des permissions Discord ; la dernière lecture détecte
les nouveaux messages avant la suppression. Un ticket en attente de reprise reste en lecture seule.


| État | Travail | Condition pour avancer |
| --- | --- | --- |
| `collecting` | Lecture de l'historique, une page à la fois, sauvegarde SQL du texte et du curseur | La dernière page est atteinte ; aucun texte n'est tronqué |
| `delivering` | Envoi de chaque fichier dans le journal privé | Identifiant de chaque message d'archive confirmé en base |
| `deleting` | Dernière lecture du salon, fermeture du ticket en SQL, suppression du salon | Les messages arrivés entre-temps sont archivés ; un refus conserve la reprise |
| `done` | Copie temporaire conservée selon la durée configurée | Échéance de rétention atteinte |
| `purging` | Texte SQL effacé, suppression des copies Discord, puis des reçus techniques | Tous les messages de cette archive ont été retirés ou confirmés absents |

Un bail et une révision protègent chaque changement SQL. Un ancien processus ne peut pas valider une page après
la reprise par un autre. Le bot traite un nombre borné de pages et de tickets par passage, puis rend la main à
ses autres fonctions. Le redémarrage et la minuterie reprennent les travaux incomplets, y compris un salon déjà
supprimé dont l'accusé de réception n'avait pas été enregistré.

Avant un envoi, le bot garde l'identifiant du dernier message du journal. Après une réponse perdue, il cherche
sa propre référence et le fichier attendu depuis ce point. Une recherche incomplète interdit de reposter et
conserve le salon source. La référence sert également de nonce stable ; la déduplication de Discord est limitée
aux dernières minutes, d'où cette recherche complémentaire. L'historique est lu avec des curseurs d'identifiants,
par pages de 100 au plus. [Documentation Discord des messages](https://docs.discord.com/developers/resources/message).

Un journal devenu public, un historique illisible, un curseur incohérent ou un fichier trop grand bloquent la
suppression. Une nouvelle tentative conserve la progression déjà enregistrée. Le membre ou le staff peut relancer
`/ticket action:fermer` ; `/ticket action:etat` indique la fermeture en cours et explique le blocage au staff.

La fermeture vérifie aussi les fils actifs et archivés rattachés au ticket. S'il en existe, ou si les droits ne
permettent pas de vérifier leur présence, le salon est conservé : ce lot n'archive pas les fils séparés.
Le bot a besoin de lire l'historique, gérer les permissions du ticket et gérer les fils pour cette vérification.

## Rétention et portée

La durée démarre à la fin de la fermeture. À l'échéance, le texte SQL est effacé avant la tentative de suppression
des copies Discord. Si Discord est indisponible, seuls les identifiants nécessaires à la purge restent en base ;
la suppression distante est réessayée. Le bot ne touche qu'aux messages d'archive dont il a conservé les reçus.
Les archives antérieures à V21, publiées comme simples lignes du journal, ne sont pas reprises par cette purge.

Le transcript est un instantané des lectures. Les types avancés tels que les résultats de sondages ou les messages
transférés ne font pas l'objet d'une sauvegarde spécialisée dans ce lot. La restauration des fichiers et la
réouverture d'un ticket fermé restent hors de ce parcours.

## Recette

Les tests couvrent les conversations de plus de 200 messages, l'Unicode, les références de fichiers et cartes,
les réponses perdues après capture et envoi, les refus de suppression, les nouveaux messages, les droits du
journal, les curseurs invalides et la purge. La vraie MariaDB d'essai vérifie les réservations concurrentes,
les baux expirés, les transitions et la reprise avec un autre store.

Le déploiement, la recette humaine sur Discord et les nouveaux textes restent distincts de ces tests.
