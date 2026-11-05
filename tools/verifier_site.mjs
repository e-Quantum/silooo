#!/usr/bin/env node
// Les pages du site dans un vrai navigateur. Chaque index.html du dossier rendu (core/build/site-rendu, écrit par
// SiteRenderToDisk à chaque passage des tests Gradle) s'ouvre dans Chromium (Playwright) à 360 px et à 1 280 px de
// large, servi par un petit serveur local pour que /public/ se charge comme sur silooo.com.
//
// Usage : node tools/verifier_site.mjs [--captures <dossier>] [--sonder] [<rendu>]
//   <rendu>               le dossier des pages rendues, un index.html par page (défaut : core/build/site-rendu)
//   --captures <dossier>  écrit une capture JPEG de chaque page à chaque largeur dans ce dossier (la relecture en part)
//   --sonder              dit seulement si Chromium est là : code 0 s'il l'est, 3 sinon (1 en CI)
//
// Échoue (code 1) si une page déborde horizontalement (en nommant les éléments coupables), si la console a une erreur,
// si une requête échoue ou répond 400 ou plus, ou si une image n'a pas d'attribut alt (un alt vide dit une image décorative). Code 3 si Chromium de Playwright manque
// (npx playwright install chromium), sauf en CI (variable CI posée) où c'est un échec : la CI l'installe toujours.
import { createServer } from 'node:http';
import { createReadStream, existsSync, mkdirSync, statSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import path from 'node:path';

const LARGEURS = [360, 1280];
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.xml': 'application/xml',
  '.txt': 'text/plain; charset=utf-8',
};

function usage(message) {
  console.error(message);
  console.error('Usage : node tools/verifier_site.mjs [--captures <dossier>] [--sonder] [<rendu>]');
  process.exit(64);
}

const options = { captures: null, sonder: false, rendu: 'core/build/site-rendu' };
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  const arg = args[i];
  if (arg === '--captures') {
    options.captures = args[++i] ?? usage('--captures attend un dossier');
  } else if (arg === '--sonder') {
    options.sonder = true;
  } else if (arg === '-h' || arg === '--help') {
    usage('');
  } else if (arg.startsWith('-')) {
    usage(`Option inconnue : ${arg}`);
  } else {
    options.rendu = arg;
  }
}

/** Le code de sortie quand Chromium manque : 3 sur un poste (l'étape s'ignore), 1 en CI (elle doit tourner). */
const SANS_CHROMIUM = process.env.CI ? 1 : 3;

async function chromiumOuRien() {
  let playwright;
  try {
    playwright = await import('playwright');
  } catch {
    console.error('Le paquet playwright manque : npm ci à la racine du dépôt.');
    process.exit(SANS_CHROMIUM);
  }
  const executable = playwright.chromium.executablePath();
  if (!existsSync(executable)) {
    console.error(`Chromium de Playwright manque (${executable}) : npx playwright install chromium.`);
    process.exit(SANS_CHROMIUM);
  }
  return playwright.chromium;
}

const chromium = await chromiumOuRien();
if (options.sonder) {
  console.log('Chromium de Playwright est là.');
  process.exit(0);
}

const racine = path.resolve(options.rendu);
if (!existsSync(racine) || !statSync(racine).isDirectory()) {
  console.error(`${options.rendu} n'est pas un dossier : les tests Gradle l'écrivent (SiteRenderToDisk).`);
  process.exit(1);
}

/** Chaque index.html du dossier, en adresse de page : « / » pour la racine, « /ouvert/wiki/ » pour un sous-dossier. */
async function pages(dossier, prefixe = '/') {
  const resultat = [];
  const entrees = (await readdir(dossier, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name));
  if (entrees.some((e) => e.isFile() && e.name === 'index.html')) resultat.push(prefixe);
  for (const entree of entrees) {
    if (entree.isDirectory() && entree.name !== 'public') resultat.push(...(await pages(path.join(dossier, entree.name), `${prefixe}${entree.name}/`)));
  }
  return resultat;
}

/** Un serveur de fichiers sur 127.0.0.1 : la page, /public/, et 404 pour le reste (une requête en échec se voit). */
function servir() {
  return new Promise((resolve) => {
    const serveur = createServer((req, res) => {
      let chemin = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
      if (chemin.endsWith('/')) chemin += 'index.html';
      const fichier = path.resolve(racine, `.${chemin}`);
      if (!fichier.startsWith(racine) || !existsSync(fichier) || !statSync(fichier).isFile()) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('introuvable');
        return;
      }
      res.writeHead(200, { 'content-type': TYPES[path.extname(fichier)] ?? 'application/octet-stream' });
      createReadStream(fichier).pipe(res);
    });
    serveur.listen(0, '127.0.0.1', () => resolve(serveur));
  });
}

/** « / » devient « accueil », « /ouvert/wiki/personnel/ » devient « ouvert-wiki-personnel » : le nom d'une capture. */
function nom(route) {
  return route === '/' ? 'accueil' : route.replace(/^\/|\/$/g, '').replace(/\//g, '-');
}

const routes = await pages(racine);
if (routes.length === 0) {
  console.error(`${options.rendu} ne contient aucun index.html.`);
  process.exit(1);
}
if (options.captures) mkdirSync(options.captures, { recursive: true });

const serveur = await servir();
const base = `http://127.0.0.1:${serveur.address().port}`;
const navigateur = await chromium.launch();
const ecarts = [];
try {
  for (const largeur of LARGEURS) {
    const contexte = await navigateur.newContext({ viewport: { width: largeur, height: 900 }, deviceScaleFactor: 1 });
    for (const route of routes) {
      const page = await contexte.newPage();
      const problemes = [];
      page.on('console', (message) => {
        if (message.type() === 'error') problemes.push(`console : ${message.text()}`);
      });
      page.on('pageerror', (erreur) => problemes.push(`erreur de script : ${erreur.message}`));
      page.on('requestfailed', (requete) => problemes.push(`requête en échec : ${requete.url()} (${requete.failure()?.errorText ?? '?'})`));
      page.on('response', (reponse) => {
        if (reponse.status() >= 400) problemes.push(`réponse ${reponse.status()} : ${reponse.url()}`);
      });
      await page.goto(base + route, { waitUntil: 'load' });
      await page.evaluate(() => document.fonts.ready);
      const mesure = await page.evaluate(() => {
        const racine = document.documentElement;
        const largeur = racine.clientWidth;
        const coupables = [];
        if (racine.scrollWidth > largeur) {
          for (const element of document.querySelectorAll('body *')) {
            const boite = element.getBoundingClientRect();
            if (boite.width === 0 || boite.right <= largeur + 1) continue;
            // Seuls les éléments les plus hauts de l'arbre : un enfant d'un élément qui déborde déjà n'apprend rien.
            const parent = element.parentElement?.getBoundingClientRect();
            if (parent && parent.right > largeur + 1) continue;
            const classes = (element.getAttribute('class') ?? '').trim().split(/\s+/).filter(Boolean).join('.');
            coupables.push(`${element.tagName.toLowerCase()}${classes ? `.${classes}` : ''} (${Math.round(boite.width)} px, bord droit à ${Math.round(boite.right)})`);
          }
        }
        const sansAlt = [...document.images].filter((image) => image.getAttribute('alt') === null).map((image) => image.getAttribute('src') ?? '?');
        return { largeur, defilement: racine.scrollWidth, coupables: coupables.slice(0, 6), sansAlt };
      });
      if (mesure.defilement > mesure.largeur) {
        problemes.push(`déborde (${mesure.defilement} px pour ${mesure.largeur}) : ${mesure.coupables.join(' ; ') || 'coupable non trouvé'}`);
      }
      for (const image of mesure.sansAlt) problemes.push(`image sans alt : ${image}`);
      if (options.captures) {
        await page.screenshot({ path: path.join(options.captures, `${nom(route)}-${largeur}.jpg`), type: 'jpeg', quality: 80, fullPage: true });
      }
      await page.close();
      for (const probleme of problemes) {
        ecarts.push(`${route} à ${largeur} px : ${probleme}`);
        console.log(`  ÉCHEC  ${route} à ${largeur} px : ${probleme}`);
      }
    }
    await contexte.close();
  }
} finally {
  await navigateur.close();
  serveur.close();
}

console.log(`${routes.length} page(s) à ${LARGEURS.join(' et ')} px : ${ecarts.length} écart(s).`);
process.exit(ecarts.length === 0 ? 0 : 1);
