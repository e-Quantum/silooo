/*
 * Oooscar vivant. Sans ce fichier, la page garde l'image PNG d'Oooscar et sa bulle : rien ne manque. Avec lui, l'image
 * cède la place au SVG articulé (oooscar.svg) et il réagit : toucher, toit qui tombe, survol, attraper et lâcher,
 * sommeil après quinze secondes, nuit de 22 h à 7 h, Vote party. Adapté de docs/references/site/oooscar-vivant.html.
 *
 * Aucune phrase n'est écrite ici : l'humeur de départ, la bulle de la page et les réactions arrivent sur les attributs
 * `data-humeur`, `data-bulle` et `data-textes` posés par Kotlin (pages/Oooscar.kt). Le SVG est le seul morceau de HTML
 * chargé par le script, et il vient de notre propre dossier /public/.
 * Par image, seuls trois styles changent (transform du porteur, du corps et de l'ombre) ; tout le reste passe par des
 * classes (`on`, `dort`, `pop`) et par l'attribut transform du toit et des pupilles.
 */
(function () {
  'use strict';

  // Le cadre du dessin, le pivot du toit et la place de l'œil, en unités du SVG.
  const VB = { x: -70.29, y: -300.42, w: 306.05, h: 317.54 };
  const PIV = { x: 92.42, y: -210.68 };
  const EYE = { x: 63.95, y: -135.0 };

  const reduit = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const PENTES = [-8, -12, -16, -20, -26];
  const CLES_TEXTES = ['toit', 'neuf', 'repare', 'attrape', 'chute', 'salut', 'reveil', 'nuit', 'voteParty'];

  function lireTextes(hote) {
    let brut = null;
    try { brut = JSON.parse(hote.dataset.textes || '{}'); } catch (_) { brut = null; }
    if (!brut || typeof brut !== 'object') brut = {};
    const textes = { touche: Array.isArray(brut.touche) ? brut.touche.filter((s) => typeof s === 'string' && s) : [] };
    for (const cle of CLES_TEXTES) textes[cle] = typeof brut[cle] === 'string' ? brut[cle] : '';
    return textes;
  }

  function animer(hote, image, bulle, svg) {
    const T = lireTextes(hote);
    const base = hote.dataset.bulle || bulle.textContent;
    const scene = hote.closest('.scene__boite') || hote.parentElement;

    // ---- la structure : porteur (déplacé), corps (écrasé), ombre, puis la bulle de la page
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('width', '100%');
    svg.setAttribute('height', '100%');
    const porte = document.createElement('div');
    porte.className = 'oooscar__porte';
    const bouton = document.createElement('button');
    bouton.type = 'button';
    bouton.className = 'oooscar__corps';
    bouton.setAttribute('aria-label', image.alt);
    bouton.appendChild(svg);
    const ombre = document.createElement('span');
    ombre.className = 'oooscar__ombre';
    ombre.setAttribute('aria-hidden', 'true');
    porte.append(bouton, ombre);

    // ---- les pièces articulées : bras gauche (g), bras droit (d), pente du toit (t), visages (v)
    const P = { g: {}, d: {}, t: {}, v: {} };
    for (const cle of ['g', 'd', 't', 'v']) {
      svg.querySelectorAll('[data-' + cle + ']').forEach((el) => { P[cle][el.dataset[cle]] = el; });
    }
    const ANGLES = {};
    for (const cle of ['g', 'd', 't']) ANGLES[cle] = Object.keys(P[cle]).map(Number);
    const toitG = svg.querySelector('#toit');
    const pupilles = svg.querySelector('.pupilles');

    // Tout est prêt : on échange l'image contre le porteur, la bulle de la page y emménage.
    image.replaceWith(porte);
    porte.appendChild(bulle);
    hote.classList.add('oooscar--vivant');

    const st = {
      mood: 'joie', armL: -35, armR: -35, tilt: -8,
      x: 0, y: 0, sx: 1, sy: 1,
      dort: false, nuit: false, busy: false, drag: false,
      chute: false, // en l'air après un lâcher, jusqu'au retour au calme
    };
    let H = 150, W = 150, k = 1;
    let minX = 0, maxX = 0, maxY = 0;

    // ---- pièces : on n'affiche que la pièce demandée, la plus proche si la valeur n'existe pas
    function show(map, cle) {
      for (const n in map) map[n].classList.toggle('on', n === String(cle));
    }
    function proche(cle, v) {
      const l = ANGLES[cle];
      return l.length ? l.reduce((a, b) => (Math.abs(b - v) < Math.abs(a - v) ? b : a)) : v;
    }
    function bras(g, d) {
      st.armL = proche('g', g); st.armR = proche('d', d);
      show(P.g, st.armL); show(P.d, st.armR);
    }
    function visage(m) { st.mood = m; show(P.v, m); }
    function toit(t) { st.tilt = proche('t', t); show(P.t, st.tilt); }
    function toitT(dx, dy, r) {
      if (!toitG) return;
      toitG.setAttribute('transform', 'translate(' + dx.toFixed(1) + ' ' + dy.toFixed(1) + ') rotate(' + r.toFixed(1) + ' ' + PIV.x + ' ' + PIV.y + ')');
    }

    // ---- mesures : la taille suit la largeur de l'écran, les pieds posent sur le sol
    function bornes() {
      minX = -hote.offsetLeft;
      maxX = Math.max(minX, scene.clientWidth - hote.offsetLeft - W);
      maxY = H * 1.3;
      st.x = Math.max(minX, Math.min(maxX, st.x));
    }
    function mesure() {
      H = window.innerWidth < 700 ? 120 : 150;
      k = H / VB.h;
      W = VB.w * k;
      for (const el of [bouton, porte]) {
        el.style.width = W + 'px';
        el.style.height = H + 'px';
      }
      porte.style.bottom = -(16 * k + 2) + 'px';
      bornes();
      redessine();
    }

    // ---- rendu : trois transformations par image, et seulement quand quelque chose bouge
    function rendu() {
      porte.style.transform = 'translate(' + st.x.toFixed(1) + 'px,' + (-st.y).toFixed(1) + 'px)';
      bouton.style.transform = 'scale(' + st.sx.toFixed(3) + ',' + st.sy.toFixed(3) + ')';
      // L'ombre vit dans le porteur : elle redescend de la hauteur du saut pour rester au sol, et rétrécit en l'air.
      const r = 1 - Math.min(st.y, 260) / 380;
      ombre.style.transform = 'translateY(' + st.y.toFixed(1) + 'px) scaleX(' + r.toFixed(3) + ')';
    }
    let planifie = false;
    function redessine() {
      if (planifie) return;
      planifie = true;
      requestAnimationFrame(() => { planifie = false; rendu(); });
    }

    // ---- outils d'animation : un compteur par canal, stop(canal) coupe tweens et délais du canal
    const canaux = {};
    const suivant = (canal) => (canaux[canal] = (canaux[canal] || 0) + 1);
    const stop = suivant;
    function tween(canal, ms, fn, fin) {
      const id = suivant(canal);
      if (reduit || ms <= 0) { fn(1); rendu(); if (fin) fin(); return; }
      const t0 = performance.now();
      (function pas(now) {
        if (canaux[canal] !== id) return;
        const t = Math.min(1, Math.max(0, now - t0) / ms);
        fn(t);
        rendu();
        if (t < 1) requestAnimationFrame(pas);
        else if (fin) fin();
      })(t0);
    }
    function plan(canal, ms, fn) {
      const id = canaux[canal] || 0;
      setTimeout(() => { if ((canaux[canal] || 0) === id) fn(); }, ms);
    }
    let flipT = null;
    function flip(images, ms, fin) {
      clearInterval(flipT);
      if (reduit) { images[images.length - 1](); if (fin) fin(); return; }
      let i = 0;
      const pas = () => {
        const f = images[i++];
        if (!f) { clearInterval(flipT); if (fin) fin(); return; }
        f();
      };
      pas();
      flipT = setInterval(pas, ms);
    }

    // ---- la bulle : elle ne disparaît jamais, elle revient à la phrase de la page
    let bulleT = null, derniere = '';
    function rejouer() {
      bulle.classList.remove('pop');
      void bulle.offsetWidth;
      bulle.classList.add('pop');
    }
    function retablir() {
      clearTimeout(bulleT);
      if (bulle.textContent !== base) bulle.textContent = base;
    }
    function dire(txt, ms = 2400) {
      if (!txt) return;
      clearTimeout(bulleT);
      derniere = txt;
      bulle.textContent = txt;
      rejouer();
      bulleT = setTimeout(retablir, ms);
    }
    function pioche(liste) {
      if (!liste.length) return '';
      let t;
      do { t = liste[Math.floor(Math.random() * liste.length)]; } while (t === derniere && liste.length > 1);
      return t;
    }

    // ---- activité, sommeil
    let derniereAct = Date.now();
    const actif = () => { derniereAct = Date.now(); };
    let zT = null;
    function zzz() {
      if (document.hidden) return;
      const z = document.createElement('span');
      z.className = 'z';
      z.textContent = 'z';
      z.setAttribute('aria-hidden', 'true');
      z.style.fontSize = Math.max(14, Math.round(H * (0.1 + Math.random() * 0.1))) + 'px';
      z.style.left = (W * 0.62) + 'px';
      z.style.bottom = (H * 0.8) + 'px';
      porte.appendChild(z);
      setTimeout(() => z.remove(), 2300);
    }
    function dormir() {
      st.dort = true;
      bouton.classList.add('dort');
      visage('dodo'); bras(-4, -4); toit(-20);
      clearInterval(zT);
      zT = setInterval(zzz, 1100);
      zzz();
    }
    function endormi() {
      st.dort = false;
      bouton.classList.remove('dort');
      clearInterval(zT);
    }
    // Annule le retour au calme prévu : une nouvelle réaction reprend la main, et la chute est finie.
    function calme() { stop('repos'); st.chute = false; }
    // Retour au calme : visage content, bras le long du corps, toit droit ; la nuit, on se rendort.
    function repos(ms) {
      stop('repos');
      plan('repos', ms, () => {
        st.chute = false;
        if (st.drag || st.busy) return;
        if (st.nuit) { if (!st.dort) dormir(); return; }
        if (st.dort) return;
        visage('joie'); bras(-35, -35); toit(-8);
      });
    }
    function reveil(texte) {
      actif();
      endormi();
      visage('panique'); bras(-150, -150); toit(-12);
      dire(texte || T.reveil);
      saut(H * 0.21, 420);
      repos(1000);
    }

    // ---- saut, avec écrasement à l'appel et à l'atterrissage
    function saut(hauteur, ms, fin) {
      tween('corps', ms, (t) => {
        if (t < 0.16) {
          const s = Math.sin(Math.PI * t / 0.16);
          st.y = 0; st.sy = 1 - 0.12 * s; st.sx = 1 + 0.08 * s;
        } else if (t < 0.86) {
          const a = (t - 0.16) / 0.7;
          st.y = hauteur * 4 * a * (1 - a); st.sy = 1.05; st.sx = 0.97;
        } else {
          const s = Math.sin(Math.PI * (t - 0.86) / 0.14);
          st.y = 0; st.sy = 1 - 0.1 * s; st.sx = 1 + 0.07 * s;
        }
        const a = Math.max(0, Math.min(1, (t - 0.16) / 0.7));
        toitT(0, -16 * Math.sin(Math.PI * a), 0);
      }, () => {
        st.y = 0; st.sx = st.sy = 1; toitT(0, 0, 0);
        if (fin) fin();
      });
    }

    // ---- toucher
    let touches = [];
    function toucher() {
      actif();
      if (st.nuit) {
        if (!st.dort && !st.busy && !st.drag) dormir();
        dire(T.nuit, 2800);
        return;
      }
      if (st.dort) { reveil(); return; }
      if (st.busy) return;
      const now = Date.now();
      touches = touches.filter((t) => now - t < 2500);
      touches.push(now);
      if (touches.length >= 5) { touches = []; toitTombe(); return; }
      calme();
      visage('joie');
      dire(pioche(T.touche));
      bras(-90, -90);
      saut(H * 0.34, 560, () => {
        bras(-35, -35);
        const i = Math.min(PENTES.indexOf(st.tilt) + 1, PENTES.length - 1);
        toit(PENTES[Math.max(i, 1)]);
        stop('rep');
        plan('rep', 2600, reparer);
      });
    }
    function reparer() {
      if (st.busy || st.dort || st.drag || st.tilt === -8) return;
      st.busy = true;
      bras(-35, -150);
      plan('rep', 320, () => { toit(-8); dire(T.repare); });
      plan('rep', 760, () => { bras(-35, -35); st.busy = false; });
    }
    function toitTombe() {
      st.busy = true;
      stop('rep'); calme(); stop('toit');
      visage('oups'); bras(-10, -120);
      dire(T.toit, 1600);
      tween('toit', 720, (t) => { toitT(130 * t, -50 * Math.sin(Math.PI * t) + 118 * t * t, 80 * t); }, () => {
        plan('toit', 1200, () => {
          visage('fier');
          tween('toit', 520, (t) => {
            const u = 1 - t;
            toitT(130 * u, -70 * Math.sin(Math.PI * t) + 118 * u * u, 80 * u);
          }, () => {
            toitT(0, 0, 0); toit(-8); bras(-62, -62);
            dire(T.neuf);
            plan('toit', 900, () => { bras(-35, -35); visage('joie'); st.busy = false; });
          });
        });
      });
    }

    // ---- survol à la souris : un salut, puis six secondes de répit
    let dernierSalut = 0;
    bouton.addEventListener('pointerenter', (e) => {
      if (e.pointerType !== 'mouse' || st.busy || st.dort || st.nuit || st.drag || st.chute) return;
      const now = Date.now();
      if (now - dernierSalut < 6000) return;
      dernierSalut = now;
      actif();
      dire(T.salut, 1600);
      const R = (a) => () => bras(st.armL, a);
      flip([R(-62), R(-90), R(-120), R(-90), R(-120), R(-90), R(-62), R(-35)], 90);
    });

    // ---- attraper et lâcher (seuil de 8 px, pour ne pas confondre avec un toucher)
    let bas = null, glisse = false, sansClic = false, agiteT = null;
    bouton.addEventListener('pointerdown', (e) => {
      if (e.button > 0) return;
      bornes();
      bas = { x: e.clientX, y: e.clientY, ox: st.x, oy: st.y, id: e.pointerId };
      glisse = false;
    });
    bouton.addEventListener('pointermove', (e) => {
      if (!bas || e.pointerId !== bas.id) return;
      const dx = e.clientX - bas.x, dy = e.clientY - bas.y;
      if (!glisse && Math.hypot(dx, dy) > 8) {
        glisse = true;
        try { bouton.setPointerCapture(bas.id); } catch (_) { /* le glisser marche aussi sans capture */ }
        attraper();
      }
      if (glisse) {
        st.x = Math.max(minX, Math.min(maxX, bas.ox + dx));
        st.y = Math.max(0, Math.min(maxY, bas.oy - dy));
        redessine();
      }
    });
    function relache(e) {
      if (!bas || (e && e.pointerId !== bas.id)) return;
      const etaitGlisse = glisse;
      bas = null; glisse = false;
      if (!etaitGlisse) return;
      // Le clic qui suit un glisser ne compte pas comme un toucher.
      sansClic = true;
      setTimeout(() => { sansClic = false; }, 300);
      lacher();
    }
    bouton.addEventListener('pointerup', relache);
    bouton.addEventListener('pointercancel', relache);
    bouton.addEventListener('click', () => {
      if (sansClic) { sansClic = false; return; }
      toucher();
    });

    function attraper() {
      actif();
      stop('corps'); stop('toit'); stop('rep'); stop('repos');
      clearInterval(flipT);
      toitT(0, 0, 0);
      if (st.dort) endormi();
      st.drag = true; st.busy = false; st.chute = false; st.sx = st.sy = 1;
      visage('panique'); toit(-12);
      dire(T.attrape, 4000);
      clearInterval(agiteT);
      if (reduit) bras(-150, -150);
      else {
        let a = false;
        agiteT = setInterval(() => { a = !a; bras(a ? -150 : -120, a ? -120 : -150); }, 150);
      }
    }
    function lacher() {
      clearInterval(agiteT);
      st.drag = false; st.chute = true;
      actif();
      const h0 = st.y;
      if (reduit || h0 <= 0) { st.y = 0; rendu(); atterrir(h0); return; }
      const g = 2600 * H / 250; // la gravité du prototype, ramenée à la taille d'Oooscar
      const id = suivant('corps');
      let v = 0, t0 = performance.now();
      (function tombe(now) {
        if (canaux.corps !== id) return;
        const dt = Math.min(0.04, Math.max(0, now - t0) / 1000);
        t0 = now;
        v += g * dt;
        st.y -= v * dt;
        if (st.y <= 0) { st.y = 0; rendu(); atterrir(h0); return; }
        rendu();
        requestAnimationFrame(tombe);
      })(t0);
    }
    function atterrir(h0) {
      if (h0 > 50) {
        visage('oups'); bras(-10, -10); toit(-16);
        dire(T.chute, 1400);
        tween('corps', 300, (t) => {
          const s = Math.sin(Math.PI * t);
          st.sy = 1 - 0.16 * s; st.sx = 1 + 0.1 * s;
        }, () => { st.sx = st.sy = 1; });
        repos(1200);
      } else {
        retablir();
        repos(200);
      }
    }

    // ---- clignement (humeur joie seulement) et regard
    (function cligne() {
      setTimeout(() => {
        if (st.mood === 'joie' && !st.drag && !st.dort && !document.hidden) {
          show(P.v, 'cligne');
          setTimeout(() => { if (st.mood === 'joie') show(P.v, 'joie'); }, 130);
        }
        cligne();
      }, 2400 + Math.random() * 3200);
    })();

    if (pupilles && !reduit) {
      let px = 0, py = 0, regarde = false;
      const suivre = () => {
        regarde = false;
        if (st.mood !== 'joie' || st.dort) return;
        const r = bouton.getBoundingClientRect();
        const ex = r.left + (EYE.x - VB.x) / VB.w * r.width;
        const ey = r.top + (EYE.y - VB.y) / VB.h * r.height;
        const dx = px - ex, dy = py - ey;
        const d = Math.hypot(dx, dy) || 1;
        const f = Math.min(1, d / 160);
        pupilles.setAttribute('transform', 'translate(' + (dx / d * 0.7 * f).toFixed(2) + ' ' + (dy / d * 0.55 * f).toFixed(2) + ')');
      };
      window.addEventListener('pointermove', (e) => {
        px = e.clientX; py = e.clientY;
        if (!regarde) { regarde = true; requestAnimationFrame(suivre); }
      }, { passive: true });
    }

    // ---- nuit : de 22 h à 7 h à l'heure du joueur, il dort sur toutes les pages
    const estNuit = () => { const h = new Date().getHours(); return h >= 22 || h < 7; };
    function verifierNuit() {
      const nuit = estNuit();
      if (nuit === st.nuit) return;
      st.nuit = nuit;
      if (!nuit && st.dort) reveil(T.reveil);
    }
    setInterval(verifierNuit, 60000);

    // ---- la veille : la nuit, ou quinze secondes sans rien, et il s'endort
    setInterval(() => {
      if (st.dort || st.busy || st.drag || st.chute) return;
      if (st.nuit || Date.now() - derniereAct > 15000) dormir();
    }, 1000);

    // ---- la Vote party atteinte (évènement envoyé par direct.js)
    document.addEventListener('silooo:voteparty', () => {
      if (st.dort || st.busy || st.drag || st.chute) { dire(T.voteParty, 3200); return; }
      calme();
      visage('fier'); bras(-120, -120);
      dire(T.voteParty, 3200);
      saut(H * 0.34, 560, () => repos(1200));
    });

    window.addEventListener('resize', mesure);

    // ---- départ : l'humeur de la page, sa bulle déjà en place (on lui donne seulement son animation d'arrivée)
    const FACES = { joie: [-35, -35, -8], fier: [-62, -62, -8], oups: [-4, -4, -16], panique: [-150, -150, -12] };
    const humeur = hote.dataset.humeur;
    const face = FACES[humeur] || FACES.joie;
    visage(FACES[humeur] ? humeur : 'joie'); bras(face[0], face[1]); toit(face[2]);
    st.nuit = estNuit();
    mesure();
    rejouer();
    if (humeur === 'dodo' || st.nuit) dormir();
  }

  function demarrer() {
    const hote = document.querySelector('.oooscar[data-humeur]');
    if (!hote || hote.classList.contains('oooscar--vivant')) return;
    const image = hote.querySelector('img.sa-oooscar');
    const bulle = hote.querySelector('.sa-bulle');
    if (!image || !bulle) return;

    fetch('/public/oooscar.svg')
      .then((reponse) => {
        if (!reponse.ok) throw new Error('statut ' + reponse.status);
        return reponse.text();
      })
      .then((texte) => {
        const doc = new DOMParser().parseFromString(texte, 'image/svg+xml');
        if (doc.querySelector('parsererror')) return;
        const svg = doc.querySelector('svg');
        if (!svg) return;
        animer(hote, image, bulle, document.importNode(svg, true));
      })
      .catch(() => { /* sans le SVG, l'image PNG et la bulle restent */ });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', demarrer, { once: true });
  else demarrer();
})();
