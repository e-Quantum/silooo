#!/usr/bin/env node
// L'image de partage du site (og:image, 1 200 × 630) : Chromium rend partage.html avec le public/ du site rendu par les
// tests Gradle, et écrit le PNG. Elle est commise ; SiteAtelierTest contrôle sa taille et son poids.
// Usage : node tools/partage/capturer.mjs [<site rendu>] [<sortie.png>]
//   <site rendu>   le dossier qui contient public/ (défaut : core/build/site-rendu, écrit par ./gradlew test)
//   <sortie.png>   défaut : core/src/main/resources/site/public/partage.png
import { createServer } from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const ici = path.dirname(fileURLToPath(import.meta.url));
const [rendu = 'core/build/site-rendu', sortie = 'core/src/main/resources/site/public/partage.png'] = process.argv.slice(2);
const racine = path.resolve(rendu);
if (!existsSync(path.join(racine, 'public', 'site.css'))) {
  console.error(`${rendu}/public/site.css manque : lance ./gradlew test dans core/ (SiteRenderToDisk écrit le site rendu).`);
  process.exit(1);
}
const TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };

const serveur = createServer((req, res) => {
  const chemin = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
  const fichier = chemin === '/partage.html' ? path.join(ici, 'partage.html') : path.resolve(racine, `.${chemin}`);
  if (!fichier.startsWith(racine) && fichier !== path.join(ici, 'partage.html') || !existsSync(fichier) || !statSync(fichier).isFile()) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('introuvable');
    return;
  }
  res.writeHead(200, { 'content-type': TYPES[path.extname(fichier)] ?? 'application/octet-stream' });
  createReadStream(fichier).pipe(res);
});
await new Promise((resolve) => serveur.listen(0, '127.0.0.1', resolve));

const navigateur = await chromium.launch();
let manques = 0;
try {
  const page = await navigateur.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
  page.on('requestfailed', (requete) => { manques++; console.error(`requête en échec : ${requete.url()}`); });
  page.on('response', (reponse) => { if (reponse.status() >= 400) { manques++; console.error(`réponse ${reponse.status()} : ${reponse.url()}`); } });
  await page.goto(`http://127.0.0.1:${serveur.address().port}/partage.html`, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  if (manques === 0) {
    await page.screenshot({ path: sortie, type: 'png' });
    console.log(`${sortie} : ${statSync(sortie).size} octets.`);
  }
} finally {
  await navigateur.close();
  serveur.close();
}
process.exit(manques === 0 ? 0 : 1);
