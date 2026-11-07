# 0008 : une boucle active à côté de la gestion, reprise des mécaniques de Money Maker

Date : 7 novembre 2026. Statut : Acceptée (Kr, 7 novembre 2026), chiffres et textes à valider par Kr.

## Contexte

Silooo a été cadré en regardant Money Maker, mode de jeu de GommeHD.net, lui-même tiré d'un jeu mobile
de mines. Les phases 3 à 5 en ont déjà la boucle de gestion : la production ne paie qu'à l'encaissement
au Silo, le personnel a des raretés, les incidents arrêtent une usine, l'absence est plafonnée, l'entreprise
se partage. Il manque sa moitié active : une grotte commune où l'on mine soi-même, plus rentable en
profondeur et risquée, des effets d'ouvriers à déclencher avec une recharge, des boosts trouvés à usage
unique et des tâches du jour. Le guide du mode (moneymaker.gg/guide, relevé le 7 novembre 2026) décrit
ces mécaniques sans chiffres.

## Décision

- **Deux boucles de poids égal** : la gestion des usines et le minage du Gisement comptent autant, pour les
  joueurs de sessions courtes comme pour ceux qui jouent longtemps. Une heure de Gisement vise à rapporter à
  peu près une heure de production.
- **Toute récompense de la boucle active se compte en heures de revenu de l'entreprise**, comme le coût
  d'un salarié dans `definitions/personnel.json` : elle garde son poids du départ aux étoiles.
- **Le Gisement est un seul lieu, commun à tout le serveur**, rejoint par la carte. Ce qu'un joueur y mine
  crédite son entreprise. Mourir y fait perdre une part du sac, jamais le solde de l'entreprise.
- **Une seule entreprise par joueur, pour toujours** : on ne reprend pas les profils multiples de Money Maker.
- **On reprend les mécaniques, jamais l'habillage** : aucun nom, texte, visuel ni carte de GommeHD.
- **Ordre** : d'abord les compétences du personnel, les boosts personnels et les quêtes quotidiennes, qui
  prolongent des paquets existants ; puis le Gisement sans danger ; puis ses étages dangereux et son
  classement. Le détail est dans [game-design.md](../game-design.md), « La boucle active ».
- Écarté : une grotte par entreprise (autant de mondes à charger, personne ne s'y croise) ; la perte d'une
  part du solde à la mort (un associé ferait perdre les autres).

## Conséquences

- Nouvelles définitions (`gisement.json`, des blocs dans `personnel.json`, `missions.json`, `boutique.json`),
  avec schémas, cas invalides et types générés ; nouvelles routes déclarées d'abord dans l'OpenAPI.
- Le service reste seul juge des gains du Gisement : il borne le débit de dépôt par joueur, l'add-on ne fait
  que rapporter les blocs cassés.
- Le Gisement est un monde à bâtir, par Kr ou par le constructeur de l'add-on.
- Le simulateur des treize cibles doit rester dans ses fourchettes, Gisement compris.
