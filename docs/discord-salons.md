# Modération des salons — C2b

Les nouveaux textes et réglages sont **à valider par Kr**. La tranche ajoute quatre commandes
avec aperçu privé, motif et confirmation par leur auteur. Les résultats de validation et le
déploiement sont suivis dans [discord-passation.md](discord-passation.md).

## Périmètre

Les commandes agissent dans le salon courant, parmi les salons de texte publics autorisés par
`salonsModeration.salons` dans `definitions/discord.json` : discussion, medias et captures.
Le salon doit aussi être réellement public lors de chaque contrôle. Un forum, un fil, un ticket,
un bureau, un journal privé ou un panneau du bot ne peut pas devenir une cible en passant son identifiant.
Captures ne devient disponible qu'à la phase où le plan crée ce salon. En QG fermé, ces mutations
sur les salons publics sont refusées.

L'humain doit porter le rôle Staff ou Modération courant. Les droits natifs sont aussi vérifiés
pour l'humain et le bot :

| Commande | Droits natifs requis | Effet confirmé |
| --- | --- | --- |
| `/purger nombre motif` | Voir le salon, lire l'historique, gérer les messages | Supprimer uniquement des messages de la sélection initiale |
| `/mode-lent secondes motif` | Voir le salon, gérer les salons | Modifier le seul délai entre messages ; zéro désactive ce délai |
| `/verrouiller motif` | Voir le salon, gérer les salons et les permissions/rôles | Refuser l'écriture de @everyone et garder les exceptions du staff |
| `/deverrouiller motif` | Voir le salon, gérer les salons et les permissions/rôles | Restaurer les droits d'écriture enregistrés pour ce verrou |

Aucun droit natif supplémentaire n'est accordé automatiquement aux rôles humains.
Les permissions sont relues par REST : rôles, propriétaire, membres et exceptions du salon.
L'ordre des exceptions suit celui de Discord ; une permission présente sur le rôle ne suffit
pas si une exception effective la refuse dans le salon. Les commandes et boutons de modération
sont accusés réception avant leur mise en file, pour garder la confirmation utilisable pendant
une lecture réseau lente.

## Purge

La sélection porte sur les messages ordinaires et réponses parmi les derniers messages consultés,
dans la limite définie. Les épinglés, messages système et messages au-delà de l'ancienneté autorisée
sont exclus. La politique garde une marge sous la limite de deux semaines du bulk-delete Discord.
Seuls les identifiants sont conservés en SQL, jamais le contenu ou les pièces jointes.

La confirmation ne recalcule pas une nouvelle liste des derniers messages : elle relit une fenêtre
bornée par le plus grand identifiant sélectionné. Un message arrivé après l'aperçu ne prend jamais
la place d'un ancien. Un message devenu épinglé ou trop ancien est conservé. Le résultat indique
le nombre de messages ciblés par la requête confirmée ; Discord ne fournit pas le nombre exact
réellement supprimé si un autre modérateur a supprimé les mêmes messages entre les deux lectures.
Une requête groupée est utilisée pour plusieurs messages, une suppression simple pour un seul.
Il n'y a pas de repli automatique vers des suppressions individuelles en série.

## Verrouillage et restauration

Le verrou ne touche qu'à l'exception de @everyone : envoyer des messages, écrire dans les fils,
créer des fils publics et créer des fils privés. Les bits de visibilité et toutes les autres
permissions restent en place. Une exception de rôle ou de membre non staff permettant de contourner
ce verrou provoque un refus. Les administrateurs conservent les exceptions natives de Discord ;
les droits d'écriture du staff ne sont pas ajoutés automatiquement s'ils étaient absents.

V23 enregistre les permissions initiales **avant** l'appel externe. Un verrou complet posé
manuellement n'est pas repris par le bot. Le déverrouillage restitue les bits d'écriture initiaux,
y compris un refus manuel antérieur ; il ne promet donc pas d'ouvrir un salon qui était déjà restreint.
Les autres changements manuels survenus pendant le verrou sont conservés. Si l'exception n'existait
pas au départ et n'a reçu aucun autre réglage manuel depuis, le déverrouillage la supprime pour rendre
l'héritage initial. Une exception supprimée manuellement n'est pas recréée vide.

Chaque verrou porte un identifiant durable et une version : un vieux bouton ne peut pas restaurer
un verrou plus récent du même salon, même si les versions numériques repartent de un.
Un second verrou ne remplace jamais l'original en SQL. Les états de restauration durent jusqu'au
déverrouillage, indépendamment de la rétention des dossiers d'audit.

Discord n'offre pas de comparaison-et-écriture atomique de l'exception de permission. Le bot refuse
un écart détecté depuis l'aperçu et relit avant chaque mutation ; une édition manuelle strictement
concurrente avec la requête reste possible. Éviter de modifier cette exception pendant une confirmation.
Si les bits d'écriture changent manuellement vers un troisième état, le bot refuse de deviner quoi restaurer.

## Interruption et résultat incertain

Une réservation SQL passe en tentative avant l'appel Discord. Un double clic et un redémarrage ne
réémettent aucune mutation. Une erreur réseau ou un résultat inconnu ne devient pas un succès affiché.
Une panne de l'audit après l'action demande une vérification manuelle, sans seconde action.
Un refus prouvant qu'aucune mutation n'a eu lieu libère un verrou encore en préparation.

Après une interruption de verrouillage/déverrouillage, la période d'attente configurée doit expirer.
Le staff relance ensuite **explicitement** `/deverrouiller` : le bot relit l'état courant, propose un
nouvel aperçu puis restaure les bits connus, ou clôt l'état SQL si l'héritage était déjà revenu.
Il n'y a aucune remise en état automatique en arrière-plan. Les anciens propriétaires SQL ne peuvent
pas terminer l'état d'un nouveau propriétaire.

## Références

- [Suppression groupée de messages](https://docs.discord.com/developers/resources/message#bulk-delete-messages).
- [Permissions et ordre des exceptions](https://docs.discord.com/developers/topics/permissions).
- [Édition des permissions de salon](https://docs.discord.com/developers/resources/channel#edit-channel-permissions).
