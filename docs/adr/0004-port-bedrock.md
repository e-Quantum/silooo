# 0004 : le port du serveur Bedrock

Date : 5 novembre 2026. Statut : Acceptée (Kr a choisi le port 19142 le 5 novembre 2026).

## Contexte

Le port Bedrock par défaut, 19132, est pris sur le VPS par le Geyser de SileneMC (ADR 0121 de
SileneMC, hors dépôt). Un client Bedrock qui se connecte à `play.silooo.com` sans préciser de port
vise 19132. Deux possibilités : un autre port, que les joueurs saisissent, ou une IP supplémentaire
chez OVH pour garder 19132. La version 26.60 a forcé SileneMC à passer en NetherNet, et BDS 26.60
n'accepte plus que lui : le port `server-port` devient un port TCP de signalisation, et le jeu passe
par une plage de ports UDP (`server-udp-ports`), un port par joueur connecté, à déclarer sous la
forme « IP publique:port » derrière Docker (relevé du 5 novembre 2026 dans
[bedrock.md](../bedrock.md), fait 5, sources tierces à confirmer sur le BDS de test).

## Décision provisoire

La recommandation du plan : le port 19142 pour la bêta, en TCP pour la signalisation NetherNet, plus
une plage UDP contiguë pour le jeu, dimensionnée sur `max-players` (par exemple 19142 à 19173 pour
32 joueurs ; la valeur exacte est une variable du compose de `deploy/`), gratuit et sans attente ;
puis une IP en plus avant l'ouverture publique si le port gêne les joueurs console, pour revenir à
19132 sur cette IP. Le DNS `play.silooo.com` pointe vers l'IP choisie. silooo-core n'est jamais exposé.

## Conséquences

- L'ouverture du port, l'IP et le DNS reviennent à Kr : ils sont dans « À faire par Kr » de
  [passation.md](../passation.md).
- Le port est une variable du compose de `deploy/`, pas une constante du code.
- La fiche Hubyy et le site affichent l'adresse avec le port tant qu'il n'est pas 19132.
- Si Kr choisit l'IP supplémentaire dès la bêta, cet ADR passe « Remplacée » par un nouveau.
- La mesure qui tranche avant l'ouverture : la part des joueurs console qui échouent à se connecter
  pendant la bêta.
