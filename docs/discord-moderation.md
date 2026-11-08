# Modération du Discord

Les textes nouveaux restent **à valider par Kr**. Cette tranche C2a ajoute les sanctions de membres
au journal existant ; la purge de messages, le mode lent et les verrouillages restent en C2b.

## Commandes

| Commande | Cible | Permission native requise pour l'humain et le bot | Effet après confirmation |
| --- | --- | --- | --- |
| `/avertir` | Membre présent | Modérer les membres | Avertissement privé, échec si MP refusé |
| `/timeout` | Membre présent | Modérer les membres | Exclusion temporaire, durée choisie dans les définitions |
| `/retablir` | Membre présent | Modérer les membres | Retrait du timeout |
| `/expulser` | Membre présent | Expulser des membres | Départ forcé, nouvelle invitation nécessaire |
| `/bannir` | Membre présent | Bannir des membres | Interdit le retour, conserve tous les messages |
| `/debannir` | Identifiant d'un compte effectivement banni | Bannir des membres | Autorise le retour par invitation, sans réintégration automatique |
| `/dossier` | Mention ou identifiant, même après départ | Modérer les membres pour l'humain | Historique privé et paginé, aucune mutation |

Les rôles Staff ou Modération restent nécessaires, en plus des permissions natives. L'installation
ne leur accorde pas automatiquement le droit d'expulser ou de bannir. Un humain autorisé ne peut pas
utiliser les droits du bot pour dépasser sa propre hiérarchie. Les commandes disposent aussi des
permissions natives par défaut dans Discord ; le contrôle côté service s'applique même si leur
visibilité est modifiée manuellement. Un rôle ayant seulement le droit de modérer peut consulter
les dossiers, avertir et gérer les timeouts, mais ne peut pas expulser ni bannir.

## Confirmation et audit

Un motif est obligatoire. L'aperçu et les boutons sont privés. Seul l'auteur peut confirmer ; la durée
de validité vient de `definitions/discord.json`. L'annulation, l'expiration et la perte du rôle empêchent
l'exécution. Les comptes bots, le propriétaire et l'auteur lui-même sont protégés. La hiérarchie et les
permissions des deux acteurs sont relues au clic, puis juste avant la requête native. Le débannissement
vérifie l'absence du serveur et la présence dans les bannis ; il n'utilise pas une ancienne entrée du journal
comme preuve du bannissement actuel. La consultation d'un dossier après départ exige encore le rôle
et la permission de modération courants.

La revendication SQL précède l'appel Discord : un clic répété ou un redémarrage ne rejoue pas une
sanction déjà tentée. Un résultat réseau inconnu reste à vérifier par le staff ; le bot annonce cette
incertitude. Une panne de l'audit après succès ne provoque aucune seconde sanction. Le motif et
l'identifiant du modérateur figurent aussi dans la raison du journal natif Discord. Les dossiers sont
conservés selon les règles existantes et purgés automatiquement. V22 étend seulement la contrainte
des actions du journal V18 ; elle ne modifie aucun dossier.

En QG fermé, les sanctions sur les membres extérieurs au staff sont retenues. Un compte banni
absent ne porte plus de rôle : son débannissement est donc refusé dans ce mode. Aucun changement
du réglage d'ouverture n'est fait par ces commandes.

## Recette et limites

Les tests traversent le véritable adaptateur JDA avec requêtes interceptées : droits natifs distincts,
relecture finale, hiérarchie, membres protégés, compte absent et débannissement. Ils contrôlent aussi
le bannissement sans effacement des messages. Les tests du parcours couvrent aperçu, confirmation
par son auteur, annulation, expiration, panne réseau et panne d'audit. La MariaDB isolée vérifie V22,
les six types de dossier, la relecture et l'absence de deuxième tentative.

La recette humaine demeure ouverte : sur un compte de test accepté par Kr, confirmer une expulsion,
un bannissement puis un débannissement et relire le dossier après le départ. Le contrôle de mise en
service lit le catalogue Discord et les permissions ; il n'expulse ni ne bannit un vrai membre.

Références de l'adaptateur : [API Guild de Discord](https://docs.discord.com/developers/resources/guild)
et [JDA Guild](https://docs.jda.wiki/net/dv8tion/jda/api/entities/Guild.html).
