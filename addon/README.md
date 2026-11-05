# addon : le pack de comportement de Silooo

L'add-on tourne dans le serveur Bedrock (BDS) et parle en HTTP à silooo-core. Il n'a aucune règle de jeu :
aucun solde, prix ni gain ne se calcule ici. Il envoie ce qui se passe (arrivée, départ, heartbeat), lit les
événements que le service publie, et affiche.

## Où est quoi

| Chemin | Rôle |
|---|---|
| `manifest.json` | Pack "Silooo", module script `scripts/main.js`, dépendances. Les deux uuid ne changent jamais. |
| `src/main.ts` | Point d'entrée. Rien ne le fait tomber : toute exception est attrapée et journalisée. |
| `src/beta.ts` | Le seul fichier qui touche aux modules bêta (réseau, secret, variables). |
| `src/http.ts` | Client de silooo-core : résultats typés ok, refusé, erreur, réseau ; un seul nouvel essai, pour les GET. |
| `src/protocol.ts`, `src/api.ts` | Chemins, corps et lecture des réponses, conformes à `api/silooo-core.openapi.yaml`. |
| `src/session.ts`, `src/state.ts` | `join` à la première arrivée, `leave` au départ, état gardé par joueur. |
| `src/heartbeat.ts`, `src/events.ts` | Heartbeat régulier ; lecture des événements avec un curseur qui part de 0. |
| `src/config.ts` | Cadences et délais techniques. Aucun chiffre du jeu. |
| `src/generated/definitions.ts` | Types tirés de `definitions/`. Généré, ne pas éditer. |
| `test/` | Tests de la logique, sans Bedrock. |
| `scripts/` | `construire.mjs` (le build) et `tester.mjs` (les tests). |

## Construire et tester

Node 22 ou plus. Depuis ce dossier :

```
npm ci
npm test        # compile les tests avec esbuild, puis node --test
npm run build   # tsc strict, puis dist/
```

Le build produit `dist/pack/` (le dossier à copier dans `development_behavior_packs/` du serveur) et
`dist/silooo-addon.mcpack`. Le script est un seul fichier ESM, `dist/pack/scripts/main.js`, avec les modules
`@minecraft/*` laissés en import. Le dossier `dist/` n'est pas versionné ; son chemin est un contrat avec
`deploy/`.

Les types de `src/generated/` se refont depuis la racine du dépôt : `node tools/generer_types.mjs`.
`tools/verifier.sh` échoue s'ils sont périmés.

## URL de silooo-core et clé

Rien de tout cela n'est dans le pack. Le déploiement pose deux fichiers dans le dossier de configuration de
BDS, `config/<uuid du module script>/` (voir `docs/bedrock.md`, fait 2) :

- `variables.json` : `core_url` (l'URL de base de silooo-core, en `http://` ou `https://`) et, si besoin,
  `server_id` (par défaut `bds-1`) ;
- `secrets.json` : `silooo_key`, la clé d'API.

L'add-on lit la clé par `secrets.get` et la passe telle quelle dans l'en-tête `X-Silooo-Key` : il ne peut ni la
lire ni l'écrire dans un journal. Si la clé ou l'URL manque, il reste inactif et le dit une fois dans le journal.

## Beta APIs

`@minecraft/server-net` et `@minecraft/server-admin` sont des modules bêta. Le manifeste les déclare en
`1.0.0-beta`, et chaque monde qui utilise le pack doit avoir l'option "Beta APIs" activée. Leurs versions de
types sont épinglées dans `package.json` (`1.0.0-beta.1.26.62-stable`), jamais en `latest`. À chaque nouvelle
version de Bedrock, on relit les appels listés ci-dessous.

Appels bêta, tous dans `src/beta.ts` :

- `@minecraft/server-net` : `http.request`, `HttpRequest` (uri, method, timeout, headers, body), `HttpHeader`,
  `HttpRequestMethod`, `HttpResponse` (status, body) ;
- `@minecraft/server-admin` : `secrets.get`, `variables.get`, et le type `SecretString`.

## Points ouverts

- L'API de script ne donne pas le XUID d'un joueur. `src/identity.ts` envoie `Player.id` ; si silooo-core exige
  un XUID numérique, c'est le seul fichier à changer une fois la source du XUID décidée.
- Ce qui ne se vérifie que sur un vrai BDS : le comportement de `http.request` sur un statut 4xx ou 5xx et sur un
  délai dépassé, l'ordre de démarrage du script, le format de `allowed_uris` dans `permissions.json`.
- Un `join` qui échoue n'est pas rejoué, et un script rechargé ne refait pas le `join` des joueurs déjà en ligne.
