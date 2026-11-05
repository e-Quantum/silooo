# Direction du site : construire silooo.com en autonomie

Ce fichier s'adresse à la session de direction du site, lancée par Kr dans Claude Code sur son PC Windows, dans
le dossier `silooo-site` (un worktree du dépôt). Une autre session, la direction du jeu, construit le jeu en même
temps depuis le dossier `silooo`. Lis « Cohabiter avec la direction du jeu » avant de toucher à quoi que ce soit.

Tu ne codes pas tout toi-même : tu découpes les étapes du site en tâches, tu les confies à une session à la fois,
tu relis, tu vérifies, tu fusionnes et tu tiens `docs/site-passation.md`.

- Quoi construire : `docs/site.md`.
- Où on en est : `docs/site-passation.md` (étapes S0 à S5, leurs cases, « À faire par Kr », journal).
- Le plan, pour comprendre les choix : https://claude.ai/artifact/Ectn9eoy3zApd2sNDaCEQ2
- Les maquettes et le prototype d'Oooscar, lisibles hors ligne : `docs/references/site/`.

## Premier tour : la mise en place

1. Kr a posé dans ton dossier `docs/site.md`, `docs/site-direction.md`, `docs/site-passation.md` et
   `docs/references/site/`. Vérifie qu'ils y sont et que `git status` ne montre rien d'autre.
2. Sur une branche `claude/site-docs` : commite ces fichiers, puis ajoute
   - à `docs/README.md`, dans « Tâche par tâche » : « Construire le site » → `site.md`, et « Piloter le site » →
     `site-direction.md` ; dans « Les fichiers de `docs/` » : une ligne pour chacun des trois fichiers ;
   - à `CLAUDE.md`, section « Où lire » : « docs/site.md, docs/site-direction.md, docs/site-passation.md : le site
     silooo.com, construit par une deuxième direction. »
   Ce sont les deux seules fois où tu touches à ces fichiers de la direction du jeu.
3. `tools/verifier.sh`, rebase sur `origin/main`, fusion en avance rapide, poussée.
4. Première entrée du journal de `docs/site-passation.md`, puis la boucle.

## La boucle

1. `git fetch origin`, rebase ton dossier sur `origin/main`. Lis `docs/site-passation.md` (les cases de l'étape en
   cours sont ta file d'attente) et `docs/passation.md` en lecture seule (l'état du jeu, ses décisions, ce qui a
   changé dans le contrat ou `definitions/`).
2. Choisis la tâche suivante. Elle tient en moins d'une heure de travail, touche des fichiers précis, et a un critère
   de fin vérifiable (« le test `SiteAttenteTest` passe », « `verifier_site.mjs` ne trouve aucun débordement »).
3. **Une seule session de travail à la fois.** Lance-la avec un prompt autonome : la tâche, les sections de
   `docs/site.md` à lire, les maquettes concernées dans `docs/references/site/maquettes/`, le critère de fin, la
   branche `claude/site-<sujet>`, et le rappel de lire `CLAUDE.md` et la section « Cohabiter » de ce fichier.
4. Pendant qu'elle tourne, fais toi-même les petites tâches : docs, ADR, cases, relectures de textes.
5. Quand la branche arrive : relis le diff (aucun texte de joueur sans les règles anti-slop, aucun chiffre du jeu
   hors de `definitions/`, aucun test affaibli), `tools/verifier.sh`, rebase sur `origin/main`, fusion en avance
   rapide, poussée. Coche la case, trois lignes au journal.
6. Recommence. Toutes les 30 minutes environ : `main` est-il vert, la session avance-t-elle ? Relance une session
   bloquée avec un prompt plus précis, ou reprends la tâche toi-même.

## Où lancer une session

Comme la direction du jeu (décision 3 de `docs/passation.md`) :

| Où | Comment | Pour quoi |
| --- | --- | --- |
| Application Claude | Session en worktree local ou dans le cloud, sur la branche `claude/site-<sujet>` | Le cas normal |
| PC | `git worktree add ../silooo-site-wt/<sujet> -b claude/site-<sujet> origin/main`, puis dans ce dossier `claude -p --permission-mode auto "<prompt>"` | Les tâches qui ont besoin de Gradle, de Node ou de Chromium sur le PC |
| Sous-agent | Un sous-agent dans son propre worktree, branche `claude/site-<sujet>` | Quand aucune session ne peut être lancée sans Kr |

Pas de session sur le VPS, pas de commande sur le VPS.

## Cohabiter avec la direction du jeu

Les deux directions fusionnent sur le même `main`. Elles ne se parlent pas : chacune lit le fichier de l'autre.

| Zone | Toi |
| --- | --- |
| `docs/site*.md`, `docs/references/site/`, `core/src/main/kotlin/com/silooo/core/site/`, `core/src/main/resources/site/`, leurs tests, `tools/verifier_site.mjs` | Tu écris |
| `docs/passation.md`, `docs/direction.md` | Tu lis, tu n'écris jamais |
| `CLAUDE.md`, `docs/README.md` | Seulement au premier tour |
| `addon/` | Seulement l'écran « Relier Discord » (S3), annoncé avant dans « Pour la direction du jeu » |
| `resource-pack/`, `deploy/`, le reste de `core/` | Tu ne touches pas, sauf la liste suivante |
| Partagés : `Application.kt` (une ligne `siteRoutes`), l'OpenAPI et son test de contrat, `definitions/` et ses schémas, `session/` (le refus en mode `beta` et le Pionnier), `core/build.gradle.kts`, `libs.versions.toml`, `tools/verifier.sh`, `.github/workflows/ci.yml` | Petits commits, rebase juste avant la fusion, une ligne au journal pour chacun |

- **Numéros de migration et d'ADR** : pris au moment de la fusion. Après le rebase, si le numéro est pris sur
  `main`, renomme ta migration ou ton ADR au suivant libre. Une migration déjà sur `main` ne change jamais.
- **Poussée refusée** parce que l'autre direction vient de fusionner : `git fetch`, rebase, `tools/verifier.sh`,
  nouvelle poussée. Jamais de force-push.
- **Un changement du jeu casse le site** (contrat, `definitions/`, `join`) : adapte le site. Ne défais jamais le
  travail de l'autre direction ; si tu as besoin qu'elle change quelque chose, écris-le dans « Pour la direction
  du jeu ».
- **Votes, crédit des Gemmes, Pionnier en jeu, déploiement** : c'est le jeu (`docs/site.md`, « Les votes » et
  « L'achat de Gemmes »). En attendant, le site travaille derrière des interfaces Kotlin avec des données simulées.

## Comptes externes, secrets, mise en ligne

- Tu ne crées aucun compte ni aucune application : Discord, Azure, PayNow, Hubyy, sites de vote. Tu codes contre
  leur documentation publique (lis-la sur le web, cite-la dans le code) et tu testes avec des fournisseurs simulés.
- Les identifiants arrivent par variables d'environnement, posées par Kr. Tu ne lis jamais un fichier `.env`.
- **Tu ne déploies pas.** Quand une sortie est prête (S1 pour la page d'attente, S2 à S4 pour la bêta, S5 pour la
  vitrine), écris-le dans « Prêt à mettre en ligne » de `docs/site-passation.md`, avec ce que Kr doit avoir fait
  avant. La direction du jeu déploie `main`.
- Jamais `SILOOO_SITE_MODE=ouvert` ailleurs qu'en local, jamais de paiement réel.

## Les textes

Tout texte vu par les joueurs suit `design/README.md`, « Écrire sans slop », et `docs/anti-ia.md`. Il entre dans
« Textes à valider par Kr » de `docs/site-passation.md`. Les bulles d'Oooscar font 6 mots au plus. Les textes
légaux sont des brouillons, marqués comme tels sur la page.

## Quand t'arrêter

- Tu enchaînes S0, S1, S2, S3, S4 puis S5. S2, S3 et S4 peuvent se chevaucher quand leurs fichiers diffèrent. Au
  début de chaque étape, relis ses cases dans `docs/site-passation.md` et ajoute celles qui manquent.
- Une case qui demande Kr : note-la dans « À faire par Kr », coche tout ce qui ne dépend pas de lui, et continue.
- Toutes les 2 heures environ, et avant de t'arrêter, écris un bilan dans `docs/site-passation.md` (fait, reste,
  décisions prises seul, textes à valider, « À faire par Kr »), commite et pousse.
- **Condition de fin** : S0 à S5 cochées dans `docs/site-passation.md` sur `main`, sauf les cases de Kr ; le dernier
  bilan est poussé ; `main` est vert ; `git status` est propre ; aucune session que tu as lancée ne tourne encore.

## Ce que tu ne fais jamais

Les interdits de `CLAUDE.md` s'appliquent à toi et à chaque session que tu lances. En plus : plus d'une session de
travail à la fois, fusionner une branche dont la vérification échoue, écrire dans `docs/passation.md` ou
`docs/direction.md`, défaire le travail de la direction du jeu, déployer, inventer un chiffre du jeu hors de
`definitions/`.
