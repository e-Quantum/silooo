# Oooscar : plan de développement complet

Révision du 8 novembre 2026, demandée par Kr. Ce document complète [discord.md](discord.md) et remplace
l'ordre des prochains lots de [discord-passation.md](discord-passation.md). Les choix du jeu et les règles de
Silooo restent dans leurs documents sources. Les messages nouveaux restent **à valider par Kr**.

## But et périmètre

Oooscar devient le bot de gestion, de support et de communauté de Silooo. Il conserve la liaison prouvée au jeu,
les entreprises et l'identité Atelier. Chaque fonction peut annoncer clairement son indisponibilité et rester
sans effet quand sa dépendance manque. Le jeu reste jouable quand le bot tombe ; aucune commande Discord ne
calcule de monnaie, n'encaisse une usine ou ne lance un prestige.

Le plan couvre les familles de fonctions utiles des bots de SileneMC, de Dyno et de Hurix. Il ne promet pas une
copie intégrale de leurs catalogues. Les lignes « prévu » ne sont pas livrées par cette révision.

## Sources vérifiées

- Sources SileneMC consultées en lecture seule sur le VPS, sur indication de Kr :
  `/var/www/minecraft/deploy/discord-john/`, `/var/www/minecraft/deploy/discord-voice/bot.py`,
  `/var/www/minecraft/deploy/discord-clans/bot.py` et `card.py`. Références de conception :
  `docs/adr/0057-bot-discord-clans.md` et le descriptif en tête de `discord-john/tickets.py`.
- [Dyno : commandes](https://docs.dyno.gg/en/commands),
  [AutoMod](https://docs.dyno.gg/modules/automod) et
  [journaux du panneau](https://docs.dyno.gg/en/dashboard/logs), consultés le 8 novembre 2026.
- [Hurix : site officiel](https://hurix.xyz/), consulté le 8 novembre 2026 par téléchargement HTTPS.
  La page annonce notamment tickets, AutoMod, niveaux, giveaways, configuration web, accueil, captcha,
  rôles, vocaux temporaires, suggestions, starboard, annonces, sondages et sauvegardes.

Les tableaux suivants sont des choix de produit pour **Silooo**, inspirés de ces sources. Les seuils, délais et
limites nouveaux devront vivre dans `definitions/discord.json` avec leur schéma. Aucune donnée ou clé de
production de SileneMC n'est copiée.

## Admission en bêta

La décision 21 du site s'applique : l'admission est automatique pour tous. Les cartes et notifications Discord
reflètent cet état ; elles ne constituent pas une approbation manuelle supplémentaire. La branche a intégré main
`bfd9f07`, qui unifie cette règle. La liaison prouvée reste nécessaire pour le profil et les rôles du jeu.

## Ce que l'on reprend de SileneMC

| Source existante | Adaptation pour Silooo | État dans cette reprise |
| --- | --- | --- |
| John : accueil, liaison, annonces et événements | Parcours console/téléphone/PC, annonces avec aperçu, rôles de notification choisis | Socle déjà présent ; audit du parcours à poursuivre |
| John : tickets et transcription | Sujets privés, formulaire par catégorie, prise en charge, suivi, archive | Prise en charge livrée dans cette tranche ; formulaires et archive intégrale prévus |
| John : modération et historique | Avertir, timeout, rétablir, dossier ; puis nettoyage, mode lent, verrouillage, expulsion et bannissement | Branche de modération intégrée ; gestes supplémentaires prévus |
| John : réponses et journaux | FAQ contrôlée, événements de serveur, journal des changements et des échecs | Prévu ; journal technique déjà présent |
| John : concours et soutiens | Giveaways gratuits, opt-in, rôle Soutien et récompenses décidées par le service | Socle présent ; livraison des lots en jeu à achever |
| Bot vocal : liaison et synchronisation | Liaison prouvée, rôles du jeu, surnom ; salons temporaires et bureaux d'entreprise | Socle présent ; recette réelle et outils du propriétaire à poursuivre |
| Gaby : cartes, membres, progression et récapitulatifs | Entreprise, associés, classement, objectifs collectifs, bilan hebdomadaire | Cartes et tableaux présents ; récapitulatif d'entreprise prévu |
| Gaby : annonces de progression | Prestiges, records, étapes d'entreprise et résultats | Partiellement présent ; reprise durable à vérifier |

## Catalogue cible et ordre de livraison

| Lot | Fonctions | Critères de réception |
| --- | --- | --- |
| C0 — intégrer l'existant | Réunir D2/D3 et la branche de gestion, conserver le podium de la salle d'attente, résoudre les migrations | Un seul catalogue de commandes, migrations sans doublon, vérification du dépôt verte |
| C1 — support | Tickets par sujet, formulaire, prise en charge/libération, statut, priorité, transfert, ajout/retrait d'un participant, motif de fermeture | Seuls les rôles autorisés voient et gèrent le ticket ; deux prises en charge concurrentes donnent un seul responsable |
| C1b — archives | Transcript intégral paginé, références des pièces jointes, état durable d'archivage, reprise d'une fermeture interrompue, purge de rétention | Aucun salon supprimé avant confirmation de l'archive ; panne, refus et redémarrage testés ; limite affichée si archive incomplète |
| C2 — modération | Dossiers, avertir, timeout, rétablir ; puis expulser, bannir/débannir, purger, mode lent, verrouiller/déverrouiller | Contrôle des droits de l'humain et du bot, hiérarchie, motif, aperçu des gestes destructifs, résultat auditable |
| C3 — protection | AutoMod natif, anti-flood, doublons, liens/invitations, mentions, capitales ; mode anti-raid et retour au calme | Règles configurées, exceptions explicites, mode observation, contrôle des faux positifs et des messages édités |
| C3b — journaux | Arrivées/départs, rôles, salons, sanctions, messages édités/supprimés, vocaux ; journal par destination | Éviter le contenu privé dans un salon public ; données minimales ; distinguer auteur certain et auteur inconnu |
| C4 — communauté | Rôles choisis, réponses FAQ, commandes informatives, sondages, suggestions et suivi, meilleurs moments, événements et annonces programmées | Aucun rôle sensible assignable ; réponses échappées, délai anti-spam, mentions limitées aux personnes consentantes |
| C4b — animation | XP et classement de semaine, giveaways, concours, soutiens ; annonces Twitch/YouTube ou autres réseaux choisis | Pas d'XP utile au jeu ; concours gratuits ; publication dédupliquée ; notification choisie |
| C5 — jeu | Finir D4 : relais, filtres et signalements ; bureaux, profils, objectifs et bilan d'entreprise ; finir D5 | Contrat avant les routes ; preuve XUID ; opt-in pour les rappels ; effets en jeu exécutés une seule fois par le service |
| C6 — administration | Diagnostic par module, activation/désactivation, permissions, métriques, réglages depuis l'admin du site | Réglages validés, journal des changements, séparation Staff/Modération/Aide, aucune clé exposée |
| C6b — sauvegarde | Export versionné des réglages et du plan des salons/rôles, aperçu des différences, restauration additive | Pas de suppression automatique ; ne promet pas de restaurer les messages Discord ni leurs identifiants |
| C7 — mise en service | Recette réelle membre/staff, refus de MP, incidents, mémoire et files ; guide de reprise | D6 clos uniquement après preuves ; phase Discord et ouverture du jeu restent deux réglages distincts |

### Détail du support

1. Une question de jeu part vers le forum d'aide ; achat, compte, sanction et créateurs restent privés.
2. Le formulaire demande uniquement les éléments nécessaires. Il ne demande jamais de mot de passe,
   de jeton ou de données de paiement complètes.
3. `Prendre en charge` réserve atomiquement le ticket. `Libérer le ticket` revient à son responsable.
   `/ticket action:etat|prendre|liberer|fermer` fonctionne aussi dans les tickets antérieurs à cette livraison.
4. Le rôle du staff est relu au moment du geste. Le numéro d'un ticket ne donne aucun droit sur un autre salon.
5. La priorité et le transfert sont journalisés ; leur prochaine tranche vérifiera également le destinataire.
6. Une clôture automatique ne vient qu'après état durable, avertissement au membre et réglage explicite.
   Elle reste désactivée dans cette livraison.

### Détail de la modération et de la protection

Conserver les fonctions natives de Discord quand elles conviennent. Les commandes de modération déjà développées
ne suffisent pas à remplacer tout Dyno : ne retirer ce dernier qu'après recette de chaque fonction utilisée.
Les verrouillages manuels existants doivent rester intacts au redémarrage et être restaurés exactement par
`déverrouiller` : conserver l'état précédent de l'overwrite, sans réécrire les autres permissions.

Les restrictions anti-raid seront temporaires, ciblées et visibles du staff, avec levée manuelle et expiration.
Un captcha reste une option de dernier recours, à tester sur mobile et pour l'accessibilité. Le premier lot
anti-raid ne bannit pas automatiquement des membres. Une indisponibilité du bot ne doit pas retirer les
protections natives déjà configurées.

### Détail de l'administration

Étendre l'administration existante de silooo-core, après ses contrats et son authentification. Aucun panneau
public supplémentaire n'est livré ici. Prévoir une vue des modules (actif, indisponible, raison), les permissions
manquantes, la santé de la file, les tâches différées, les archives bloquées, les MP refusés et les envois à reprendre.
Les réglages ont un schéma, une version, un auteur et une trace avant/après. Les droits sont revérifiés côté serveur,
pas seulement masqués dans l'interface. Une désactivation annule les minuteries et ne supprime aucune donnée.

## Options et limites retenues

| Fonction des références | Choix pour Silooo |
| --- | --- |
| Économie, paris, casino | Aucune seconde économie Discord ; aucun hasard payant |
| Récompenses par invitations | Pas de récompense ; une mesure agrégée de provenance pourra alimenter le rapport |
| Console et RCON de John | Hors périmètre ; les opérations restent dans l'administration prévue |
| Musique de Hurix | Option ultérieure, désactivée ; nécessite un processus audio séparé et une décision sur les sources |
| XP vocal, séries quotidiennes | À évaluer après les mesures du QG ; aucun gain en jeu ni obligation de présence |
| Anniversaires | Option non prioritaire, consentement explicite, sans collecter d'année de naissance |
| Clans de Gaby | Adapter aux entreprises ; aucune banque Discord, recrutement ou récompense parallèle |
| Commandes personnalisées | Templates d'information validés ; aucune exécution de code, de commande système ou de requête libre |

## Première tranche livrée par cette révision

- Intégration de la branche de gestion et signalement avec main, sans perdre le podium de la salle d'attente.
- Réservation des migrations V18 (modération), V19 (signalements), V20 (affectation des tickets).
  V17 de main, déjà occupée par la salle d'attente, reste inchangée.
- Prise en charge et libération persistantes, statut privé et commande `/ticket`, boutons sur les nouveaux tickets.
- Vérification des rôles courants et du salon source ; affectation concurrente testée sur SQL.
- Fermeture après réception de l'archive uniquement ; refus de lecture ou d'envoi = ticket ouvert et salon conservé.

## Tranche C1b du 8 novembre

Le [parcours des archives](discord-archives.md) remplace la limite de 200 messages par une lecture paginée.
Les textes, cartes et références de pièces jointes sont conservés en SQL avant leur envoi par fichiers dans le
journal privé. Une fermeture interrompue reprend depuis son état durable ; la purge retire le contenu SQL puis
les copies Discord après la durée configurée. Les médias ne sont pas téléchargés et leurs liens peuvent expirer.

## Recette et suite

Tests attendus : affectation SQL concurrente, relecture depuis un autre store, refus d'un tiers, retrait du rôle,
bouton envoyé dans un autre salon, archive absente/refusée, reprise après échec, migrations et catalogue JDA.
La vérification globale est `bash tools/verifier.sh`, sans affaiblir les tests existants.

Prochain lot recommandé : **C2**, pour reprendre les gestes de Dyno encore utilisés,
ainsi que les formulaires et le transfert qui restent ouverts dans C1. D1 conserve ses validations visuelles ;
D4/D5 restent à développer et D6 conserve sa recette réelle. Aucun module n'est déclaré opérationnel sur le serveur
sur la seule présence du code.
