---
name: silooo-route
description: "Ajouter ou changer une route de silooo-core : contrat OpenAPI d'abord, puis test de contrat, code Kotlin et appel côté add-on, avec une migration si une table change. À utiliser pour toute route, toute erreur métier ou toute écriture sur une entreprise."
---

# Ajouter ou changer une route de silooo-core

Le contrat passe avant le code. L'ordre est imposé : OpenAPI, test de contrat, code du service, appel de l'add-on.

## Quand l'utiliser

- Une route s'ajoute, ou le chemin, les paramètres, la réponse ou les erreurs d'une route changent.
- Une opération de l'OpenAPI qui répond encore « pas fait » devient réelle.
- Une table s'ajoute ou change : la migration fait partie de la recette.
- `core/` et `addon/` arrivent en phases 1 et 2. Si un dossier manque, la recette s'arrête avant lui et le reste va dans « Ce qui reste » du compte rendu.

## À lire d'abord

- [`CLAUDE.md`](../../../CLAUDE.md) : les règles du code et les interdits.
- [`api/silooo-core.openapi.yaml`](../../../api/silooo-core.openapi.yaml) : l'en-tête (règles communes), puis `components` : `parameters`, `requestBodies`, `responses` (`CompanyUpdated`, `Refused`) et `schemas` (`ActionBase`, `Balances`, `CompanyState`).
- [`docs/socle.md`](../../../docs/socle.md) : règles 1 à 10, surtout idempotence, verrou optimiste, monnaies et XUID.
- `docs/architecture.md` et `docs/base-de-donnees.md` : qui appelle qui, tables, journal des monnaies.
- [`core/src/main/resources/db/migration/V1__init.sql`](../../../core/src/main/resources/db/migration/V1__init.sql) : le schéma de départ, à ne jamais modifier.
- [`docs/passation.md`](../../../docs/passation.md) : le prochain numéro de migration et d'ADR.

## Étapes

1. **OpenAPI d'abord.** Fichier touché : `api/silooo-core.openapi.yaml`.
   - Chemin sous `/v1/`, `summary` en français, `operationId` unique en anglais (`buyLevels`, `hire`). Le fichier reste de l'OpenAPI 3.0.3 valide.
   - Paramètres par référence à `#/components/parameters/` (`CompanyId`, `FactoryId`).
   - Toute action qui modifie quelque chose porte un `requestId` obligatoire : `ActionBase` (`requestId` et `actor`) dans le corps, ou `RequestId` et `ActorXuid` en paramètres quand il n'y a pas de corps. Une lecture n'en a pas.
   - Réponse d'une action sur une entreprise : `CompanyUpdated` (état et soldes). Refus métier : code 409 et `Refused`.
   - Une erreur nouvelle s'ajoute à l'énumération `reason` de `components/responses/Refused`, nommée comme les autres (`PAS_ASSEZ_DE_DONAR`, `CONFLIT_REESSAYER`). Aucune erreur hors de cette liste.
   - Types : Donar en `number` double, Lingots, Gemmes, jetons et clés en entiers 64 bits, XUID en chaîne (`Xuid`).
2. **Test de contrat.** Fichier touché : `core/src/test/`. Le test lit l'OpenAPI et vérifie la route : codes de réponse, schéma, chaque `reason` listé, `requestId` exigé, rejeu qui renvoie la même réponse. Une route pas encore codée a quand même son test. Lance-le : il doit échouer avant le code.
3. **Migration, seulement si une table change.** Fichier touché : un nouveau fichier `core/src/main/resources/db/migration/V<n>__<sujet>.sql`.
   - `<n>` est le numéro suivant lu dans `docs/passation.md`. Écris-le en tête de ton compte rendu.
   - Jamais de modification de `V1__init.sql` ni d'une migration déjà appliquée.
   - La migration reste compatible avec la version précédente du service : une migration ne se défait pas et le retour arrière doit rester possible.
   - Aucun chiffre du jeu dans le SQL : il vit dans `definitions/`.
4. **Code du service.** Fichier touché : `core/src/main/kotlin/`. Le service vérifie, calcule avec `definitions/`, écrit, puis renvoie le nouvel état.
   - Idempotence : la réponse d'un `requestId` est gardée dans `api_requests` ; rejouée, elle revient sans refaire l'action.
   - Écriture sur une entreprise : verrou optimiste sur `companies.version`. Si la version a changé, réponse `CONFLIT_REESSAYER` ; l'add-on rejoue avec l'état frais.
   - Monnaie personnelle (Lingots, Gemmes, jetons, clés) : chaque mouvement s'écrit dans `currency_ledger`, dans la même transaction que le solde.
   - Un refus métier renvoie son code `reason` et, pour une somme, `missing`.
5. **Appel côté add-on.** Fichier touché : `addon/src/api/`. Une fonction typée par opération. Chaque nouvelle intention du joueur reçoit un `requestId` neuf ; un rejeu après coupure garde le même. L'en-tête `X-Silooo-Key` vient des secrets de `@minecraft/server-admin`, jamais du code. L'add-on envoie une intention et affiche la réponse (`silooo-ecran`) ; il ne calcule rien. Les types tirés de `definitions/` sont générés : ne les édite pas.
6. **Mets la doc à jour.** Fichiers touchés : `docs/base-de-donnees.md` si une table change, `docs/architecture.md` si le flux change, un nouvel ADR dans `docs/adr/` (numéro suivant) si tu as tranché une décision.

## Ce qu'on ne fait pas

- Pas de code avant que l'OpenAPI et son test de contrat soient écrits.
- Pas de migration modifiée après coup, pas de `ALTER` dans `V1__init.sql`.
- Pas d'écriture sans `requestId`, pas d'écriture sur une entreprise sans verrou optimiste.
- Pas de code d'erreur absent de l'OpenAPI.
- Pas de solde, de prix ni de gain calculé dans `addon/` : le service décide, l'add-on affiche.
- Pas de chiffre du jeu dans le code : change `definitions/` (`silooo-equilibrage`).
- Pas de secret dans le dépôt ; ne lis ni ne modifie un fichier `.env`.
- Pas de route qui laisse un joueur fixer un prix à un autre ou échanger une monnaie : c'est interdit par `CLAUDE.md`.

## Vérifier

```
tools/verifier.sh                                                 # Git Bash sur le PC
cd core && ./gradlew test                                         # test de contrat et migrations
grep -o "operationId: .*" api/silooo-core.openapi.yaml | sort | uniq -d   # rien ne doit sortir
```

- `tools/verifier.sh` doit finir sans erreur avant de rendre la main.
- Un seul Gradle à la fois sur le VPS : construis sur le PC ou dans la CI.
- Rejoue la migration sur une base vide si tu en as écrit une.

## En fin de session

Le compte rendu va dans ton message final. Tu n'écris pas dans `docs/passation.md` : la direction y copie le journal.

- **Ce qui a changé** : la route et son `operationId`, les erreurs ajoutées, le numéro de migration pris, les fichiers touchés dans l'ordre.
- **Ce qui reste** : les étapes sans dossier (`core/`, `addon/`), les routes encore « pas fait ». Si le nombre d'opérations change, dis-le : le compte cité dans la doc est à corriger par la direction.
- **Comment vérifier** : les commandes ci-dessus.
- **À valider par Kr** : tout texte vu par les joueurs que la route fait naître (bulle par code `reason`), et toute décision qui touche l'argent réel ou une monnaie.
