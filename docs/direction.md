# Direction : piloter le chantier en autonomie

Ce fichier s'adresse à la session de direction, lancée par Kr dans Claude Code sur son PC Windows.
Elle ne code pas tout elle-même : elle découpe le plan en tâches, les confie à d'autres sessions,
vérifie leur travail, fusionne, déploie et tient le journal.

## La boucle

1. `git fetch origin` puis lis `docs/passation.md` : les cases non cochées de la phase en cours sont la file
   d'attente. Ajoute-y les tâches manquantes en suivant `docs/plan-construction.md`.
2. Choisis jusqu'à **3 tâches indépendantes** (fichiers différents). Chacune doit tenir en moins d'une heure de
   travail et avoir un critère de fin vérifiable (« ./gradlew test passe », « le schéma valide les 3 JSON »).
3. Lance une session par tâche (section suivante), avec un prompt autonome : la tâche, les fichiers à lire,
   le critère de fin, la branche `claude/<sujet>` à pousser, et le rappel de lire `CLAUDE.md`.
4. Pendant qu'elles tournent, fais toi-même les petites tâches de coordination (docs, ADR, passation).
5. Quand une branche arrive : relis le diff, lance la vérification, puis fusionne sur `main` en avance rapide
   (rebase si besoin) et pousse. Coche la case, ajoute 3 lignes au journal.
6. Recommence. Toutes les 30 minutes environ, vérifie que `main` est vert et que les sessions lancées
   avancent ; relance une session bloquée avec un prompt plus précis, ou reprends la tâche toi-même.

## Où lancer une session

| Où | Commande | Pour quoi |
| --- | --- | --- |
| Cloud | `claude --cloud "<prompt>"` depuis le dossier du dépôt | Les tâches longues et indépendantes : code Kotlin, tests, schémas. La session pousse sa branche `claude/…` |
| Relancer un cloud | `claude -p "<message>" --cloud <session-id>` | Corriger ou relancer une session cloud qui s'est arrêtée |
| PC | `git worktree add ../silooo-wt/<sujet> -b claude/<sujet>` puis, dans ce dossier, `claude -p --permission-mode auto "<prompt>"` | Les tâches qui ont besoin de Docker, de Gradle ou des fichiers du PC |
| VPS | `ssh ubuntu@silenemc.eu "<commande>"` | Déployer le projet `silooo`, lire `free -m`, `df -h /`, `docker ps`, tests de fumée. Pas de session Claude sur le VPS |

Garde au plus 3 sessions en même temps : elles partagent le quota de Kr.

## Déployer (phase 2 et suivantes)

- Le dossier `/var/www/silooo` existe sur le VPS, avec un `.env` posé par Kr. Ne le lis pas, ne l'affiche pas.
- Le dépôt n'est pas encore cloné sur le VPS (le VPS n'a pas d'accès GitHub) : envoie les fichiers de
  déploiement par `scp` ou `rsync` depuis le PC, dans `/var/www/silooo`, sans écraser `.env`.
- Commandes autorisées : `sudo docker compose -p silooo …` sur le compose de Silooo uniquement.
  Jamais `docker stop`, `rm` ou `restart` sur un conteneur `bw-*` ou `huby-*`.
- La base `silooo` n'existe peut-être pas encore : sa création revient à Kr (mot de passe root de MariaDB).
  Si elle manque, ne déploie pas silooo-core et note-le dans « À faire par Kr ».
- Après un déploiement : test de fumée, puis `free -m`. Si la mémoire disponible passe sous 800 Mo,
  arrête les conteneurs `silooo` et note-le.

## Quand t'arrêter

- Tu enchaînes les phases du plan dans l'ordre, 0, 1, 2, 3, 4 puis 5, avec au plus 3 sessions à la fois
  (cloud et PC). Au début de chaque phase, tu écris sa liste de tâches cochables dans `docs/passation.md`
  à partir de `docs/plan-construction.md`. Une phase est finie quand toutes ses cases sont cochées et que
  sa condition de sortie est remplie.
- Si la condition de sortie demande Kr (base de données, port, DNS, test sur console, achat réel) : note-la
  dans « À faire par Kr », coche tout ce qui ne dépend pas de lui, et passe à la phase suivante pour tout
  ce qui avance sans lui (code, tests, pack de ressources, docs), sans rien déployer qui en dépende.
- Tu ne commences jamais la phase 6 (bêta avec des joueurs) ni la phase 7 (ouverture) : elles demandent Kr.
- Toutes les 2 heures environ, et avant de t'arrêter, écris un bilan dans `docs/passation.md` (ce qui est
  fait, ce qui reste, les décisions prises seul, la liste « À faire par Kr »), commite et pousse.
- Condition de fin : les phases 0 à 5 sont cochées dans `docs/passation.md` sur `main`, sauf les cases
  notées « À faire par Kr » ; le dernier bilan est poussé ; `main` est vert ; `git status` est propre ;
  aucune session lancée n'est encore en cours. Avant de t'arrêter, toutes les sessions lancées sont finies
  ou notées dans le journal.

## Ce que tu ne fais jamais

Les interdits de `CLAUDE.md` s'appliquent à toi et à chaque session que tu lances. En plus : ne lance pas
plus de 3 sessions, ne fusionne jamais une branche dont la vérification échoue, et n'invente pas de chiffre
de jeu hors de `definitions/`.
