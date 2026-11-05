# 0004 : le port du serveur Bedrock

Date : 5 novembre 2026. Statut : Proposée, à valider par Kr.

## Contexte

Le port Bedrock par défaut, 19132, est pris sur le VPS par le Geyser de SileneMC (ADR 0121 de
SileneMC, hors dépôt). Un client Bedrock qui se connecte à `play.silooo.com` sans préciser de port
vise 19132. Deux possibilités : un autre port, que les joueurs saisissent, ou une IP supplémentaire
chez OVH pour garder 19132. La version 26.60 a forcé SileneMC à passer en NetherNet, qui demande
une signalisation en plus du jeu en UDP ; ce que BDS exige exactement est à relever
([bedrock.md](../bedrock.md)).

## Décision provisoire

La recommandation du plan : le port 19142 pour la bêta, ouvert en UDP pour le jeu et en TCP pour la
signalisation, gratuit et sans attente ; puis une IP en plus avant l'ouverture publique si le port
gêne les joueurs console, pour revenir à 19132 sur cette IP. Le DNS `play.silooo.com` pointe vers
l'IP choisie. silooo-core n'est jamais exposé.

## Conséquences

- L'ouverture du port, l'IP et le DNS reviennent à Kr : ils sont dans « À faire par Kr » de
  [passation.md](../passation.md).
- Le port est une variable du compose de `deploy/`, pas une constante du code.
- La fiche Hubyy et le site affichent l'adresse avec le port tant qu'il n'est pas 19132.
- Si Kr choisit l'IP supplémentaire dès la bêta, cet ADR passe « Remplacée » par un nouveau.
- La mesure qui tranche avant l'ouverture : la part des joueurs console qui échouent à se connecter
  pendant la bêta.
