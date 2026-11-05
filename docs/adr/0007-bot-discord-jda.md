# 0007 : le bot Discord Oooscar vit dans silooo-core, sur JDA, derrière une interface

Date : 5 novembre 2026. Statut : Proposée, à valider par Kr.

## Contexte

Le cadrage du Discord ([discord.md](../discord.md)) veut un bot, Oooscar, qui lit tout dans silooo-core : le
plan du serveur appliqué au démarrage, l'accueil guidé, les composants récents de Discord (Components V2) pour
les cartes, l'AutoMod, les forums, les réponses visibles du seul joueur, un webhook pour le tchat relié, les
commandes installables sur le compte. La critique du plan impose que le bot ne fasse jamais tomber le jeu ni
le site : il ne démarre que sur demande, sa mémoire est mesurée, et au-delà d'un budget il devient un
processus à part. Deux bibliothèques Kotlin ou Java tiennent la route, relevées sur Maven Central le
5 novembre 2026 : Kord 0.18.1 (14 mars 2026 ; Kotlin et coroutines, client Ktor 3.4.0, sérialisation
kotlinx, l'accueil guidé couvert) et JDA 6.7.0 (20 septembre 2026 ; Java 8 et plus, OkHttp et Jackson,
Components V2 depuis la 6.0, une version par mois, et déjà prête pour le changement des salons des
interactions que Discord rend obligatoire le 16 novembre 2026).

## Décision

- **JDA 6.7.0**, version épinglée dans `libs.versions.toml`, sans le module audio (`opus-java` exclu : pas de
  voix, moins de mémoire). Ce qui a tranché : la cadence des versions et la prise en charge du changement du
  16 novembre 2026, au moment même où le bot entre en service. Écarté : Kord, plus proche de notre pile
  (coroutines, Ktor, kotlinx) mais sans version depuis mars, sur un Ktor plus ancien que le nôtre, et dont
  nul ne sait s'il suivra le 16 novembre. L'accueil guidé, que JDA ne couvre pas, s'écrit en un appel REST
  avec le client Ktor que le service a déjà.
- **Une interface, un faux.** Le code du bot (paquet `com.silooo.core.discord`) ne voit Discord qu'à
  travers une interface Kotlin (`DiscordServer`) ; `discord/jda/` l'implémente et c'est le seul paquet qui
  importe JDA. Les tests passent par un faux en mémoire, aucun test ne parle à Discord.
- **Le bot ne démarre que si `SILOOO_DISCORD_ACTIF` vaut `oui` ou `a-blanc`** (`non` par défaut), avec
  `SILOOO_DISCORD_TOKEN` et `SILOOO_DISCORD_SERVEUR` lus dans l'environnement du conteneur, jamais
  affichés. Il tourne dans son propre fil, après le site ; une panne du bot ou de Discord s'écrit dans les
  journaux et n'arrête rien d'autre. Les images sont produites par une file bornée.
- **Le plan du serveur** (`core/src/main/resources/discord/plan-serveur.json`, validé par son schéma dans
  `tools/verifier.sh`) est la seule description des salons et des rôles : le bot crée ce qui manque, ne
  supprime jamais rien, note l'écart dans `#📒・journal-du-bot`. En mode `a-blanc`, il écrit l'écart sans
  rien changer, hormis le salon du journal s'il manque.
- **Budget mémoire** : la JVM est mesurée avec et sans le bot, chiffres dans
  [discord-passation.md](../discord-passation.md). Si le bot dépasse 96 Mo de plus que le service seul sur
  le VPS, il devient un processus à part (nouvel ADR), sans changer son interface.

## Conséquences

- `core/` gagne JDA et ses dépendances (OkHttp, Jackson, Trove), trois variables et une quatrième,
  `SILOOO_DISCORD_PHASE` (`attente`, `beta`, `ouvert`), qui dit quels salons du plan existent.
- `tools/anti_ia.py` lit les textes du bot (le plan et les littéraux du paquet `discord/`) ; les noms de
  salons sont les seuls textes avec un emoji, par la clé `emoji` du plan, que le filtre saute.
- Kr : l'application Discord et son jeton, les intentions, l'invitation du bot ; le bot de test sur son PC.
