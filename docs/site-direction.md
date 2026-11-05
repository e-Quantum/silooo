# Direction du site : piloter le chantier de silooo.com en autonomie

Ce fichier s'adresse à la session de direction du site, lancée par Kr. Elle est la sœur de la session de
direction du jeu ([direction.md](direction.md)) et suit les mêmes règles, mais son chantier est à part : le
site public `silooo.com`, décrit dans [site.md](site.md). Elle ne code pas tout elle-même : elle découpe le
chantier en étapes, les confie à une session de travail à la fois, vérifie, fusionne et tient son journal
dans [site-passation.md](site-passation.md).

## Ce qui est à elle, et ce qui ne l'est pas

- À elle (décision 37 de Kr, [passation.md](passation.md)) : le code du site, `core/src/main/kotlin/com/silooo/core/site/`
  et `core/src/main/resources/site/`, avec ses tests ; `docs/site.md`, `docs/site-passation.md`, ce fichier,
  l'ADR du site ([0006](adr/0006-site-silooo-com.md)) ; une fois, des lignes dans `docs/README.md` et
  `CLAUDE.md` ; et les quelques lignes que le site demande aux fichiers partagés de `core/` (`Application.kt`,
  `Config.kt`, `libs.versions.toml`, `core/README.md`) et à `tools/anti_ia.py`, en les gardant courtes pour que
  les tranches du jeu fusionnent sans conflit.
- Pas à elle : `docs/passation.md` et `docs/direction.md` restent à la direction du jeu. Elle n'y écrit
  jamais ; ce qu'elle a à dire au jeu passe par la section « Pour la direction du jeu » de
  `docs/site-passation.md`, que la direction du jeu lit à chaque bilan et à laquelle elle répond dans son
  journal. Ni `deploy/`, ni `api/`, ni `addon/` : une route du contrat ou un écran que le site rend nécessaire
  (le refus des non-admis dans `join`, un titre de joueur) se demande dans cette section, le jeu les fait.
- Elle ne déploie pas : la direction du jeu déploie silooo-core, site compris, quand ce fichier dit « Prêt à
  mettre en ligne » dans `docs/site-passation.md` et que Kr a fait sa part. Elle ne crée aucun compte externe, et
  ne passe jamais `SILOOO_SITE_MODE` à `ouvert` hors de son poste : ni dans un fichier du dépôt, ni dans la CI, ni
  dans un déploiement. L'ouverture du site est un geste de Kr (phase 7 du plan).
- Kr tient la direction du site dans le worktree `silooo-site` de son PC ; ses fichiers de cadrage, tant qu'ils ne
  sont pas poussés, ne sont lus par personne d'autre. Une direction du site lancée dans le cloud repart de ce
  qui est sur `main`, et demande à Kr de pousser le reste.

## Le premier tour : la mise en place

1. `git fetch origin`, lis `CLAUDE.md`, ce fichier, [site.md](site.md) et [site-passation.md](site-passation.md).
2. Si `site-passation.md` manque, écris-le : l'état, les étapes S0 à S5 avec leurs cases, « À faire par Kr »,
   « Décisions prises sans Kr », « À valider par Kr », le journal. Si `site.md` n'est pas sur `main`, reprends-le
   de la branche qui le porte et mets-le à jour.
3. Écris ou relis l'ADR du site. Un numéro d'ADR se prend dans l'index de `docs/adr/README.md` et s'annonce dans
   `site-passation.md` (la direction du site n'écrit pas dans `passation.md` : l'index de l'ADR, mis à jour dans
   le même commit, est le verrou).
4. Lance la vérification sur `main` pour connaître l'état de départ, puis commite la mise en place et pousse.

## La boucle

1. `git fetch origin` puis lis `site-passation.md` : la première étape dont une case n'est pas cochée est la
   suivante. Les étapes se font dans l'ordre, S0 puis S1 et ainsi de suite.
2. Lance **une seule session de travail** pour l'étape, avec un prompt autonome : la tâche case par case, les
   fichiers à lire, le critère de fin (« `tools/verifier.sh` vert, les deux modes du site testés »), la
   branche `claude/site-sN` à pousser, le rappel de lire `CLAUDE.md`, et les interdits de ce fichier. La
   session ne touche ni `docs/site-passation.md`, ni `docs/passation.md`, ni `CLAUDE.md` : elle rend compte
   dans son message final. Elle sait que des tranches du jeu travaillent dans `core/` en même temps : elle
   reste dans `site/` et ne réécrit pas un fichier partagé, elle y ajoute des lignes.
3. Pendant qu'elle tourne, fais toi-même les tâches de coordination : relire les textes, préparer la liste des
   « à valider par Kr », tenir le journal, préparer le prompt suivant.
4. Quand la branche arrive : relis le diff en entier, lance `tools/verifier.sh` dessus, puis fusionne sur `main`
   en avance rapide (rebase si `main` a bougé) et pousse. Coche les cases, ajoute trois lignes au journal.
   Une branche dont la vérification échoue ne se fusionne pas : relance la session avec le journal de l'échec,
   ou reprends la correction toi-même.
5. Recommence. Toutes les 2 heures environ, écris un bilan dans `site-passation.md`, commite et pousse.

## Où lancer une session de travail

Même tableau que [direction.md](direction.md), section « Où lancer une session ». Depuis une session cloud, une
session de travail est un sous-agent dans son propre worktree git, sur sa branche `claude/site-sN`
(`docs/passation.md`, décision 3). Une à la fois : le quota est celui de Kr, et le site n'a pas de tranches
indépendantes assez larges pour en occuper trois.

## Les règles propres au site

- Le site affiche, il ne décide rien. Les chiffres (prix des packs, grades, usines, nombre d'associés) viennent
  des définitions que le service a chargées, jamais recopiés dans un contenu. Les données vivantes (joueurs en
  ligne, classements, fiches) se lisent dans la base par les accès du service, avec un cache ; le site n'écrit
  que ce que l'[ADR 0006](adr/0006-site-silooo-com.md) lui permet (livraisons PayNow, inscriptions).
- Le site écoute sur son propre port (`SILOOO_SITE_PORT`), jamais sur celui de `/v1` : l'API reste hors
  d'Internet.
- Deux modes, lus dans `SILOOO_SITE_MODE` : `attente` (défaut partout : la page d'attente, les mentions,
  l'inscription des pionniers) et `ouvert` (le site entier). Le mode `ouvert` ne sert qu'à relire le site sur le
  poste de la direction ou dans les tests.
- Chaque texte est un texte vu par les joueurs : il passe `tools/anti_ia.py` (`site/` est dans sa liste) et reste
  marqué « à valider par Kr » dans `site-passation.md` tant que Kr ne l'a pas lu ([anti-ia.md](anti-ia.md)).
- Rien de ce que [vision.md](vision.md) interdit n'apparaît sur le site : ni hasard payant, ni monnaie
  échangeable, ni Lingots, zones ou places de classement à vendre. Pas de traceur, pas de cookie, pas d'hôte tiers.
- Le design est Silooo Atelier ([design/README.md](../design/README.md)) : mêmes jetons, mêmes textures,
  mêmes mots. Un écran qui ressemble à un tableau de bord SaaS n'est pas dans Silooo.
- Une page qui montre des données du jeu ne montre que ce qu'un joueur voit déjà en jeu : gamertag, nom
  d'entreprise, époque, étoiles, Lingots gagnés dans la semaine ; jamais de XUID, jamais de solde, jamais une
  adresse de courriel ni un identifiant Discord. Une table nouvelle passe par une migration, dont le numéro se
  prend à la fusion (décision 37, point 5).
- Les cases qui demandent Kr (DNS, bloc Caddy, identité légale des mentions, adresse de la boutique, sites de
  vote, application Discord, secret du webhook, ouverture) vont dans « À faire par Kr » de `site-passation.md`,
  et le chantier continue sans elles.

## Quand t'arrêter

- Tu enchaînes S0 à S5 dans l'ordre, une session à la fois. Une étape est finie quand toutes ses cases sont
  cochées sur `main`, sauf celles notées « À faire par Kr ».
- Toutes les 2 heures environ, et avant de t'arrêter, un bilan dans `site-passation.md` : ce qui est fait, ce
  qui reste, les décisions prises seule, la liste « À faire par Kr », les textes « à valider par Kr ».
- Condition de fin : S0 à S5 cochées dans `site-passation.md` sur `main`, sauf les cases de Kr ; le dernier
  bilan poussé ; `main` vert ; `git status` propre ; aucune session lancée encore en cours.
- « Prêt à mettre en ligne » s'écrit dans `site-passation.md` quand S0 à S5 sont sur `main`, que la liste de Kr
  pour la mise en ligne (DNS, bloc Caddy, mentions, mode) est écrite, et que `main` est vert : la direction du jeu
  déploie alors, pas la direction du site.

## Ce que tu ne fais jamais

Les interdits de `CLAUDE.md` et de [direction.md](direction.md) s'appliquent à toi et à chaque session que tu
lances. En plus : pas de déploiement ; pas d'écriture dans `docs/passation.md`, `docs/direction.md`, `deploy/`,
`api/` ni `addon/` ; aucun compte externe (registraire, hébergeur, Discord, PayNow, sites de vote, boutique) ;
jamais `SILOOO_SITE_MODE=ouvert` dans le dépôt, la CI ou un déploiement ; jamais plus d'une session de travail
à la fois ; jamais une fusion sans vérification verte ; jamais un chiffre du jeu écrit dans un contenu du site ;
jamais une donnée personnelle (courriel, identifiant Discord, XUID) affichée sur une page.
