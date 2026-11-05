# Économie : formules, paliers, prestige, notation

Les chiffres du jeu vivent dans [definitions/economie.json](../definitions/economie.json),
[definitions/usines.json](../definitions/usines.json),
[definitions/boutique.json](../definitions/boutique.json) et [definitions/monde.json](../definitions/monde.json)
(où se posent les îles). Le tableur
[silooo-equilibrage-v2.xlsx](references/silooo-equilibrage-v2.xlsx) simule un joueur type et
vérifie treize cibles de rythme. Ce fichier explique les mécanismes et renvoie aux clés ; il ne
recopie aucune valeur. Pour changer un chiffre, suis la recette `silooo-equilibrage` et ajoute une
ligne à l'historique en bas de ce fichier.

## Les trois couches

| Couche | Monnaie | Évolution | Sert à | Remise à zéro |
| --- | --- | --- | --- | --- |
| Course | Donar | Exponentielle | Usines, salariés, améliorations, logistique | À chaque prestige |
| Patrimoine | Lingots | Lente, jamais d'explosion | Zones et bâtiments de la ville, marché, boutique de prestige | Jamais |
| Boutique | Gemmes | Achetée en euros | Boutique en jeu | Jamais |

L'inflation est voulue dans la première couche et impossible dans la seconde. Le Donar est un
`DOUBLE`, les Lingots et les Gemmes des `BIGINT` : voir [base-de-donnees.md](base-de-donnees.md).

## Le prix des niveaux

- Chaque niveau d'une usine coûte un multiple du précédent : clé `niveaux.croissancePrix`.
- Le prix du niveau 1 de chaque usine : clé `prixNiveau1` de l'usine dans `usines.json`.
- Le prix pour monter de `k` niveaux d'un coup est une somme géométrique, écrite dans la clé
  `niveaux.formulePrix` :

  ```
  prix(n -> n+k) = prixNiveau1 * croissance^n * (croissance^k - 1) / (croissance - 1)
  ```

- L'opération `buyLevels` accepte `1`, `10`, `palier` (jusqu'au prochain ×2) et `max` (tout ce que
  le solde permet). C'est le service qui calcule, jamais l'add-on. `1`, `10` et `palier` se paient en entier
  ou pas du tout (409 `PAS_ASSEZ_DE_DONAR`, avec la somme qui manque). `max` achète tout ce que le solde paie
  et ne refuse que s'il ne paie pas même un niveau, auquel cas il manque le prix du prochain niveau moins le
  solde. **À valider par Kr.**
- La croissance du prix est le réglage le plus sensible du jeu : une variation au centième rallonge
  le premier prestige de plusieurs heures.

## La production et les paliers

- La production d'un niveau, salaires déduits et avant multiplicateurs : clé `productionParNiveau`
  de chaque usine, dans l'unité donnée par la clé `unites` de `usines.json`.
- La production se calcule au temps écoulé, jamais bloc par bloc.
- Paliers ×2 : la production d'une usine double à certains niveaux, fixés par la clé
  `niveaux.paliersX2` (des paliers fixes, puis un pas régulier). Le champ `Factory.nextMilestone`
  donne le prochain, et la jauge à lampes du HUD compte les niveaux qui restent.
- Le multiplicateur global `CompanyState.multiplier` regroupe plusieurs bonus : Lingots détenus,
  grade, Pilier, étoiles, bâtiments de la ville, boosts. La façon de les combiner est celle de la
  feuille « Courses » du tableur, colonne « Multiplicateur global » : à reprendre telle quelle. Les bonus de grade et de Pilier
  sont dans `boutique.json`, clés `grades[].bonusDonarPourcent` et `abonnements[].bonusDonarPourcent`.
- Les salaires sont la sortie d'argent principale. Hors ligne, ils se paient sur la production de la
  période et ne font jamais passer le compte en négatif.

## Hors ligne

- La production continue pendant l'absence, jusqu'à un plafond en heures : clé
  `horsLigne.plafondHeures` au départ, puis la colonne `companies.offline_cap_hours` quand les
  améliorations du chef d'équipe et du Silo l'ont étendu. La création de l'entreprise recopie la clé dans la
  colonne ; à partir de là, la colonne fait foi et le moteur lui applique sa formule.
- Elle est comptée à efficacité réduite : clé `horsLigne.efficacite`.
- L'absence se compte depuis `companies.last_collect_at`, le dernier calcul de production de l'entreprise, et non
  depuis le départ du joueur (`players.last_seen_at`) : la production appartient à l'entreprise, et deux associés
  qui se relaient ne doivent pas se faire payer la même heure deux fois. Le revenu est celui d'aujourd'hui,
  multiplicateur de maintenant compris. Le surplus au-delà du plafond est perdu.
- **Deux régimes, selon qu'un membre de l'entreprise était connecté.** La production écoulée depuis
  `last_collect_at` se verse ainsi, puis `last_collect_at` repart de maintenant dans la même écriture :
  - **En ligne**, au moins un membre connecté pendant la période : le revenu plein, `revenu par heure × heures`,
    sans plafond ni efficacité. C'est ce que le compteur de l'add-on a fait défiler (règle 2 de
    [socle.md](socle.md)).
  - **Hors ligne**, personne de connecté : la formule de cette section, heures plafonnées par la colonne de
    l'entreprise et efficacité `horsLigne.efficacite`.

  **À valider par Kr.**
- Pour quatre opérations, le régime se connaît par construction. `collect`, `buyLevels` et `restoreStep` sont
  demandées par un membre connecté : en ligne. `leave` verse la production en ligne (le partant était connecté
  jusque-là) et remet `last_collect_at` à maintenant : la période hors ligne commence au départ du dernier membre.
  Seul `join` choisit : en ligne si un AUTRE membre de l'entreprise est connecté à cet instant, hors ligne sinon.
- La présence que `join` interroge est `ServerPresence`, que `heartbeat` remplit (l'add-on bat toutes les
  10 secondes, avec les XUID des joueurs en ligne). Un signal de plus de 30 secondes est périmé : un serveur qui
  ne bat plus n'a plus de joueurs en ligne. Celui qui arrive ne compte pas pour lui-même. `players.last_seen_at` ne
  sert pas : il est figé au départ et remis au retour, il ne dit pas qui est connecté maintenant. La présence est en
  mémoire : si silooo-core redémarre, un `join` qui tombe avant le prochain signal de vie (10 secondes) voit une
  période hors ligne, ce qui paie moins, jamais plus.
- `join` verse selon ce régime, puis remet `last_collect_at` à maintenant même quand le gain est nul (plafond nul,
  aucune usine qui produit) : sans cela, un `collect` ultérieur compterait ces heures au revenu plein. Cela coûte
  une écriture, donc un coup de version, de plus ; elle n'a lieu que si du temps s'est écoulé.
- `collect`, `buyLevels` et `restoreStep` versent la production en attente, puis remettent `last_collect_at` à
  maintenant dans la même écriture que le reste de l'action. `buyLevels` paie donc l'achat avec ce solde, et
  l'usine ne produit à son nouveau niveau que depuis maintenant. Un `collect` sans temps écoulé ne change rien.
- `collect` renvoie `offlineHours`, nom du contrat pour les heures comptées : toutes les heures écoulées (il est
  toujours en ligne), sans plafond. À l'arrivée, `JoinResponse.offlineCollected` donne ce qui a été encaissé au
  régime hors ligne ; la production versée au régime en ligne va au Donar de l'entreprise (`state.donar`) et n'y
  figure pas.
- Sans chef d'équipe, une usine s'arrête quand son stock est plein. Jamais de grève ni d'incident
  pendant une absence.

## La remise en état

Les quatre étapes sont celles de [game-design.md](game-design.md) (dialogue, tâches, livraison, recrutement) ;
l'opération `restoreStep` en avance une à la fois. Rien dans `definitions/` ne chiffre cette remise en état, donc
le service tranche ainsi, **à valider par Kr** :

- Les étapes se font dans l'ordre, une fois chacune. `EN_RUINE` n'accepte que l'étape 1 ; `REMISE_EN_ETAT` à
  l'étape *s* n'accepte que l'étape *s* + 1. Une étape trop loin donne `USINE_VERROUILLEE`, une étape déjà faite
  ou une usine déjà `ACTIVE` donne `DEJA_POSSEDE`, et rien n'est écrit.
- Aucune étape ne coûte de Donar : ni `usines.json` ni `economie.json` n'a de prix de remise en état. Le jour où
  il y en aura un, il va dans `definitions/` et le service le lit ; le chiffre n'entre jamais dans le code.
- La dernière étape passe l'usine `ACTIVE` au niveau le plus haut entre son niveau (0 en ruine) et
  `prestige.departDeCourse.niveau`, le niveau où la Carrière démarre chaque course. Une usine remise en état
  produit donc dès l'étape 4.
- Chaque étape verse d'abord la production en attente, au régime en ligne (voir « Hors ligne ») : l'usine remise
  en état ne produit que depuis maintenant.

## Le prestige et les Lingots

- Le prestige est permis quand les Donar gagnés dans la course atteignent le seuil de la course :
  clé `prestige.seuils`, une entrée par course, et `CompanyState.threshold` côté API. Au-delà des
  seuils listés, chaque seuil vaut le précédent multiplié par la clé `prestige.facteurSeuilSuivant`
  (`prestige.etoilesSuivantes` en garde le texte).
- Les Lingots gagnés suivent la clé `prestige.conversionLingots` :

  ```
  lingots = coefficient * cbrt(donarGagnes / (seuil / diviseurReference))
  ```

  La racine cubique donne un rendement décroissant : attendre bien au-delà du seuil rapporte peu de
  plus. Comme la référence suit le seuil, les Lingots restent petits et réguliers d'une course à
  l'autre, même quand le Donar change de lettre. `PrestigePreview.lingotsIfDoubled` sert à montrer
  ce rendement au joueur.
- Chaque Lingot détenu ajoute un bonus à toute la production : clé `prestige.bonusParLingotDetenu`.
  Pour une entreprise, le bonus se calcule sur la moyenne des Lingots des membres (choix du socle, à
  confirmer, voir [socle.md](socle.md)). Dépenser ses Lingots en ville réduit donc ce bonus : c'est
  le vrai choix du joueur.
- Les Lingots sont personnels. Au prestige, le total se partage selon `company_members.share_percent`.
- Après la dernière époque, chaque prestige donne une étoile, qui multiplie toute la production :
  clé `prestige.productionParEtoile`.
- Lingots hors prestige : la mission de bienvenue de la mairie et les missions, commandes et votes,
  clé `lingotsHorsPrestige`.
- Chaque prestige s'enregistre dans `prestige_history` avec sa durée : c'est la matière du tableau
  de bord d'équilibrage.

### Ce que fait l'opération `prestige`

Ni `economie.json` ni le contrat ne disent tout ce qu'un prestige remet à zéro. Le service tranche ainsi, **à valider
par Kr** (`prestigePreview` montre le résultat sans rien écrire, `prestige` l'écrit) :

- Seul le PDG lance le prestige (`PAS_LE_PDG` pour un associé). Il est refusé sous le seuil (`SEUIL_NON_ATTEINT`, avec
  les Donar qui manquent).
- La production en attente est versée d'abord, au régime en ligne (voir « Hors ligne ») : elle compte pour le test du
  seuil et pour les Lingots, comme si le joueur avait encaissé juste avant.
- La course suivante commence : Donar et Donar gagnés à zéro, `course_number` + 1, l'époque et les étoiles données par
  le moteur pour cette course, `course_started_at` et `last_collect_at` à maintenant.
- Les usines repartent. La Carrière (`prestige.departDeCourse`) est `ACTIVE` à son niveau de départ. Les autres usines
  repassent `EN_RUINE`, niveau 0, étape 0 : la ligne est gardée (une usine déjà découverte n'est pas à redécouvrir,
  seulement à remettre en état) et les incidents ouverts sont clos. La mairie, le silo, le plafond hors ligne et le
  personnel ne sont pas remis à zéro : ils ne sont pas encore modélisés.
- Le total de Lingots se partage selon `company_members.share_percent`. Chaque part est arrondie vers le bas, et le
  reste va au PDG, pour que la somme versée soit exactement le total.
- Chaque Lingot versé passe par `currency_ledger` (motif `prestige`, référence « entreprise-*n*-course-*k* »), et chaque
  membre reçoit un événement `ANNONCE` (`payload.action = "prestige"`) avec sa part. Le type `PRESTIGE_ENTREPRISE` de
  l'OpenAPI n'est pas utilisé : un événement unique ne dit pas la part de chacun.

## Les missions

`definitions/missions.json` liste les missions dans l'ordre où un joueur les fait. Chaque entrée dit son `id` (la clé de
`player_missions`), l'`evenement` qu'elle compte (`collect` pour l'instant), la `cible` (le nombre d'actions) et la
`recompense`. La récompense n'est pas un nombre : c'est le chemin du nombre dans `economie.json`, pour que le chiffre
reste à un seul endroit. La première mission, `bienvenue-mairie`, pointe `lingotsHorsPrestige.missionBienvenue`. Le
vérificateur de `tools/` et le chargeur du service refusent un chemin qui ne mène pas à un nombre entier de Lingots.

Le service tranche ainsi, **à valider par Kr** :

- Le service compte lui-même : un `collect` qui rapporte du Donar fait avancer la mission en cours. Un `collect` qui ne
  rapporte rien (rien d'écoulé, ou aucune usine qui produit) ne compte pas.
- Les missions forment une chaîne : la mission en cours est la première du catalogue que le joueur n'a pas terminée, et
  elle seule avance.
- À la cible, la mission se termine toute seule, dans la même transaction que le `collect` : les Lingots de la
  récompense sont versés (`currency_ledger`, motif `mission`, référence = l'identifiant de la mission) et un événement
  `ANNONCE` (`payload.action = "mission"`) prévient le joueur. Il n'y a pas de geste pour « réclamer ».
- Une mission terminée ne se rejoue jamais, et chaque joueur a les siennes (un associé qui encaisse avance sa mission,
  pas celle du PDG).
- `GET /v1/players/{xuid}/missions` rend la mission en cours (identifiant, avancement, cible, Lingots) et la liste des
  missions terminées. Les textes de chaque mission sont ceux de l'add-on, retrouvés par l'identifiant : le service ne
  les connaît pas.

## La ville : zones et bonus

- Chaque zone de l'île principale a un prix fixe en Lingots, connu à l'avance : clé
  `ville.zonesPrixLingots`, une entrée par zone, dans l'ordre. L'opération `buyZone` vérifie le
  niveau de mairie et le solde de l'associé qui paie.
- Les bonus des bâtiments restent petits et plafonnés. Ils jouent sur le Donar, le recrutement et la
  logistique, jamais sur les Lingots. Leurs valeurs ne sont pas encore dans `definitions/` : à
  calibrer avec le tableur avant la phase 4.

## La notation des grands nombres

- Affichage : k, M, Md, puis aa, ab, ac et ainsi de suite, deux lettres par puissance de mille :
  clé `notation.apresMd`. La feuille « Notation » du tableur donne la table complète.
- Le Donar est stocké en `DOUBLE` ; sa limite et le dernier suffixe atteignable sont dans la clé
  `notation.limiteDouble`. Une notation plus large pourra prendre le relais d'ici là.
- Format français : virgule décimale, espace entre les milliers, trois chiffres au plus avant
  l'unité. Les règles d'affichage sont dans [design/README.md](../design/README.md), section
  « Les mots », et dans [design/composants/Compteur.md](../design/composants/Compteur.md).

## Les treize cibles du tableur

La feuille « Synthèse » du tableur compare, pour un joueur type, le temps calculé à une fourchette
cible pour treize étapes : première amélioration, deuxième usine, première zone de ville, Mine
profonde, les trois prestiges, puis les six premières étoiles. Le simulateur de silooo-core (section « Le simulateur »)
rejoue ces treize étapes ; un chiffre qui sort d'une cible fait échouer la CI. La feuille
« Paramètres » liste les réglages, dans le même ordre que `economie.json`. Le tableur portait
encore le nom UrbaFactory : c'est le même fichier.

### Ce que le moteur de la phase 1 a dû trancher

Le moteur (`core/.../economy/`) rejoue le tableur à 1e-14 près en relatif, soit l'arrondi à 15 chiffres
des JSON dorés et rien d'autre. Pour y arriver, il applique les règles suivantes, toutes **à valider par Kr**
(points 1, 2, 4, 5 et 6 de `core/src/test/resources/golden/README.md`) :

- Paliers ×2 aux niveaux 25 et 50, puis à chaque multiple de 100 (clé `niveaux.paliersX2`).
- Lingots du prestige : valeur exacte de la formule, arrondie à l'entier inférieur, sauf si elle tombe à
  moins de 1e-9 (relatif) d'un entier, auquel cas on garde cet entier (100 Lingots au seuil, pas 99).
- Le multiplicateur de la course est calculé au départ et figé jusqu'au prestige. Il compte les Lingots
  gardés tels quels, fractionnaires (le joueur type du tableur en garde la moitié).
- Époque de la course = min(course, plus grande époque de `usines.json`) ; étoiles = course moins cette
  époque, jamais négatif.
- Les seuils de `prestige.seuils` (9 en phase 1) sont ceux du tableur. Au-delà, `prestigeThreshold` multiplie le
  dernier seuil par `prestige.facteurSeuilSuivant` à chaque course de plus (le texte de `etoilesSuivantes` parlait
  d'un facteur à recaler pendant l'alpha ; la clé le chiffre). Un seuil qui sortirait des doubles vaut
  `Double.MAX_VALUE`, pour que l'état d'une entreprise reste toujours calculable. **À valider par Kr.**
- Affichage : trois chiffres au plus, virgule, décimale tronquée (le tableur écrit « 195.5 M », la charte
  veut « 195 M »). Sous 1 000, entier arrondi à l'unité la plus proche.

## Le simulateur

Le simulateur fait jouer de bout en bout le joueur type du tableur et mesure, en heures de jeu, quand arrive chacune
des treize étapes. Il n'utilise que `EconomyEngine` et `definitions/`. Une étape hors de sa fourchette fait échouer
`ThirteenTargetsTest`, donc `./gradlew test`, `tools/verifier.sh` et la CI. C'est du code de test
(`core/src/test/kotlin/com/silooo/core/simulation/`) : le service ne s'en sert pas et l'image Docker ne l'embarque pas.

### Ce qu'il rejoue

Neuf courses, de la première au prestige de la neuvième (la 6e étoile, dernière des treize cibles) :

- les achats de niveaux (prix de `niveaux.croissancePrix`), les paliers ×2, le rachat et la remise en état des usines en
  ruine, la découverte des usines (clé `decouverte`) ;
- le prestige au seuil : Lingots, multiplicateur figé pour la course, époque, étoiles ;
- le régime en ligne (revenu plein, `onlineEarnings`) et, pour le reste de la journée du joueur type, le multiplicateur
  hors ligne (`dailyOfflineMultiplier`, cellule B8) : les temps sont des heures de jeu, comme les cibles ;
- les zones de ville payées avec la part des Lingots que le joueur ne garde pas.

Aucun chiffre du jeu n'est écrit dans le simulateur. Les hypothèses du joueur type (heures de jeu par jour, part de
Lingots gardée, heure de la mission de bienvenue) et son échelle de paliers viennent de `golden/parametres.json` et
`golden/simulation.json` ; les cibles, leurs fourchettes et leur provenance (le fichier du tableur et son empreinte)
viennent de `golden/synthese.json`, colonnes A à D de la feuille « Synthèse ». Ces JSON sont tirés du tableur par
`tools/tableur/extraire.py` : on ne les corrige pas à la main.

### La stratégie du joueur type

Le tableur ne fixe pas de règle : il donne une liste d'achats (feuille « Simulation »), que son auteur décrit comme
« un joueur qui achète toujours ce qui rapporte le plus vite ». La règle ci-dessous a été retrouvée en la confrontant
à cette liste. Elle redonne les 956 achats faits avant les neuf prestiges, dans le même ordre (la feuille compte
965 lignes : il y a en plus, par course, la première ligne que le joueur n'a plus le temps de faire avant son
prestige). `SimulatorSheetTest` le vérifie à chaque exécution.

1. Le joueur ne vise que les paliers de l'échelle de la feuille (1, 5, 10, 25, 50, 75, 100, 150…) : pour chaque usine,
   le prochain palier au-dessus de son niveau. Une usine en ruine n'a qu'un achat, le rachat, au prix du niveau où la
   remise en état la laisse.
2. Il choisit l'achat qui minimise `prix / revenu + prix / gain de revenu`, c'est-à-dire le temps d'attendre le prix
   plus le temps que l'achat met à se rembourser. À égalité parfaite, l'usine qui vient la première dans `usines.json`.
3. Il attend d'avoir exactement le prix, achète, recommence. Son solde est nul avant chaque achat.
4. Dès que les Donar gagnés atteignent le seuil, il fait son prestige, avant d'avoir fini d'attendre l'achat suivant :
   il prestige toujours au seuil exact.

### Les interprétations du simulateur, à valider par Kr

- **Découverte.** Les clés de `decouverte` se lisent `depart` (révélée dès le début), `niveau-entreprise-N` (la somme des
  niveaux atteint N), `epoque-N` (l'époque de la course atteint N) et `apres-<usine>` (l'usine nommée est remise en
  état). Une usine révélée le reste d'une course à l'autre, comme la ligne gardée par le prestige. Le moteur les lit
  dans `EconomyEngine.isDiscovered` ; une clé d'une autre forme fait échouer le chargement en nommant l'usine. Le
  service ne lit pas encore `decouverte` : il ne bloque aucun achat.
- **Remise en état.** Le simulateur la fait d'un coup, sans durée, après le rachat. Le tableur ne chiffre aucune durée
  de découverte ni de remise en état : ses temps sont « au plus court ».
- **Lingots de bienvenue.** Le tableur les compte dans le multiplicateur dès l'heure 0, alors que la mission de
  bienvenue les verse à l'heure B14. Le simulateur fait comme lui, sinon il ne retrouve pas ses multiplicateurs.
- **Zones de ville.** La formule de la feuille « Ville » : la première course dont le budget couvre le prix cumulé, puis
  l'heure à l'intérieur de cette course. Une zone ne se paie pas avant la mission de bienvenue.

### Lancer

```bash
cd core
./gradlew test --tests 'com.silooo.core.simulation.*' --console=plain               # le simulateur et ses trois jeux de tests
./gradlew test --tests 'com.silooo.core.simulation.ThirteenTargetsTest' --console=plain -i   # avec le tableau des treize cibles
```

Le tableau est aussi dans le rapport `core/build/reports/tests/test/index.html`, à la classe `ThirteenTargetsTest`.
Une exécution complète (neuf courses) prend moins d'une seconde.

- `ThirteenTargetsTest` : la barrière. Les treize temps contre leurs fourchettes, la valeur du tableur pour chacun
  (1e-14 en relatif), et des essais à blanc : un `croissancePrix` changé de +1 % ou de -5 % doit faire sortir le
  premier prestige de sa fourchette, par le haut ou par le bas.
- `SimulatorSheetTest` : le simulateur contre la feuille, achat par achat (usine, niveaux, prix, revenus, heures), puis
  course par course et zone par zone.
- `GameSimulatorTest` : la cohérence d'une partie (le temps avance, le prestige se fait au seuil, le rachat précède la
  montée, la découverte précède l'achat, les Lingots se reportent, une échelle épuisée échoue).

### Lire un échec

`ThirteenTargetsTest` liste les cibles hors de leur fourchette : l'identifiant, le temps mesuré en heures et en
minutes, la fourchette, le verdict (« Trop court » ou « Trop long ») et l'écart en heures, les cellules de la feuille,
puis les neuf courses (durée, fin, nombre d'achats). Par exemple, une ligne `! prestige-1 … mesuré 16,2 h … fourchette
[10 ; 15] h  Trop long (+1,2 h)  Synthèse!C9:D9`.

1. Regarde d'abord `SimulatorSheetTest`. S'il est rouge aussi, le simulateur ou le moteur ne rejoue plus le tableur :
   le message nomme le premier achat qui diverge. C'est une erreur de code, pas d'équilibrage ; corrige-la avant tout.
2. S'il est vert et que seul `ThirteenTargetsTest` est rouge, le simulateur est fidèle et c'est l'équilibrage qui a
   bougé : un chiffre de `definitions/` (relis la dernière ligne de « Historique des réglages ») ou une formule du
   moteur. Remets le chiffre dans sa fourchette ; ne touche ni à la fourchette ni à la cible pour faire passer le test.
3. Une fourchette ne change que dans le tableur, par Kr ; on régénère alors les JSON dorés avec
   `tools/tableur/extraire.py`, et le message d'échec nomme le fichier et l'empreinte lus.
4. « Jamais atteint » : la partie n'arrive pas à l'événement. « Échelle épuisée » : le joueur n'a plus de palier à
   acheter avant le seuil ; l'échelle vient de la feuille « Simulation », il faut lui ajouter des paliers.

Chaque réglage de `definitions/` consigne dans « Historique des réglages » le résultat du simulateur sur les treize
cibles : `ThirteenTargetsTest` les donne.

### Mesure de référence, le 5 novembre 2026

Sur `definitions/` et le tableur de ce jour, les treize étapes sont dans leur fourchette et le simulateur retrouve la
valeur du tableur pour chacune.

| Cible | Mesuré | Fourchette |
| --- | --- | --- |
| Première amélioration (Carrière niveau 5) | 0,080 h (4,8 min) | 0 à 0,083 h |
| Deuxième usine (Scierie) | 0,282 h | 0,25 à 0,333 h |
| Première zone de ville | 1,000 h | 0,75 à 1,5 h |
| Mine profonde | 2,017 h | 2 à 3 h |
| 1er prestige | 14,701 h | 10 à 15 h |
| 2e prestige | 37,508 h | 30 à 40 h |
| 3e prestige | 82,338 h | 70 à 100 h |
| Étoiles 1 à 6 (durée de la course) | 22,204 ; 20,981 ; 20,168 ; 20,880 ; 25,977 ; 21,575 h | 20 à 30 h |

La Mine profonde (une minute au-dessus du minimum) et la première amélioration (13 secondes sous le maximum) n'ont
presque pas de marge : le moindre réglage les fera sortir.

### Ce que le simulateur ne dit pas encore

- **Le rachat n'existe pas dans le service.** Le tableur fait payer à chaque course la première fois qu'une usine est
  remontée le prix du niveau où la remise en état la laisse. Le service ne vend rien : `restoreStep` est gratuit et donne
  ce niveau. Essai à blanc, rachat gratuit et immédiat : la Scierie arrive à 0,08 h (cible 0,25 à 0,333 h), la Mine
  profonde à 0,08 h (2 à 3 h), le 2e prestige à 20,9 h (30 à 40 h), le 3e à 48,5 h (70 à 100 h) et la première étoile à
  17,5 h (20 à 30 h). Il faut donc un prix ou une durée de remise en état dans `definitions/`, ou une autre façon de
  tenir ces cibles : décision de Kr, notée dans `docs/passation.md`.
- Les durées de découverte et de remise en état ne sont pas modélisées, faute de chiffre dans `definitions/`.
- Un seul joueur type. Marché, personnel, boutique et événements n'entrent pas dans les temps.

## Les garde-fous du long terme

- Chaque entrée d'argent a une sortie en face : salaires, rachats, entretien de la ville, intérêts,
  taxe du marché, cosmétiques en Donar. L'objectif de destruction hebdomadaire est dans
  [game-design.md](game-design.md), section « Économie sur le long terme ».
- Aucune baisse rétroactive : un ajustement s'annonce une semaine avant et vise les nouveaux
  achats, jamais ce que les joueurs possèdent.
- Chaque transaction est journalisée : en cas de faille, on corrige le compte, pas le serveur.
- Les classements hebdomadaires mesurent les Lingots gagnés dans la semaine, pas la richesse.

## Le marché

L'OpenAPI prévoit des annonces entre joueurs en Lingots, taxées (`market_listings`,
`market_trades.tax`). Le plan recommande plutôt une vente au Silo à prix fixé par le jeu, sans
annonces, pour qu'aucune monnaie ne circule entre joueurs. Tant que Kr n'a pas tranché, les
opérations de marché répondent « pas fait » et aucune valeur de marché n'entre dans `definitions/`.

## Historique des réglages

Une session qui change un chiffre de `definitions/` ajoute ici une ligne : date, clé changée,
ancienne valeur, nouvelle valeur, pourquoi, et le résultat du simulateur sur les treize cibles.

| Date | Clé | Avant | Après | Pourquoi |
| --- | --- | --- | --- | --- |
| 5 nov. 2026 | `prestige.departDeCourse` (`usine`, `niveau`) | absente | `carriere`, niveau `1` | Le tableur démarre chaque course avec la Carrière au niveau 1 (Paramètres!B17), jamais à zéro : règle du jeu sans clé, dont le moteur a besoin. **À valider par Kr** (point 2 de `core/src/test/resources/golden/README.md`). Treize cibles : inchangées, le rejeu des tests dorés les retrouve. |
| 5 nov. 2026 | `prestige.facteurSeuilSuivant` | absente (le texte `etoilesSuivantes` parlait d'un facteur à recaler) | `1000` | Le moteur doit donner un seuil à toute course, et `join` ne peut pas tomber au-delà de la 9e : la clé chiffre le texte. **À valider par Kr** (point 5 de `core/src/test/resources/golden/README.md`). Treize cibles : inchangées, aucune course listée ne change. |
| 5 nov. 2026 | `monde.json` : `archipel.ecartEntreCases`, `archipel.casesParRangee`, `archipel.origine` (`x`, `z`) | fichier absent | `600`, `64`, `0` et `0` | `CompanyBootstrap.slotOrigin` pose chaque île neuve sur une grille : colonne `N % casesParRangee`, rangée `N / casesParRangee`. 600 blocs reprend l'espace à prévoir par joueur ([game-design.md](game-design.md), section Technique) ; la largeur de 64 cases et l'origine en (0, 0) sont des valeurs de départ, à recaler quand le monde sera posé. **À valider par Kr.** Treize cibles : inchangées, ces clés ne touchent pas l'économie. |
| 5 nov. 2026 | `missions.json` : `missions[]` (`id`, `evenement`, `cible`, `recompense`) | fichier absent | `bienvenue-mairie`, `collect`, `1`, `lingotsHorsPrestige.missionBienvenue` | Le service compte les missions lui-même (`collect` qui rapporte) et les termine tout seul. Le catalogue n'a aucun chiffre d'économie : la récompense est le chemin d'une clé de `economie.json`, dont `lingotsHorsPrestige.missionBienvenue` (10) ne change pas. **À valider par Kr.** Treize cibles : inchangées, ces clés ne touchent pas l'économie. |
