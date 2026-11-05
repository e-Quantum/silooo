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
  pendant une absence. Un chef d'équipe affecté à une usine relève le plafond de cette usine seulement
  (section Le personnel).

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

## Le personnel

`definitions/personnel.json` dit tout : la liste du jour de l'agence, les trois raretés (part, bonus, salaire, prix), les
deux rôles, les postes et le plafond du chef. Le service ne contient aucun de ces chiffres.

**La liste du jour.** Cinq candidats par jour et par entreprise : quatre salariés et un chef d'équipe. Elle est tirée de
l'entreprise et du jour (UTC) seulement, donc identique toute la journée et à chaque lecture, et il n'existe aucun moyen de
payer pour la changer : pas de relance, pas de tirage payant, rien qui ressemble à un pari. Les raretés sortent d'une
répartition fixe, 75 % de communs, 20 % de rares, 5 % d'épiques.

**Ce que rapporte un membre.** Affecté à une usine qui produit, il change la production de cette usine de
`bonus - salaire`, en pourcent de ce qu'elle produirait sans équipe. Le salaire d'un rôle est le pourcent de salaire de la
rareté fois le facteur du rôle (le chef coûte plus cher). Les bonus de plusieurs membres s'additionnent. Le salaire est une
vraie sortie d'argent : il est retranché du revenu net, donc dans les deux régimes (en ligne au revenu plein, hors ligne par
le plafond et l'efficacité), et `CompanyState.ratePerHour` est ce revenu net. Un membre sans usine, ou dans une usine en ruine,
ne rapporte rien et ne coûte rien.

| Rareté | Part | Bonus | Salaire d'un salarié | Salaire d'un chef | Prix d'un salarié | Prix d'un chef |
| --- | --- | --- | --- | --- | --- | --- |
| Commun | 75 % | 10 % | 2,5 % | 3,75 % | 1 heure de revenu | 2 heures |
| Rare | 20 % | 25 % | 6 % | 9 % | 4 heures | 8 heures |
| Épique | 5 % | 60 % | 15 % | 22,5 % | 20 heures | 40 heures |

Ce sont les valeurs de départ de `personnel.json`. Le prix d'embauche est des heures du revenu net de l'entreprise au moment
de l'embauche : il suit la progression au lieu d'être un chiffre fixe, et il ne peut pas devenir négligeable plus tard.

**Retour sur investissement.** Quand une seule usine produit tout le revenu, un membre se paie après `prix / net` heures :

| Rareté | Salarié | Chef |
| --- | --- | --- |
| Commun | 13 h | 32 h |
| Rare | 21 h | 50 h |
| Épique | 44 h | 107 h |

Avec plusieurs usines le même membre ne porte qu'une part du revenu et se paie plus lentement. À recaler avec le simulateur
quand le joueur type embauchera ([Le simulateur](#le-simulateur)).

**Postes.** Une usine accueille un salarié, plus un par tranche de 25 niveaux, et un seul chef. Les postes ne limitent que
l'entrée : une usine qui en perd au prestige garde son équipe.

**Le chef d'équipe** relève de 4 heures le plafond hors ligne de l'usine où il travaille, et de celle-là seulement ; les
autres usines restent au plafond de l'entreprise. C'est le seul effet hors ligne du personnel.

Le service tranche ainsi, **à valider par Kr** :

- Tout membre de l'entreprise peut embaucher et affecter ; seul le PDG renvoie.
- L'embauche coûte du Donar de l'entreprise, jamais de Lingots. Un membre renvoyé ne rend rien.
- Le candidat renvoyé redevient embauchable le jour même, au prix du moment.
- Les bonus s'additionnent, ils ne se multiplient pas.
- Les noms et les apparences de l'agence, et leur nombre, sont provisoires.

## La ville : zones et bonus

- Chaque zone de l'île principale a un prix fixe en Lingots, connu à l'avance : clé
  `ville.zonesPrixLingots`, une entrée par zone, dans l'ordre. L'opération `buyZone` vérifie le
  niveau de mairie et le solde de l'associé qui paie.
- Les bonus des bâtiments restent petits et plafonnés. Ils jouent sur le Donar, le recrutement et la
  logistique, jamais sur les Lingots. Leurs valeurs sont dans `definitions/ville.json` : voir la section
  La ville côté service, plus bas.

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
| 5 nov. 2026 | `personnel.json` : `agence`, `raretes`, `roles`, `postes`, `chefEquipe` | fichier absent | liste de 5 (4 salariés, 1 chef), parts `75`/`20`/`5`, bonus `10`/`25`/`60`, salaires `2,5`/`6`/`15`, prix `1`/`4`/`20` heures de revenu, chef x`2` de prix et x`1,5` de salaire, 1 salarié + 1 par `25` niveaux et 1 chef, chef `+4` h hors ligne | Le contrat décrit l'agence (cinq candidats, trois raretés 75/20/5) sans chiffres de bonus, de salaire ni de prix : le service en a besoin pour embaucher et pour compter les salaires comme sortie d'argent. Valeurs de départ au plus simple, à recaler quand le joueur type embauchera. **À valider par Kr.** Treize cibles : inchangées, `ThirteenTargetsTest` reste vert (le joueur type n'embauche personne). |
| 5 nov. 2026 | `lingotsHorsPrestige.commandeDuJour`, `lingotsHorsPrestige.trouvaille`, `commandeDuJour` (`objectifUnites`, `minutesDeProductionParUnite`) | absentes | `1`, `1`, `100` et `1` | La commande mondiale du jour et le Carnet de trouvailles ont besoin de quatre chiffres que le game design ne donne pas. Une prime d'un Lingot reste dans l'ordre de `moyenneParHeureDeJeu` (1 par heure) ; 100 unités à une minute de revenu chacune font 100 minutes de revenu pour toute la commande. **À valider par Kr.** Treize cibles : inchangées, ces clés ne touchent ni la production ni le prestige. |
| 5 nov. 2026 | `incidents.json` : `frequence` (`creneauMinutes`, `chancePourcent`), `sortes[]` (`id`, `ralentissement`, `poids`), `recompense.minutesDeRevenu`, `electricite` (`aPartirDeEpoque`, `usineCentrale`) | fichier absent | créneau de `30` minutes, `20` % de chances, incendie x`0,5` (poids `1`), panne x`0,6` (poids `2`), courroie x`0,7` (poids `2`), `10` minutes de revenu, électricité dès l'époque `3`, Centrale `centrale` | Le contrat décrit l'incident (ralentit, réparation par mini-jeu, récompense en Donar) et l'électricité (époque 3 et plus, Centrale) sans chiffres : le service en a besoin pour tirer, ralentir et payer. Valeurs de départ au plus simple (voir la section Les incidents d'usine). **À valider par Kr.** Treize cibles : inchangées, `ThirteenTargetsTest` reste vert (le simulateur ne joue pas les incidents). |
| 5 nov. 2026 | `ville.json` : `ile`, `zones` (`colonnes`, `tailleChunks`, `premiere`, `ordre[]`), `mairie.emplacementDepart`, `logement.reductionSalairePourcent`, `plafonds`, `batiments[]` | fichier absent (`ville.zonesPrixLingots` seule, pour les prix des zones) | île de 4 x 4 zones de 8 chunks, parcelle d'un chunk ; première zone dès `2` usines actives ; mairie posée en (192, 192) ; salaire d'un logé `-20` % ; plafonds `60` places, `10` % de production, `40` % de vitesse ; mairie de `16` niveaux, route, maison, immeuble, commerce et garage (prix, mairie requise et bonus par niveau dans la section La ville côté service) | Le contrat décrit `buyZone`, `placeBuilding` et `upgradeBuilding` et le game design promet des bonus « visibles, petits, plafonnés », sans chiffre. Le service en a besoin pour décider. Valeurs de départ au plus simple, aucune ne donne de Lingots. **À valider par Kr.** Treize cibles : inchangées, `ThirteenTargetsTest` reste vert (le joueur type n'achète aucune zone ni aucun bâtiment, donc aucun bonus). |

## Les commandes du jour et le Carnet

Le game design parle d'une commande mondiale par jour que tout le serveur livre ensemble, et le plan de construction d'un
Carnet de trouvailles, sans donner de règle ni de chiffre. Le service prend la règle la plus simple pour chacun, et elle
est **à valider par Kr**. Les quatre chiffres sont dans `economie.json` : `commandeDuJour.objectifUnites` (100),
`commandeDuJour.minutesDeProductionParUnite` (1), `lingotsHorsPrestige.commandeDuJour` (1) et
`lingotsHorsPrestige.trouvaille` (1). Les ressources sont les produits de `usines.json`.

### La commande du jour

- **Quelle ressource** : la `(jours depuis 1970 mod nombre de ressources)`-ième des ressources du jeu, au jour UTC. Tout
  le serveur a la même, et chaque ressource revient à son tour. Aucun tirage au sort, aucune ligne en base tant que
  personne n'a livré.
- **Ce que coûte une unité** : du Donar de l'entreprise de celui qui livre, au prix de `minutesDeProductionParUnite`
  minutes de SON revenu par heure. Une minute de revenu pèse autant pour un petit joueur que pour un gros : le temps de
  jeu est le même, et le Donar d'un gros joueur n'achète pas la commande. La production en attente compte pour payer et
  est versée dans la même écriture.
- **Plusieurs livraisons par jour** : un joueur peut livrer plusieurs fois, ses parts s'additionnent. Une livraison qui
  dépasse le reste est ramenée à ce reste (et ne coûte que ce reste).
- **La prime** : `lingotsHorsPrestige.commandeDuJour` Lingots, versés une fois à chaque joueur qui a livré, quand la
  commande atteint l'objectif. Celui qui livre le dernier lot ne prend rien de plus, et personne ne perd sa prime parce
  qu'il a livré tôt. Chaque participant reçoit aussi un événement `COMMANDE_TERMINEE`. Le game design parle de jetons ;
  V1 récompense en Lingots ([glossaire.md](glossaire.md), ligne Jetons).
- **Après la fin** : une livraison sur une commande terminée ne prend rien et n'écrit rien. Le service répond 200 avec la
  commande finie, faute de code de refus au contrat.
- **Pas de hasard, pas de pari, aucune monnaie échangée entre joueurs** : on livre des unités contre du Donar, on reçoit
  des Lingots du jeu.

### Le Carnet de trouvailles

- **Une trouvaille est une ressource du jeu.** Elle entre dans le Carnet du joueur au premier encaissement qui rapporte
  du Donar alors qu'une de ses usines en marche la produit. Une usine en ruine ou en remise en état ne trouve rien.
- **Chaque trouvaille paie une fois** `lingotsHorsPrestige.trouvaille` Lingots, et le Carnet ne se vide pas au prestige :
  il est au joueur, pas à l'entreprise. Au plus une prime par ressource du jeu, une fois pour toutes.
- **Le Carnet est celui de qui encaisse** : un associé qui encaisse remplit le sien.
- `GET /v1/players/{xuid}/findings` rend les ressources trouvées (identifiant et instant), le nombre de ressources du jeu
  et la prime de chacune.

## Les incidents d'usine

Une usine tombe en panne de temps en temps, pendant qu'on joue. L'incident la ralentit jusqu'à ce qu'un joueur la répare
dans un mini-jeu ; il ne fait **rien perdre** (ni Donar, ni niveau, ni personnel) et il ne s'achète, ni ne s'évite, ni ne
se relance contre de l'argent. Le plan de construction (phase 4) demande l'incident et sa réparation ; le service a pris,
pour ce que le plan laisse ouvert, la règle la plus simple. Tous les chiffres sont dans `definitions/incidents.json`.

- **La cadence** : le temps se découpe en créneaux de `frequence.creneauMinutes` (30) minutes UTC. À chaque créneau,
  `frequence.chancePourcent` (20) % de chances qu'un incident survienne, soit au plus 0,4 incident par heure de jeu en
  ligne. Le tirage est figé par l'entreprise, le jour et le rang du créneau (même générateur que l'agence d'emploi) :
  le rejouer donne la même chose, et rien ne permet de tirer un autre résultat.
- **Les sortes** (`sortes`) : l'incendie ralentit à x0,5 (poids 1, une fois sur cinq), la panne à x0,6 (poids 2) et la
  courroie à x0,7 (poids 2). Le facteur multiplie la production de l'usine touchée tant que l'incident dure. La sorte
  dit à l'add-on quel mini-jeu proposer.
- **Où il tombe** : sur une usine en marche (`ACTIVE`, niveau 1 au moins) qui n'a pas déjà d'incident et qui n'est pas à
  l'arrêt faute de courant. Au plus un incident par entreprise et par créneau, au plus un ouvert par usine.
- **Jamais pendant une absence** : le service ne tire que pour une entreprise dont un membre est connecté depuis deux
  passages de son minuteur (30 s). Hors ligne, rien n'est ralenti non plus : un incident ouvert ne coûte que le temps
  passé en ligne, et le calcul de l'absence ([Hors ligne](#hors-ligne)) ne change pas.
- **Ce que ça coûte** : tant que l'incident dure et que le joueur est en ligne, l'usine rapporte `ralentissement` de son
  revenu : une panne coûte 40 % du revenu de cette usine par heure en ligne, et presque rien si elle est réparée tout de
  suite. L'ouverture verse d'abord ce que l'entreprise avait produit, au rythme d'avant.
- **La récompense** : réparer rapporte `recompense.minutesDeRevenu` (10) minutes du revenu de l'usine sans
  ralentissement, en Donar, comptées dans le Donar gagné de la course comme n'importe quelle production. Réparer vite
  est donc toujours gagnant. Un associé peut réparer à la place du PDG ; un incident déjà réparé ne rapporte rien une
  seconde fois. Un prestige clôt les incidents ouverts, avec le reste de la remise à zéro.
- **Pas de hasard payant** : la récompense est un montant fixe, jamais tirée ; aucune route n'achète une relance, un
  bouclier ou une réparation. L'incident reste un événement du jeu, pas une mécanique de dépense.

À valider par Kr : la cadence (30 minutes, 20 %), les trois sortes et leurs parts, les facteurs 0,5 / 0,6 / 0,7, les
10 minutes de revenu de la récompense, et l'idée qu'une absence n'est jamais ralentie. Les treize cibles du tableur ne
dépendent d'aucune de ces valeurs : le simulateur ne joue ni incident ni coupure de courant, et sans incident les chiffres
d'avant sont inchangés.

## L'électricité

À partir de l'époque `electricite.aPartirDeEpoque` (3), une usine marquée `"electricite": true` dans `usines.json`
(verrerie, menuiserie, conserverie, usine textile, usine robots, cristallerie) s'arrête tant que l'entreprise n'a aucune
Centrale (`electricite.usineCentrale`, la `centrale` de l'époque 3) en marche, au niveau 1 au moins. La Centrale ne dépend
elle-même d'aucun courant : on la relève en premier.

- **À l'arrêt** : l'usine ne rapporte rien, **et** son équipe ne coûte aucun salaire, en ligne comme hors ligne. Elle garde
  son niveau, son état et son personnel ; rien n'est perdu, la production reprend dès qu'une Centrale tourne.
- **Avant l'époque 3** : aucun effet. Une usine en ruine, en remise en état ou au niveau 0 ne produit pas, donc ne compte
  pas non plus comme Centrale.
- **Avec un incident** : une usine arrêtée n'est jamais frappée, et la réparer rapporte 0 (la récompense suit le revenu).
- **À valider par Kr** : l'époque de départ, et le fait que la règle soit « une Centrale suffit » plutôt qu'une capacité
  qui se partage entre les usines. Le plan ne la chiffre pas, la description du contrat la dit à son plus simple.

## L'entreprise partagée

Le game design parle d'une entreprise partagée jusqu'à 4 associés, avec un PDG qui décide, et de Lingots de prestige partagés
« selon la part que le PDG attribue à chacun ». Il ne dit pas comment on devient associé, ni ce que devient l'entreprise que
le nouvel associé avait déjà. Le service prend la règle la plus simple, la moins destructrice, et elle est **à valider par Kr**.
Les deux chiffres sont dans `monde.json`, bloc `entreprise` : `membresMax` (4, PDG compris) et `invitationHeures` (48).

| Date | Clé | Avant | Après | Pourquoi |
|---|---|---|---|---|
| 5 nov. 2026 | `entreprise.membresMax`, `entreprise.invitationHeures` | absentes | `4` et `48` | Le game design donne « jusqu'à 4 » ; la durée d'une invitation n'est écrite nulle part. 48 heures laissent deux soirées à un ami. **À valider par Kr.** Treize cibles : inchangées, ces clés ne touchent ni la production ni le prestige. |

- **Inviter** : seul le PDG invite. L'invité reçoit un événement et a `invitationHeures` heures pour accepter. Inviter ne
  change rien à l'entreprise tant que l'invité n'a pas accepté.
- **Une seule entreprise par joueur, et tout joueur en a une dès son premier `join`.** Pour rejoindre une autre entreprise, la
  sienne doit être encore « à son départ » : lui seul dedans, aucun prestige, la première course, la première époque, la mairie et
  le silo au niveau 1, aucun personnel, aucune zone de ville, les usines de départ au niveau de départ. Dès qu'il a joué un
  peu plus, il ne peut plus rejoindre personne : on ne fait pas disparaître une partie commencée.
- **Ce que devient l'entreprise laissée** : rien. Elle n'est jamais supprimée. Elle reste, sans membre, avec ses usines, son
  île et sa case de l'archipel. Le jeu n'a pas encore de façon de la quitter, de la reprendre ou de la libérer.
- **Entrer** : l'associé arrive avec 0 % des parts, donc le PDG garde 100 % jusqu'à ce qu'il décide autrement. Les parts font
  toujours 100 en tout, PDG compris, et le PDG peut se donner 0 %.
- **Le partage des Lingots au prestige est inchangé** : le total se partage selon `company_members.share_percent`, chaque part
  arrondie vers le bas (voir la section Le prestige plus haut). Seule la façon de fixer les parts est nouvelle.
- **L'arrivée d'un associé ne vole rien à l'entreprise** : la production gagnée jusqu'à son arrivée est versée d'abord, au
  régime d'avant (en ligne si un membre était connecté, sinon hors ligne). Son arrivée ne transforme pas en revenu plein des
  heures où personne ne jouait. Ses propres Lingots comptent dans le multiplicateur de l'entreprise à partir de son arrivée,
  comme ceux de tout associé.
- **Pas de hasard, pas de pari, aucune monnaie échangée entre joueurs** : on invite, on accepte, on règle des parts.

## La ville côté service

Le game design promet des zones qu'on achète, des bâtiments qu'on pose et une mairie qui monte, avec des bonus « visibles,
petits, plafonnés ». Le service décide de tout (qui peut acheter, ce que ça coûte, ce que ça donne) ; l'add-on n'affiche que ce
qu'il lui rend (`CompanyState.city`). Les chiffres sont dans `definitions/ville.json` (valeurs de départ, **à valider par Kr**) et,
pour le prix des zones, dans `economie.json` (`ville.zonesPrixLingots`). Les tableaux ci-dessous sont recopiés le 5 novembre 2026 :
le JSON fait foi.

### Les zones

L'île principale est une grille de 4 x 4 zones, chacune de 8 x 8 chunks (128 blocs de côté). Les zones se numérotent de 1 à 16
dans l'ordre où on les achète : on commence près du centre, on remplit le carré du milieu, puis on fait le tour de l'île.

| Zone | Colonne, rangée | Mairie requise | Prix (Lingots) | Zone | Colonne, rangée | Mairie requise | Prix (Lingots) |
|---|---|---|---|---|---|---|---|
| 1 | 1, 1 | 1 | 5 | 9 | 3, 2 | 9 | 75 |
| 2 | 2, 1 | 2 | 10 | 10 | 3, 3 | 10 | 90 |
| 3 | 2, 2 | 3 | 15 | 11 | 2, 3 | 11 | 110 |
| 4 | 1, 2 | 4 | 20 | 12 | 1, 3 | 12 | 130 |
| 5 | 1, 0 | 5 | 30 | 13 | 0, 3 | 13 | 150 |
| 6 | 2, 0 | 6 | 40 | 14 | 0, 2 | 14 | 180 |
| 7 | 3, 0 | 7 | 50 | 15 | 0, 1 | 15 | 200 |
| 8 | 3, 1 | 8 | 60 | 16 | 0, 0 | 16 | 235 |

- **Dans l'ordre.** On n'achète que la zone suivante, jamais une zone plus loin ni une zone de moins : refus `ZONE_VERROUILLEE`.
- **Le niveau de la mairie ouvre la zone** : la zone N demande une mairie de niveau N (colonne « Mairie requise »).
- **La première zone** demande en plus `zones.premiere.usinesActivesMin` (2) usines actives : un joueur qui n'a que sa Carrière de
  départ n'a pas encore de ville. Les autres zones ne demandent pas d'usines.
- **La première zone pose la mairie**, au niveau 1, gratuitement, à `mairie.emplacementDepart` (192, 192), dans la zone 1.
- **Le prix est fixe et connu à l'avance.** Il se paie en Lingots, jamais en Donar.

### Les bâtiments

Une parcelle fait un chunk (16 x 16 blocs) ; un bâtiment en occupe une, et une parcelle n'a qu'un bâtiment. Les coordonnées sont
en blocs depuis le coin de l'île principale, et se posent sur la grille des parcelles. Un bâtiment se pose au niveau 1 et monte
niveau par niveau. Chaque ligne donne, par niveau : le prix en Lingots pour y arriver, la mairie requise, le bonus total du bâtiment.

| Bâtiment | Au plus | Bonus | Niveau 1 | Niveau 2 | Niveau 3 | Niveau 4 |
|---|---|---|---|---|---|---|
| Route | 200 | aucun | 1 L, mairie 1 | 2 L, mairie 3 | | |
| Maison | 12 | `LOGEMENTS` | 3 L, mairie 1, 1 place | 6 L, mairie 3, 2 places | 12 L, mairie 5, 3 places | |
| Immeuble | 6 | `LOGEMENTS` | 10 L, mairie 3, 4 places | 20 L, mairie 5, 6 places | 35 L, mairie 8, 8 places | 60 L, mairie 11, 10 places |
| Commerce | 4 | `PRODUCTION_DONAR` | 8 L, mairie 2, 0,5 % | 15 L, mairie 4, 1 % | 30 L, mairie 7, 1,5 % | 55 L, mairie 10, 2,5 % |
| Garage | 3 | `LIVRAISON_VITESSE` | 12 L, mairie 4, 5 % | 25 L, mairie 6, 10 % | 45 L, mairie 9, 15 % | |

- **La mairie** n'est pas posable : elle vient avec la première zone. Elle a 16 niveaux ; passer du niveau N au niveau N + 1 coûte
  le prix du niveau N + 1 (2, 3, 4, 6, 8, 10, 12, 15, 18, 22, 26, 30, 35, 40, 45 Lingots), sans mairie requise de plus. Son niveau
  vit dans `companies.mairie_level`.
- **Les routes** ne donnent rien : c'est le décor qui relie les bâtiments.
- **Les refus** : une parcelle prise donne `EMPLACEMENT_OCCUPE` ; une zone que l'entreprise n'a pas achetée, ou une mairie trop
  basse pour le bâtiment ou son niveau, donne `ZONE_VERROUILLEE` ; autant d'exemplaires que le catalogue le permet, ou un bâtiment
  déjà au dernier niveau, donne `DEJA_POSSEDE`. Le contrat n'a pas de code plus précis (voir le dossier d'écart plus bas).

### Ce que les bonus donnent

Aucun bonus ne donne de Lingots, ni ne touche au prestige ou à ses seuils. Les bonus d'un même type s'additionnent, puis se
plafonnent (`plafonds`).

| Type | Ce que ça fait | Plafond |
|---|---|---|
| `LOGEMENTS` | des places pour loger du personnel ; un salarié logé coûte 20 % de salaire en moins (`logement.reductionSalairePourcent`) | 60 places |
| `PRODUCTION_DONAR` | multiplie le multiplicateur global de production par `1 + pourcent / 100`, donc toute la production de l'entreprise | 10 % |
| `LIVRAISON_VITESSE` | un pourcentage de vitesse pour les camions de livraison ; montré dans l'état de la ville, appliqué quand le jeu aura des livraisons | 40 % |

- **Le logement** : le nombre de places est la partie entière de la somme des bonus `LOGEMENTS`. Les membres du personnel sont logés
  dans l'ordre de leur identifiant (les premiers embauchés d'abord), jusqu'à ce qu'il n'y ait plus de place ; un membre sans usine
  prend une place comme un autre. Un membre logé coûte `salaire x (1 - 20 / 100)`. `Staff.housed` le dit à l'add-on, et
  `Staff.salaryPercent` et `Staff.salaryPerHour` portent déjà le salaire réduit.
- **Le bonus de production** s'applique au multiplicateur global (celui des Lingots détenus et des étoiles), donc il touche le revenu
  de chaque usine et la somme des usines redonne toujours le revenu de l'entreprise. Sans commerce, le calcul est celui d'avant la
  ville, bit pour bit.
- **Sans bâtiment à bonus, ou sans aucune ville, rien ne change** : multiplicateur, revenu, salaires et production versée sont ceux
  d'avant. Treize cibles du tableur : inchangées, le joueur type n'achète ni zone ni bâtiment.
- **La ville ne se remet pas à zéro au prestige.** Zones, bâtiments et niveau de la mairie sont la durée de vie de l'entreprise, pas
  d'une course ; le prestige ne touche pas aux tables de la ville.

### Qui paie

Les Lingots sont personnels (un solde par joueur, jamais celui de l'entreprise). **Tout membre de l'entreprise, PDG ou associé, peut
acheter, poser et améliorer, et paie avec ses propres Lingots.** Les Lingots du PDG ne couvrent pas un associé à court : le refus
`PAS_ASSEZ_DE_LINGOTS` donne la somme qui manque à celui qui agit. Le journal des monnaies garde un motif par action : `zone`,
`batiment`, `amelioration`. Aucune action de la ville n'échange de Lingots entre joueurs.

### À valider par Kr

- Tous les chiffres de `ville.json` (zones, mairie, prix, niveaux, bonus, plafonds) sont des valeurs de départ au plus simple.
- Les zones s'achètent dans l'ordre, avec une mairie de niveau égal au numéro de la zone : le plan n'a pas fixé ce lien.
- La première zone demande deux usines actives et pose la mairie niveau 1 gratuitement.
- Tout membre peut acheter, avec ses Lingots ; le PDG n'a pas de droit particulier ici.
- Le logement porte sur les premiers embauchés, et ne dépend pas de leur poste.
- La ville survit au prestige.
- Le contrat n'a pas de code pour « bâtiment pas encore permis par la mairie » ni « quantité maximale » ni « dernier niveau » : le
  service rend `ZONE_VERROUILLEE` et `DEJA_POSSEDE`, faute de mieux.
- Le bonus de vitesse des livraisons est calculé et montré, mais aucun camion ne l'utilise encore.

## La boutique : Gemmes, grades et Pilier

Les Gemmes s'achètent en euros sur le site de SileneMC (PayNow) et se dépensent en jeu. Le service décide de tout : ce qu'un joueur
peut acheter, ce que ça coûte, ce que ça donne ; l'add-on n'affiche que ce qu'il lui rend (`catalog`, le solde de `buyItem`). Les
chiffres sont dans `definitions/boutique.json` (les nombres de SileneMC, relevés le 4 novembre 2026) ; les tableaux ci-dessous sont
recopiés le 5 novembre 2026 : le JSON fait foi. Cette tranche ne vend que les grades et les deux abonnements ; les cosmétiques, les
boosts collectifs et le pass viendront avec leurs propres articles.

### Les grades à vie

Six grades, à prendre dans l'ordre (`regleGrades`, comme sur SileneMC). Un grade se garde pour toujours, le prestige n'y touche pas.

| Ordre | Grade (`id`) | Prix (Gemmes) | Bonus de Donar |
|---|---|---|---|
| 1 | MVIP (`mvip`) | 100 | +5 % |
| 2 | VIP (`vip`) | 500 | +5 % |
| 3 | VIP+ (`vip+`) | 1 000 | +10 % |
| 4 | Héros (`heros`) | 2 000 | +20 % |
| 5 | Légende (`legende`) | 3 500 | +30 % |
| 6 | Ultime (`ultime`) | 6 000 | +50 % |

- **Le seul grade qu'on puisse acheter est le premier que le destinataire n'a pas.** Un grade déjà possédé donne `DEJA_POSSEDE`, un
  grade plus loin que le suivant donne `GRADE_PRECEDENT_REQUIS`. Dans le catalogue, seul ce grade est `available`.
- **Le grade s'écrit dans `player_ranks`** avec la source `achat` (pour soi) ou `cadeau` (pour un autre joueur).

### Les abonnements

| Abonnement (`id`) | Prix (Gemmes) | Durée | Bonus de Donar | Gemmes rendues | Condition |
|---|---|---|---|---|---|
| Pilier (`pilier`) | 400 | 28 jours | +10 % tant qu'il court | 50 à chaque achat | aucune |
| Perso (`perso`) | 2 500 | 28 jours | aucun | aucune | le grade Ultime (`reserveA`) |

- **Un abonnement se rachète à volonté.** S'il court encore, les jours s'ajoutent à sa fin ; s'il est fini (ou jamais pris), ils
  partent de maintenant. Son début ne change que lorsqu'il repart de zéro. `player_subscriptions.months_total` compte les achats et ne
  retombe jamais à zéro, même après un trou : c'est le badge qui évolue.
- **Le Pilier rend 50 Gemmes à chaque achat**, au destinataire, dans le journal des monnaies (motif `pilier`).
- **Le Perso demande l'Ultime** du destinataire. Le contrat n'a pas de code pour ce refus : le service rend
  `GRADE_PRECEDENT_REQUIS`, faute de mieux. Le Perso ne change aucun chiffre du jeu : il ouvrira l'option de préfixe personnel côté
  jeu, à venir.
- **Dans le catalogue**, un abonnement est `owned` tant qu'il court, et `available` dès que le grade qu'il demande est possédé
  (toujours pour le Pilier).

### Qui paie, qui reçoit

- **L'acheteur paie avec ses propres Gemmes**, sur son solde personnel (`PAS_ASSEZ_DE_GEMMES` avec la somme qui manque, sans rien
  écrire). Motif du journal : `boutique`, référence : l'article.
- **Un cadeau** (`forXuid`) : l'acheteur paie, le destinataire reçoit le grade ou l'abonnement, et les Gemmes rendues du Pilier.
  Les vérifications de possession (`DEJA_POSSEDE`, `GRADE_PRECEDENT_REQUIS`) portent sur le destinataire. Un cadeau à soi-même est
  un achat pour soi.
- **Un `requestId` ne sert qu'à un achat.** Rejoué, il rend la même réponse sans payer deux fois (`api_requests`), et
  `shop_purchases.request_id` est unique en base.
- **Un article inconnu ou pas encore vendu, un acheteur ou un destinataire que le service ne connaît pas** (jamais passé par `join`) :
  400, rien d'écrit.
- **Aucun événement n'est écrit pour un achat.** `GEMMES_RECUES` est celui d'une livraison de commande.

### Le bonus de Donar

Le bonus joue sur le multiplicateur global de l'entreprise (celui des Lingots détenus, des étoiles et de la ville), donc sur le
revenu de chaque usine et sur la production versée, en ligne comme hors ligne.

- **C'est le PDG de l'entreprise qui compte**, pas celui qui joue : le multiplicateur est celui de l'entreprise, le même pour tous ses
  membres. Le grade ou le Pilier d'un associé ne change rien ; ceux du PDG profitent à toute l'entreprise.
- **Un seul grade compte : le plus haut**, jamais la somme des grades. Un PDG Ultime a +50 %, pas +95 %.
- **Le Pilier compte tant qu'il court**, en plus du grade. Le Perso ne compte pas.
- **Les bonus se multiplient comme celui de la ville** : `multiplicateur x (1 + grade / 100) x (1 + Pilier / 100)`, après le bonus de
  la ville (`EconomyEngine.withShopBonuses`). Ultime et Pilier font donc x1,5 puis x1,1, soit x1,65. Un bonus à zéro n'est pas
  appliqué.
- **Sans grade ni Pilier, rien ne change** : multiplicateur, revenu, salaires et production versée sont ceux d'avant, bit pour bit.
  Treize cibles du tableur : inchangées, le joueur type n'achète rien dans la boutique.
- **Un achat ne règle pas la production en attente.** Le nouveau bonus s'applique au prochain règlement, sur tout le temps écoulé
  depuis le précédent, hors ligne comprise (le plafond d'heures hors ligne reste le même). Un grade acheté juste avant de se
  reconnecter profite donc à la période d'absence. L'écart reste borné par ce plafond.

### Les livraisons de Gemmes

Le site écrit une ligne dans `shop_deliveries` quand PayNow confirme un paiement. Le service la verse au joueur dès qu'il le
connaît ([base-de-donnees.md](base-de-donnees.md), section « Les livraisons de Gemmes »).

- **Un passage toutes les 30 secondes** par défaut (variable `SILOOO_DELIVERY_POLL_SECONDS`, de 1 à 3 600), et **au `join`** : les
  Gemmes d'un joueur qui arrive sont versées avant la réponse, sans attendre le passage suivant.
- **Le joueur d'une ligne** : son XUID si la ligne en porte un que `players` connaît, sinon son pseudo (casse ignorée), et si
  plusieurs joueurs le portent, celui qu'on a vu en dernier. Personne ne correspond : la ligne reste en attente, intacte, et le
  passage suivant la retente. Au `join`, une ligne qui revient à un autre joueur n'est pas versée à celui qui arrive.
- **Versée une seule fois**, quoi qu'il arrive : dans une seule transaction, la ligne est marquée (`delivered_at`, XUID), les Gemmes
  sont créditées (motif `livraison`, référence : la commande) et l'événement `GEMMES_RECUES` est écrit (`orderId`, `gemmes`,
  `gemmesTotal`). Si un des trois échoue, aucun ne reste. Deux passages en même temps, ou un passage et un `join`, ne versent pas
  deux fois.
- **Les packs de Gemmes** (`packsGemmes` : prix en euros et bonus) sont ceux du site ; le service ne les lit pas.

### Ce qui reste à valider par Kr

Tous ces choix sont les plus simples que les définitions et le contrat permettaient. Aucun chiffre n'a été ajouté à `definitions/`.

- Les grades s'achètent dans l'ordre ; seul le suivant est `available`.
- Le Perso sans Ultime se refuse avec `GRADE_PRECEDENT_REQUIS` (pas de code dédié au contrat).
- Le Pilier se rachète à volonté et s'ajoute à sa fin ; ses Gemmes rendues vont au destinataire à chaque achat, cadeau compris ;
  `months_total` ne retombe pas à zéro après un trou.
- Le bonus de Donar est celui du PDG, et non de celui qui joue ou de la moyenne des membres.
- Le grade et le Pilier se multiplient entre eux et avec la ville ; la feuille « Courses » du tableur (colonne « Multiplicateur
  global ») est la référence à confirmer.
- Un achat ne règle pas la production en attente : le bonus profite à toute la période depuis le dernier règlement.
- Un achat n'écrit aucun événement ; seule une livraison écrit `GEMMES_RECUES`.
- Une livraison sans joueur connu attend sans limite de temps.
- L'intervalle du relevé (30 secondes) est un réglage technique, pas un chiffre du jeu.
