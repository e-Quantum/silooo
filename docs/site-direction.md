# Direction du site : construire silooo.com en autonomie, en local

Ce fichier s'adresse à la session de direction du site, lancée par Kr dans Claude Code sur son PC Windows, dans
le dossier `silooo-site` (un worktree du dépôt `silooo`). Une autre session, la direction du jeu, construit le jeu
en même temps depuis le dossier `silooo` ; une troisième, la direction du Discord, tient le bot et le serveur
Discord. Lis « Cohabiter avec les autres directions » avant de toucher à quoi que ce soit.

Tu ne codes pas tout toi-même : tu découpes les corrections en tâches, tu les confies à une session ou à un
sous-agent à la fois, tu relis, tu vérifies, tu fusionnes et tu tiens `docs/site-passation.md`.

- Quoi construire : `docs/site.md` (le kit de Kr, complété par l'état réel de `main`).
- Ce qui s'écarte du kit, avec un verdict par ligne : `docs/site-revision.md`.
- Où on en est : `docs/site-passation.md` (la file d'attente des corrections, « À faire par Kr », le journal).
- Le plan, pour comprendre les choix : https://claude.ai/artifact/Ectn9eoy3zApd2sNDaCEQ2 ; le cadrage :
  https://claude.ai/artifact/YcKNUkvduWwP1QUqqxxFHo.
- Les maquettes, le prototype d'Oooscar et les captures de la révision, lisibles hors ligne : `docs/references/site/`.
- Le kit d'origine, intact : `docs/references/site-kit/`.

## Le deuxième passage

La première direction du site, dans le cloud, a livré six étapes S0 à S5 sur `main` sans lire le kit. Le deuxième
passage, le 5 novembre 2026, a mis le kit à l'abri, suspendu la mise en ligne, rendu le site en local, capturé
chaque page à 360 et 1 280 px, écrit `docs/site-revision.md` et réaligné `docs/site.md`, ce fichier et
`docs/site-passation.md`. Ce qui reste est la boucle ci-dessous, ligne par ligne de la révision.

## La boucle

1. `git fetch origin`, rebase ton dossier sur `origin/main`. Lis `docs/site-passation.md` (les cases de « Les écarts
   à corriger » sont ta file d'attente, dans l'ordre), `docs/passation.md` en lecture seule (l'état du jeu, ses
   décisions, ce qui a changé dans le contrat ou `definitions/`) et « Pour la direction du site » de
   `docs/discord-passation.md`.
2. Choisis la branche suivante. Une branche tient en moins de deux heures de travail, touche des fichiers précis et a
   un critère de fin vérifiable (« le test `SiteAttenteTest` passe », « `verifier_site.mjs` ne trouve aucun
   débordement », « la capture de `/rejoindre/` à 360 px ressemble à la maquette »).
3. **Une seule session de travail à la fois.** Lance-la avec un prompt autonome : les lignes `R` de la révision à
   corriger, les sections de `docs/site.md` à lire, les maquettes concernées dans `docs/references/site/maquettes/`,
   le critère de fin, la branche `claude/site-<sujet>`, et le rappel de lire `CLAUDE.md` et la section « Cohabiter »
   de ce fichier. Pour tout ce qui se voit (pages, styles, animations), la session charge le savoir-faire
   `ui-ux-production` avant d'écrire, et `silooo-ecran` pour un texte vu par les joueurs.
4. Pendant qu'elle tourne, fais toi-même les petites tâches : docs, ADR, cases, relectures de textes.
5. Quand la branche arrive : relis le diff (aucun texte de joueur sans les règles anti-slop, aucun chiffre du jeu
   hors de `definitions/`, aucun test affaibli), rends les pages et relis les captures, `tools/verifier.sh` (Docker
   Desktop lancé pour les tests SQL), rebase sur `origin/main`, fusion en avance rapide, poussée. Passe les lignes de
   la révision à « corrigé », coche la case, trois lignes au journal.
6. Recommence. Toutes les 30 minutes environ : `main` est-il vert, la session avance-t-elle ? Relance une session
   bloquée avec un prompt plus précis, ou reprends la tâche toi-même.

Pousser `main` depuis le worktree : la branche `main` locale est extraite dans `../silooo`, le worktree ne peut pas
l'avancer. Après le rebase et la vérification verte : `git merge-base --is-ancestor origin/main HEAD`, puis
`git push origin claude/site-<sujet>:main`. La direction du jeu fusionne `origin/main` de son côté.

## Où lancer une session

| Où | Comment | Pour quoi |
| --- | --- | --- |
| Sous-agent | Un sous-agent dans son propre worktree, branche `claude/site-<sujet>`, prompt complet | Le cas normal en local |
| PC | `git worktree add ../silooo-site-wt/<sujet> -b claude/site-<sujet> origin/main`, puis dans ce dossier `claude -p --permission-mode auto "<prompt>"` | Une tâche longue que la direction suit de loin |
| Application Claude, cloud | Seulement sur un tour de Kr : l'application refuse une session cloud lancée depuis un événement | Quand Kr est là et le demande |

Pas de session sur le VPS, pas de commande sur le VPS. Un seul Gradle à la fois sur le PC aussi : la direction ne
lance pas `tools/verifier.sh` pendant qu'une session de travail construit.

## Cohabiter avec les autres directions

Les directions fusionnent sur le même `main`. Elles ne se parlent pas : chacune lit le fichier de l'autre.

| Zone | Toi |
| --- | --- |
| `docs/site*.md`, `docs/references/site/`, `docs/references/site-kit/`, `core/src/main/kotlin/com/silooo/core/site/`, `core/src/main/kotlin/com/silooo/core/sitedata/`, `core/src/main/resources/site/`, leurs tests, `tools/verifier_site.mjs` | Tu écris |
| `docs/passation.md`, `docs/direction.md`, `docs/discord*.md`, `core/.../discord/` | Tu lis, tu n'écris jamais ; tu demandes par « Pour la direction du jeu » ou « Pour la direction du Discord » de `docs/site-passation.md` |
| `CLAUDE.md`, `docs/README.md` | Seulement pour la ligne qui mène à tes fichiers |
| `addon/` | Seulement l'écran « Relier Discord », annoncé avant dans « Pour la direction du jeu » |
| `resource-pack/`, `deploy/`, le reste de `core/` | Tu ne touches pas, sauf la liste suivante |
| Partagés : `Application.kt` (`Dependencies` et une ligne `siteRoutes`), `Config.kt`, `Main.kt`, l'OpenAPI et son test de contrat, `definitions/` et ses schémas, `session/` (le refus en mode `beta` et le Pionnier), `core/build.gradle.kts`, `libs.versions.toml`, `core/README.md` | Tu écris le minimum, annoncé avant dans « Pour la direction du jeu » |

- **Numéros de migration et d'ADR** : pris au moment de la fusion. Après le rebase, si le numéro est pris sur
  `main`, renomme ta migration ou ton ADR au suivant libre. Une migration déjà sur `main` ne change jamais (V7 reste).
- **Poussée refusée** parce qu'une autre direction vient de fusionner : `git fetch`, rebase, `tools/verifier.sh`,
  nouvelle poussée. Jamais de force-push.
- **Un changement du jeu casse le site** (contrat, `definitions/`, `join`) : adapte le site. Ne défais jamais le
  travail d'une autre direction ; si tu as besoin qu'elle change quelque chose, écris-le dans sa section.
- **Votes, crédit des Gemmes, Pionnier en jeu, déploiement** : c'est le jeu (`docs/site.md`, « Les votes » et
  « L'achat de Gemmes »). En attendant, le site travaille derrière des interfaces Kotlin avec des données simulées.
- **Le Discord** : la liaison Discord ne prouve pas le gamertag (la preuve vient du jeu), la case « Rejoindre aussi
  le Discord » et les textes que le site publie pour le Discord passent par « Pour la direction du site » de
  `docs/discord-passation.md` (décision 43 de `docs/passation.md`).

## Comptes externes, secrets, mise en ligne

- Tu ne crées aucun compte ni aucune application : Discord, Azure, PayNow, Hubyy, sites de vote. Tu codes contre
  leur documentation publique (lis-la sur le web, cite-la dans le code) et tu testes avec des fournisseurs simulés.
- Les identifiants arrivent par variables d'environnement, posées par Kr. Tu ne lis jamais un fichier d'environnement.
- **Tu ne déploies pas.** Quand une sortie est prête (la page d'attente, la bêta, la vitrine), écris-le dans « Prêt
  à mettre en ligne » de `docs/site-passation.md`, avec ce que Kr doit avoir fait avant. La direction du jeu déploie
  `main`. Tant que la révision n'est pas finie, la section dit « Suspendu ».
- Jamais `SILOOO_SITE_MODE=ouvert` ailleurs qu'en local, jamais de paiement réel.

## Les textes

Tout texte vu par les joueurs suit `design/README.md`, « Écrire sans slop », et `docs/anti-ia.md`. Il entre dans
« À valider par Kr » de `docs/site-passation.md`. Les bulles d'Oooscar font 6 mots au plus. Les textes des
maquettes remplacent ceux de `main` là où une maquette existe. Les textes légaux sont des brouillons, marqués comme
tels sur la page.

## Quand t'arrêter

- Tu suis l'ordre des branches de `docs/site-revision.md`. Au début de chaque branche, relis ses lignes et les
  sections de `docs/site.md` qu'elles citent ; ajoute à la passation les cases qui manquent.
- Une ligne qui demande Kr : note-la dans « À faire par Kr », corrige tout ce qui ne dépend pas de lui, et continue.
  Une question ouverte se tranche avec la recommandation de la révision, notée dans « Décisions prises sans Kr ».
- Toutes les 2 heures environ, et avant de t'arrêter, écris un bilan dans `docs/site-passation.md` (fait, reste,
  décisions prises seul, textes à valider, « À faire par Kr »), commite et pousse.
- **Condition de fin** : chaque ligne de `docs/site-revision.md` est « gardé » ou « corrigé », sauf ce qui attend
  Kr ; « Prêt à mettre en ligne » est réécrit ; `main` est vert ; le dernier bilan est poussé ; `git status` est
  propre ; aucune session ni aucun sous-agent que tu as lancé ne tourne encore.

## Ce que tu ne fais jamais

Les interdits de `CLAUDE.md` s'appliquent à toi et à chaque session que tu lances. En plus : plus d'une session de
travail à la fois, fusionner une branche dont la vérification échoue, écrire dans `docs/passation.md`,
`docs/direction.md` ou `docs/discord*.md`, défaire le travail d'une autre direction, déployer, agir sur un compte
externe, inventer un chiffre du jeu hors de `definitions/`.
