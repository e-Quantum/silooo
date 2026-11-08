// L'île de la page d'attente (/public/ile/carriere.js ; ile.js reste le nom de l'île qui se construit de la vitrine, R29) : la Carrière en marche, en 3D, qui tourne lentement sur elle-même.
//
// Repris de l'aperçu 3D de la Carrière de l'atelier (« Carrière Silooo, aperçu 3D ») : les mêmes blocs, les mêmes
// modèles, animations et particules du pack de ressources, lus par le même petit lecteur Bedrock. Ce qui n'a pas sa
// place sur une page d'attente est parti : les commandes, la cinématique, les sons, la coupe, les repères, la ruine.
// Les données viennent de carriere.json et les expressions Molang de molang.js, à côté de ce fichier, écrits par
// tools/site_ile.mjs ; three.js (MIT, three-LICENSE.txt) est servi par le site, la politique de sécurité n'admet rien d'un autre hôte.
//
// Rien ne se charge sur téléphone : l'île n'y est pas affichée (atelier.css), et le script attend qu'elle le soit.
// Sans WebGL, ou si quoi que ce soit échoue, l'image fixe de l'île (carriere.png, rendue depuis cette scène) reste.
// Avec « réduire les animations », la scène est rendue une fois, immobile. Hors de l'écran ou onglet caché : rien ne tourne.

const boite = document.querySelector(".ile");
const ICI = new URL(".", import.meta.url);

if (boite) {
  const large = matchMedia("(min-width: 700px)");
  const lancer = () => {
    large.removeEventListener("change", surChangement);
    demarrer(boite).catch((e) => { boite.classList.remove("ile--vivante"); console.warn("Île 3D : l'image fixe reste.", e); });
  };
  const surChangement = (e) => { if (e.matches) lancer(); };
  if (large.matches) lancer(); else large.addEventListener("change", surChangement);
}

function webgl() {
  try { const c = document.createElement("canvas"); return !!(c.getContext("webgl2") || c.getContext("webgl")); } catch { return false; }
}

async function demarrer(boite) {
  if (!webgl()) return;
  const [THREE, { default: MOLANG }, DATA] = await Promise.all([
    import(new URL("three.module.min.js", ICI).href),
    import(new URL("molang.js", ICI).href),
    fetch(new URL("carriere.json", ICI)).then((r) => { if (!r.ok) throw new Error("carriere.json " + r.status); return r.json(); }),
  ]);
  const MOD = DATA.modeles, pal = DATA.palette;
  const reduit = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const OPAQUE = new Set(["cube", "leaves", "path", "chest", "stairs"]);
  const FPS_FLIPBOOK = 8, DEG = Math.PI / 180;
  const key = (x, y, z) => x + "," + y + "," + z;

  // ---------- Blocs ----------
  const entries = [], occ = new Map();
  for (let i = 0; i < DATA.blocs.length; i += 4) {
    const [x, y, z, m] = DATA.blocs.slice(i, i + 4);
    entries.push({ x, y, z, m }); occ.set(key(x, y, z), m);
  }

  // ---------- Textures : les PNG du pack, sans lissage ----------
  const flipbooks = [], texCache = new Map();
  function texture(src, nom) {
    const img = new Image(); img.src = src;
    const t = new THREE.Texture(img);
    img.onload = () => { t.needsUpdate = true; };
    t.magFilter = t.minFilter = THREE.NearestFilter; t.generateMipmaps = false; t.colorSpace = THREE.SRGBColorSpace;
    const n = (nom && DATA.images[nom]) || 1;
    if (n > 1) { t.repeat.set(1, 1 / n); t.offset.set(0, 1 - 1 / n); flipbooks.push({ t, n }); }
    return t;
  }
  function tex(nom) { if (!texCache.has(nom)) texCache.set(nom, texture(DATA.textures[nom], nom)); return texCache.get(nom); }
  const FACES = ["px", "nx", "py", "ny", "pz", "nz"];
  function faceName(b, f) {
    const fc = b.faces;
    if (fc[f]) return fc[f];
    if (f === "py") return fc.top || fc.all || fc.side;
    if (f === "ny") return fc.bottom || fc.all || fc.side;
    return fc.side || fc.all;
  }
  const matCache = new Map();
  function material(i) {
    if (matCache.has(i)) return matCache.get(i);
    const b = pal[i];
    const mk = (nom) => {
      const o = { map: tex(nom) };
      if (b.forme === "pane") { o.transparent = true; o.depthWrite = false; }
      if (["leaves", "rail", "cross", "conveyor"].includes(b.forme) || nom.includes("dechire")) { o.alphaTest = 0.5; o.side = THREE.DoubleSide; }
      if (b.forme === "lantern") { o.emissive = new THREE.Color("#e07a2b"); o.emissiveIntensity = 0.55; }
      return new THREE.MeshLambertMaterial(o);
    };
    const noms = FACES.map((f) => faceName(b, f));
    const m = noms.every((n) => n === noms[0]) ? mk(noms[0]) : noms.map(mk);
    matCache.set(i, m); return m;
  }

  // ---------- Formes ----------
  function box(w, h, d, oy = 0) { const g = new THREE.BoxGeometry(w, h, d); g.translate(0, oy, 0); return g; }
  // Fusionne des géométries indexées (position, normale, uv) en une seule : ce que mergeGeometries de three fait pour
  // l'aperçu, sans charger le module complémentaire.
  function fusion(geos) {
    const attrs = { position: [], normal: [], uv: [] }, index = [];
    let base = 0;
    for (const g of geos) {
      for (const k in attrs) attrs[k].push(...g.attributes[k].array);
      for (const i of g.index.array) index.push(i + base);
      base += g.attributes.position.count;
    }
    const r = new THREE.BufferGeometry();
    r.setAttribute("position", new THREE.Float32BufferAttribute(attrs.position, 3));
    r.setAttribute("normal", new THREE.Float32BufferAttribute(attrs.normal, 3));
    r.setAttribute("uv", new THREE.Float32BufferAttribute(attrs.uv, 2));
    r.setIndex(index); return r;
  }
  function crossGeo() {
    const g = new THREE.BufferGeometry(), s = 0.45;
    g.setAttribute("position", new THREE.Float32BufferAttribute([-s, -0.5, -s, s, -0.5, s, s, 0.5, s, -s, 0.5, -s, -s, -0.5, s, s, -0.5, -s, s, 0.5, -s, -s, 0.5, s], 3));
    g.setAttribute("uv", new THREE.Float32BufferAttribute([0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1], 2));
    g.setIndex([0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7]); g.computeVertexNormals(); return g;
  }
  const GEO = {
    cube: box(1, 1, 1), leaves: box(1, 1, 1), slab: box(1, 0.5, 1, -0.25), path: box(1, 15 / 16, 1, -1 / 32), rail: box(1, 1 / 16, 1, -15 / 32),
    post: box(0.25, 1, 0.25), lantern: box(6 / 16, 7 / 16, 6 / 16, -9 / 32), pane: box(1, 1, 2 / 16), door: box(1, 1, 3 / 16),
    // bras de barrière vers le nord (-z), deux lisses comme en jeu : on les tourne vers chaque voisin
    bras: (() => { const a = box(2 / 16, 3 / 16, 0.5, -0.5 + 7.5 / 16), b = box(2 / 16, 3 / 16, 0.5, -0.5 + 13.5 / 16); a.translate(0, 0, -0.25); b.translate(0, 0, -0.25); return fusion([a, b]); })(),
    stairs: (() => { const haut = box(1, 0.5, 0.5, 0.25); haut.translate(0, 0, -0.25); return fusion([box(1, 0.5, 1, -0.25), haut]); })(),
    sign: box(14 / 16, 8 / 16, 1 / 16), chest: box(14 / 16, 14 / 16, 14 / 16, -1 / 16), conveyor: box(1, 6 / 16, 1, -5 / 16), cross: crossGeo(),
  };
  const NO_SHADOW = new Set(["cross", "pane", "lantern", "rail"]);

  // ---------- Scène ----------
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setClearColor(0x000000, 0);
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.domElement.className = "ile__scene";
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(30, 540 / 460, 0.3, 500);
  const target = new THREE.Vector3(DATA.taille.x / 2, DATA.sol + 1, DATA.taille.z / 2);
  scene.add(new THREE.HemisphereLight("#e4f0f7", "#5b5853", 1.9));
  const sun = new THREE.DirectionalLight("#fff6de", 2.3);
  sun.target.position.copy(target);
  sun.castShadow = true; sun.shadow.mapSize.set(1024, 1024);
  Object.assign(sun.shadow.camera, { left: -26, right: 26, top: 26, bottom: -26, near: 1, far: 150 });
  sun.shadow.bias = -0.0006; sun.shadow.normalBias = 0.03;
  scene.add(sun, sun.target);

  const groupe = new THREE.Group(); scene.add(groupe);
  const M4 = new THREE.Matrix4(), Q = new THREE.Quaternion(), V = new THREE.Vector3(), S1 = new THREE.Vector3(1, 1, 1), YAX = new THREE.Vector3(0, 1, 0);
  const opaqueAt = (x, y, z) => { const p = occ.get(key(x, y, z)); return p !== undefined && OPAQUE.has(pal[p].forme); };
  const isTapis = (x, y, z) => { const p = occ.get(key(x, y, z)); return p !== undefined && pal[p].forme === "conveyor"; };

  const by = new Map();
  for (const e of entries) { if (!by.has(e.m)) by.set(e.m, []); by.get(e.m).push(e); }
  for (const [i, list] of by) {
    const b = pal[i], mesh = new THREE.InstancedMesh(GEO[b.forme] || GEO.cube, material(i), list.length);
    list.forEach((e, n) => {
      let rot = 0, ox = 0, oz = 0;
      if (b.forme === "pane" || b.forme === "door") { if (opaqueAt(e.x, e.y, e.z + 1) || opaqueAt(e.x, e.y, e.z - 1)) rot = Math.PI / 2; }
      // la toile défile vers -z dans la texture : demi-tour sur le tronçon nord-sud, quart de tour vers l'ouest
      if (b.forme === "conveyor") rot = (isTapis(e.x + 1, e.y, e.z) || isTapis(e.x - 1, e.y, e.z)) ? Math.PI / 2 : Math.PI;
      // escalier : la marche haute est au nord dans la géométrie, on la tourne vers « monte »
      if (b.forme === "stairs") rot = { nord: 0, sud: Math.PI, est: -Math.PI / 2, ouest: Math.PI / 2 }[b.id.match(/monte=(\w+)/)[1]];
      if (b.forme === "sign") {
        if (opaqueAt(e.x - 1, e.y, e.z)) { rot = Math.PI / 2; ox = -7 / 16; } else if (opaqueAt(e.x + 1, e.y, e.z)) { rot = Math.PI / 2; ox = 7 / 16; }
        else if (opaqueAt(e.x, e.y, e.z - 1)) oz = -7 / 16; else oz = 7 / 16;
      }
      Q.setFromAxisAngle(YAX, rot); V.set(e.x + 0.5 + ox, e.y + 0.5, e.z + 0.5 + oz);
      mesh.setMatrixAt(n, M4.compose(V, Q, S1));
    });
    mesh.castShadow = !NO_SHADOW.has(b.forme); mesh.receiveShadow = true;
    groupe.add(mesh);
  }
  // barrières : un bras vers chaque voisin barrière ou bloc plein, comme Minecraft les relie
  const iF = pal.findIndex((q) => q.id === "minecraft:spruce_fence"), bras = [];
  for (const e of entries) {
    if (e.m !== iF) continue;
    for (const [dx, dz, r] of [[0, -1, 0], [0, 1, Math.PI], [1, 0, -Math.PI / 2], [-1, 0, Math.PI / 2]]) {
      const n = occ.get(key(e.x + dx, e.y, e.z + dz));
      if (n !== undefined && (n === iF || OPAQUE.has(pal[n].forme))) bras.push([e, r]);
    }
  }
  if (bras.length) {
    const mesh = new THREE.InstancedMesh(GEO.bras, material(iF), bras.length);
    bras.forEach(([e, r], n) => { Q.setFromAxisAngle(YAX, r); V.set(e.x + 0.5, e.y + 0.5, e.z + 0.5); mesh.setMatrixAt(n, M4.compose(V, Q, S1)); });
    mesh.castShadow = true; mesh.receiveShadow = true; groupe.add(mesh);
  }

  // ---------- Matériaux simples des wagonnets et de la charrette ----------
  const MB = {
    bois: new THREE.MeshLambertMaterial({ map: tex("sapin") }), boisClair: new THREE.MeshLambertMaterial({ map: tex("planches") }),
    pierres: new THREE.MeshLambertMaterial({ map: tex("pierres") }), fonte: new THREE.MeshLambertMaterial({ color: "#3a3633" }),
  };
  function cube(w, h, d, mat, x = 0, y = 0, z = 0) { const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; return m; }
  const snap = (v) => Math.round(v * 16) / 16; // déplacement entier : un pixel de texture

  // ---------- Modèles, animations et particules Bedrock (le lecteur de l'aperçu) ----------
  const MH = { sin: (x) => Math.sin(x * DEG), cos: (x) => Math.cos(x * DEG), floor: Math.floor, min: Math.min, max: Math.max, abs: Math.abs, pow: Math.pow, sqrt: Math.sqrt, mod: (a, b) => a % b };
  function mol(e) {
    if (typeof e === "number" || typeof e === "boolean") return () => e;
    if (/^-?\d+(\.\d+)?$/.test(String(e))) { const n = parseFloat(e); return () => n; }
    const f = MOLANG[e];
    if (!f) throw new Error("expression Molang absente de molang.js : " + e);
    return (v) => f(MH, v);
  }
  function canal(ch) {
    if (typeof ch !== "object" || Array.isArray(ch)) { const fs = (Array.isArray(ch) ? ch : [ch, ch, ch]).map(mol); return (t) => fs.map((f) => f({ t })); }
    const keys = Object.entries(ch).map(([k, v]) => ({ t: parseFloat(k), pre: Array.isArray(v) ? v : (v.pre || v.post), post: Array.isArray(v) ? v : (v.post || v.pre) })).sort((a, b) => a.t - b.t);
    return (t) => {
      if (t <= keys[0].t) return keys[0].pre;
      for (let i = 0; i < keys.length - 1; i++) { const a = keys[i], b = keys[i + 1]; if (t < b.t) { const u = (t - a.t) / (b.t - a.t); return a.post.map((x, j) => x + (b.pre[j] - x) * u); } }
      return keys[keys.length - 1].post;
    };
  }
  const animCache = new Map();
  function banim(id) {
    if (animCache.has(id)) return animCache.get(id);
    const a = MOD.anims[id], bones = {};
    for (const [b, chs] of Object.entries(a.bones || {})) { bones[b] = {}; for (const c of ["rotation", "position", "scale"]) if (chs[c] !== undefined) bones[b][c] = canal(chs[c]); }
    const liste = (o) => Object.entries(o || {}).map(([k, v]) => ({ t: parseFloat(k), list: [].concat(v) })).sort((x, y) => x.t - y.t);
    const r = { len: a.animation_length || 1, bones, fx: liste(a.particle_effects) };
    animCache.set(id, r); return r;
  }
  function ajoute(pose, a, t) {
    for (const [b, chs] of Object.entries(a.bones)) {
      const p = pose[b] || (pose[b] = { r: [0, 0, 0], p: [0, 0, 0], s: [1, 1, 1] });
      if (chs.rotation) { const v = chs.rotation(t); for (let i = 0; i < 3; i++) p.r[i] += v[i]; }
      if (chs.position) { const v = chs.position(t); for (let i = 0; i < 3; i++) p.p[i] += v[i]; }
      if (chs.scale) { const v = chs.scale(t); for (let i = 0; i < 3; i++) p.s[i] *= v[i]; }
    }
  }
  // Convention de Blockbench pour Bedrock : x du modèle en miroir, rotation (-x, -y, z), ordre ZYX.
  function appliquePose(os, pose) {
    for (const [nom, g] of Object.entries(os)) {
      const p = pose[nom], r = p ? p.r : [0, 0, 0], q = p ? p.p : [0, 0, 0], sc = p ? p.s : [1, 1, 1], o = g.userData.rot;
      g.rotation.set(-(o[0] + r[0]) * DEG, -(o[1] + r[1]) * DEG, (o[2] + r[2]) * DEG, "ZYX");
      g.position.set(g.userData.rest.x - q[0], g.userData.rest.y + q[1], g.userData.rest.z + q[2]);
      g.scale.set(Math.max(sc[0], 1e-4), Math.max(sc[1], 1e-4), Math.max(sc[2], 1e-4));
    }
  }
  const texModeles = new Map(), matModeles = new Map();
  function texM(nom) { if (!texModeles.has(nom)) texModeles.set(nom, texture(MOD.tex[nom])); return texModeles.get(nom); }
  function matM(nom) { if (!matModeles.has(nom)) matModeles.set(nom, new THREE.MeshLambertMaterial({ map: texM(nom), alphaTest: 0.5 })); return matModeles.get(nom); }
  const FACES_B = ["west", "east", "up", "down", "south", "north"]; // +x, -x, +y, -y, +z, -z de three, une fois x en miroir
  function boiteB(c, tw, th) {
    const g = new THREE.BoxGeometry(Math.max(c.size[0], 0.01), Math.max(c.size[1], 0.01), Math.max(c.size[2], 0.01));
    if (c.uv && typeof c.uv === "object" && !Array.isArray(c.uv)) {
      const uv = g.attributes.uv;
      FACES_B.forEach((f, i) => {
        const fu = c.uv[f]; if (!fu) return;
        const [u, v] = fu.uv, [w, h] = fu.uv_size, u0 = u / tw, u1 = (u + w) / tw, v0 = 1 - v / th, v1 = 1 - (v + h) / th;
        uv.setXY(i * 4, u0, v0); uv.setXY(i * 4 + 1, u1, v0); uv.setXY(i * 4 + 2, u0, v1); uv.setXY(i * 4 + 3, u1, v1);
      });
    }
    return g;
  }
  function modele(geoId, mat) {
    const geo = MOD.geos[geoId], os = {}, piv = {}, racine = new THREE.Group();
    const tw = geo.description.texture_width || 16, th = geo.description.texture_height || 16;
    for (const b of geo.bones) {
      const g = new THREE.Group(), p = b.pivot || [0, 0, 0], pp = b.parent ? piv[b.parent] : [0, 0, 0];
      g.position.set(-(p[0] - pp[0]), p[1] - pp[1], p[2] - pp[2]); g.userData.rest = g.position.clone();
      const o = b.rotation || [0, 0, 0]; g.userData.rot = o; g.rotation.set(-o[0] * DEG, -o[1] * DEG, o[2] * DEG, "ZYX");
      for (const c of b.cubes || []) {
        const m = new THREE.Mesh(boiteB(c, tw, th), mat); m.castShadow = true; m.receiveShadow = true;
        m.position.set(-(c.origin[0] + c.size[0] / 2 - p[0]), c.origin[1] + c.size[1] / 2 - p[1], c.origin[2] + c.size[2] / 2 - p[2]);
        g.add(m);
      }
      for (const [nom, l] of Object.entries(b.locators || {})) { const lo = new THREE.Object3D(); lo.position.set(-(l[0] - p[0]), l[1] - p[1], l[2] - p[2]); g.add(lo); os["@" + nom] = lo; }
      os[b.name] = g; piv[b.name] = p; (b.parent ? os[b.parent] : racine).add(g);
    }
    const echelle = new THREE.Group(); echelle.scale.setScalar(1 / 16); echelle.add(racine);
    return { objet: echelle, racine, os: Object.fromEntries(Object.entries(os).filter(([k]) => k[0] !== "@")), locs: Object.fromEntries(Object.entries(os).filter(([k]) => k[0] === "@").map(([k, v]) => [k.slice(1), v])) };
  }
  // Particules : émetteurs Bedrock réduits à ce que ces fichiers utilisent ; positions en blocs
  const emetteurs = [];
  const PLAN = new THREE.PlaneGeometry(1, 1);
  class Emetteur {
    constructor(id, pos, vars = {}) {
      this.c = MOD.particles[id].particle_effect.components; this.pos = pos.clone(); this.vars = vars;
      this.age = 0; this.parts = []; this.cumul = 0; this.fini = false;
      const inst = this.c["minecraft:emitter_rate_instant"];
      if (inst) for (let i = 0, n = mol(inst.num_particles)(vars); i < n; i++) this.naitre();
    }
    naitre() {
      const v = Object.assign({ age: 0, r1: Math.random(), r2: Math.random(), r3: Math.random(), r4: Math.random() }, this.vars);
      v.life = mol(this.c["minecraft:particle_lifetime_expression"].max_lifetime)(v);
      const map = texM("particules").clone(); map.needsUpdate = true; map.repeat.set(8 / 64, 8 / 64);
      const mesh = new THREE.Mesh(PLAN, new THREE.MeshBasicMaterial({ map, alphaTest: 0.5, side: THREE.DoubleSide }));
      scene.add(mesh); this.parts.push({ v, mesh, map });
    }
    pas(dt) {
      this.age += dt;
      const st = this.c["minecraft:emitter_rate_steady"], vie = this.c["minecraft:emitter_lifetime_once"];
      if (st && this.age < vie.active_time) { this.cumul += mol(st.spawn_rate)(this.vars) * dt; while (this.cumul >= 1) { this.cumul -= 1; if (this.parts.length < mol(st.max_particles)(this.vars)) this.naitre(); } }
      const pm = this.c["minecraft:particle_motion_parametric"].relative_position.map(mol), bb = this.c["minecraft:particle_appearance_billboard"];
      for (const p of this.parts) {
        p.v.age += dt; const v = p.v;
        p.mesh.position.set(this.pos.x + pm[0](v), this.pos.y + pm[1](v), this.pos.z + pm[2](v));
        const sz = mol(bb.size[0])(v) * 2; p.mesh.visible = sz > 0; p.mesh.scale.set(sz || 1, sz || 1, 1);
        let u, w;
        if (bb.uv.flipbook) {
          const f = bb.uv.flipbook;
          let k = f.stretch_to_lifetime ? Math.floor(v.age / v.life * f.max_frame) : Math.floor(v.age * f.frames_per_second);
          k = f.loop ? k % f.max_frame : Math.min(k, f.max_frame - 1);
          u = f.base_UV[0] + f.step_UV[0] * k; w = f.base_UV[1] + f.step_UV[1] * k;
        } else { u = mol(bb.uv.uv[0])(v); w = mol(bb.uv.uv[1])(v); }
        p.map.offset.set(u / 64, 1 - (w + 8) / 64); p.mesh.quaternion.copy(camera.quaternion);
      }
      for (const p of this.parts) if (p.v.age >= p.v.life) { scene.remove(p.mesh); p.mesh.material.dispose(); p.map.dispose(); }
      this.parts = this.parts.filter((p) => p.v.age < p.v.life);
      if (this.parts.length === 0 && this.age > (vie ? vie.active_time : 0)) this.fini = true;
    }
  }
  function emettre(id, pos, vars) { if (!reduit && MOD.particles[id]) emetteurs.push(new Emetteur(id, pos, vars)); }
  // Une entité animée : ses animations et ses effets aux locators
  function entite(m, ids, effets) { return { m, anims: ids.map(banim), prec: ids.map(() => 0), temps: 0, effets }; }
  function joueEntite(e, dt) {
    e.temps += dt;
    const pose = {};
    e.anims.forEach((a, i) => {
      const tt = e.temps % a.len, pr = e.prec[i];
      ajoute(pose, a, tt);
      const passe = (x) => (tt >= pr && x > pr && x <= tt) || (tt < pr && (x > pr || x <= tt));
      if (dt > 0) {
        e.m.objet.updateMatrixWorld(true);
        for (const f of a.fx) if (passe(f.t)) for (const x of f.list) { const l = e.m.locs[x.locator], id = (e.effets[x.effect] || "").replace("silooo:", ""); if (l) emettre(id, l.getWorldPosition(new THREE.Vector3()), {}); }
      }
      e.prec[i] = tt;
    });
    appliquePose(e.m.os, pose);
  }
  function machine(nom) {
    const d = MOD.machines.entites[nom]["minecraft:client_entity"].description, vars = { ruine: 0 };
    const m = modele(d.geometry.default, matM("machines"));
    m.racine.rotation.y = Math.PI; // entité tournée vers le sud (yaw 0)
    for (const r of MOD.machines.rc[d.render_controllers[0]].part_visibility || []) { const [k, c] = Object.entries(r)[0]; if (k !== "*" && m.os[k]) m.os[k].visible = c === true || !!mol(c)(vars); }
    const ids = d.scripts.animate.map((x) => (typeof x === "string" ? [x, true] : Object.entries(x)[0])).filter(([, c]) => c === true || mol(c)(vars)).map(([n]) => d.animations[n]);
    return entite(m, ids, d.particle_effects || {});
  }

  // ---------- Entités : ce que le pack de ressources anime, la Carrière en marche ----------
  const E = DATA.entites, anim = { ailes: null, pilons: null, pierres: [], vente: null, salaries: [], oooscar: null, fumee: 8 };
  { const e = machine("ailes_moulin"); e.m.objet.position.set(...E.ailes.moyeu); groupe.add(e.m.objet); anim.ailes = e; joueEntite(e, 0); }
  { const PL = E.pilons, e = machine("pilons"); e.m.objet.position.set(PL.x[1] + 0.5, PL.bas, PL.z + 0.5); groupe.add(e.m.objet); anim.pilons = e; joueEntite(e, 0); }
  for (const w of E.wagonnets) {
    const g = new THREE.Group();
    g.add(cube(0.95, 0.5, 0.72, MB.boisClair, 0, 0.35, 0));
    g.add(cube(0.98, 0.08, 0.75, MB.fonte, 0, 0.58, 0));
    for (const [dx, dz] of [[-0.3, -0.38], [0.3, -0.38], [-0.3, 0.38], [0.3, 0.38]]) g.add(cube(0.22, 0.22, 0.06, MB.fonte, dx, 0.11, dz));
    if (!w.renverse) g.add(cube(0.7, 0.2, 0.5, MB.pierres, 0, 0.66, 0));
    g.position.set(w.x + 0.5, w.y, w.z + 0.5);
    groupe.add(g);
  }
  if (E.pierres) {
    const pts = E.pierres.map(([x, y, z]) => new THREE.Vector3(x, y, z));
    const seg = []; let L = 0; for (let i = 1; i < pts.length; i++) { const l = pts[i].distanceTo(pts[i - 1]); seg.push(l); L += l; }
    for (let k = 0; k < 8; k++) { const m = cube(0.28, 0.28, 0.28, MB.pierres); m.rotation.y = k * 0.7; groupe.add(m); anim.pierres.push({ m, k, pts, seg, L }); }
  }
  if (E.coffre) anim.vente = { pos: new THREE.Vector3(...DATA.emetteurs.vente_coffre), prec: 0 };
  const CH = E.charrette;
  if (CH) {
    const g = new THREE.Group();
    g.add(cube(1.8, 0.12, 1.0, MB.boisClair, 0, 0.55, 0));
    for (const dz of [-0.5, 0.5]) g.add(cube(1.8, 0.35, 0.08, MB.bois, 0, 0.78, dz));
    for (const dz of [-0.62, 0.62]) { g.add(cube(0.7, 0.7, 0.1, MB.bois, 0, 0.35, dz)); g.add(cube(0.16, 0.16, 0.14, MB.fonte, 0, 0.35, dz)); }
    g.add(cube(1.4, 0.08, 0.08, MB.bois, 1.5, 0.45, -0.3)); g.add(cube(1.4, 0.08, 0.08, MB.bois, 1.5, 0.45, 0.3));
    if (!CH.renverse) g.add(cube(1.2, 0.3, 0.7, MB.pierres, -0.1, 0.78, 0));
    g.position.set(CH.x + 0.5, CH.y, CH.z + 0.5); g.rotation.y = 0.4;
    groupe.add(g);
  }
  E.salaries.forEach((sal, k) => {
    const pioche = sal.geste === "pioche";
    const m = modele(pioche ? "geometry.silooo.salarie" : "geometry.silooo.salarie.pelle", matM(k === 0 ? "salarie_commun" : "salarie_rare"));
    if (m.os.charge) m.os.charge.visible = false;
    m.objet.position.set(sal.x + 0.5, sal.y, sal.z + 0.5); m.objet.rotation.y = pioche ? 0 : -Math.PI / 2; // face au front, ou face à l'auge
    groupe.add(m.objet);
    const e = entite(m, [pioche ? "animation.silooo_salarie.piocher" : "animation.silooo_salarie.pelleter"], MOD.salarieEffets);
    e.temps = k * 0.37; joueEntite(e, 0); anim.salaries.push(e);
  });
  // Oooscar : le modèle du pack, tourné vers l'apparition du joueur, balancement en continu et joie à chaque vente
  const apparition = DATA.reperes.find((r) => r.id === "apparition"), osc = DATA.reperes.find((r) => r.id === "oooscar");
  if (osc) {
    const m = modele("geometry.silooo_oooscar", matM("oooscar"));
    m.objet.position.set(osc.x, osc.y, osc.z);
    if (apparition) m.objet.rotation.y = Math.atan2(apparition.x - osc.x, apparition.z - osc.z) + Math.PI + 0.6;
    groupe.add(m.objet); anim.oooscar = { e: entite(m, ["animation.silooo_oooscar.balancement"], {}), joie: null };
  }
  function along(p, s) { for (let i = 0; i < p.seg.length; i++) { if (s <= p.seg[i]) return new THREE.Vector3().lerpVectors(p.pts[i], p.pts[i + 1], s / p.seg[i]); s -= p.seg[i]; } return p.pts[p.pts.length - 1].clone(); }

  // ---------- La caméra : un tour lent autour de l'île, le soleil la suit pour que la face vue reste éclairée ----------
  // Le départ est la vue 3/4 de l'aperçu (le pont et le coffre de vente devant). La distance garde l'île entière, ailes du
  // moulin comprises, à chaque angle du tour.
  const AZIMUT0 = Math.atan2(-30, 44), ELEVATION = 22 * DEG, TOUR = 120; // secondes par tour
  const RAYON = 16.5, DISTANCE = RAYON / Math.sin((camera.fov / 2) * DEG);
  function placeCamera(t) {
    const a = AZIMUT0 + (t / TOUR) * 2 * Math.PI;
    const dir = new THREE.Vector3(Math.sin(a) * Math.cos(ELEVATION), Math.sin(ELEVATION), Math.cos(a) * Math.cos(ELEVATION));
    camera.position.copy(target).addScaledVector(dir, DISTANCE); camera.lookAt(target);
    const s = a - 40 * DEG; // le soleil à gauche de la caméra, haut
    sun.position.set(target.x + Math.sin(s) * 34, target.y + 46, target.z + Math.cos(s) * 34);
  }

  function taille() {
    const w = boite.clientWidth, h = boite.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
  }

  let derniere = 0, temps = 0, visible = true, boucle = 0;
  function rendre(dt) {
    temps += dt;
    const image = Math.floor(temps * FPS_FLIPBOOK);
    placeCamera(temps);
    // textures animées : une image tous les 1/8 s, comme flipbook_textures.json
    for (const f of flipbooks) f.t.offset.y = 1 - ((image % f.n) + 1) / f.n;
    joueEntite(anim.ailes, dt);
    joueEntite(anim.pilons, dt);
    for (const s of anim.pierres) { const v = along(s, (temps + s.k * (s.L / 8)) % s.L); s.m.position.set(snap(v.x), snap(v.y), snap(v.z)); }
    // vente : une pierre tombe dans le coffre toutes les L / 8 secondes ; miettes, jeton, et Oooscar se réjouit
    if (anim.vente && anim.pierres.length) {
      const ph = temps % (anim.pierres[0].L / 8);
      if (dt > 0 && ph < anim.vente.prec) {
        emettre("miettes_coffre", anim.vente.pos); setTimeout(() => emettre("jeton_donar", anim.vente.pos), 150);
        if (anim.oooscar && !anim.oooscar.joie) anim.oooscar.joie = entite(anim.oooscar.e.m, ["animation.silooo_oooscar.joie"], {});
      }
      anim.vente.prec = ph;
    }
    for (const e of anim.salaries) joueEntite(e, dt);
    if (anim.oooscar) {
      const o = anim.oooscar; o.e.temps += dt; const pose = {}; ajoute(pose, o.e.anims[0], o.e.temps % o.e.anims[0].len);
      if (o.joie) { o.joie.temps += dt; if (o.joie.temps >= o.joie.anims[0].len) o.joie = null; else ajoute(pose, o.joie.anims[0], o.joie.temps); }
      appliquePose(o.e.m.os, pose);
    }
    // fumée du poêle : l'add-on relance l'émetteur toutes les 8 s
    if (dt > 0) { anim.fumee += dt; if (anim.fumee >= 8) { anim.fumee = 0; emettre("fumee_poele", new THREE.Vector3(...DATA.emetteurs.fumee_poele)); } }
    for (const em of emetteurs) em.pas(dt);
    for (let i = emetteurs.length - 1; i >= 0; i--) if (emetteurs[i].fini) emetteurs.splice(i, 1);
    renderer.render(scene, camera);
  }
  function image(now) {
    boucle = 0;
    const dt = derniere ? Math.min(0.1, (now - derniere) / 1000) : 0; derniere = now;
    rendre(dt);
    relancer();
  }
  function relancer() { if (!reduit && visible && !document.hidden && !boucle) boucle = requestAnimationFrame(image); }
  function arreter() { if (boucle) cancelAnimationFrame(boucle); boucle = 0; derniere = 0; }

  texM("particules"); // les particules naissent plus tard : leur texture se charge maintenant, avec les autres
  // Les textures sont des images : la première image attend qu'elles soient décodées, sinon l'île apparaîtrait noire.
  await Promise.all([...texCache.values(), ...texModeles.values()].map((t) => t.image.decode ? t.image.decode().catch(() => {}) : null));
  for (const t of [...texCache.values(), ...texModeles.values()]) t.needsUpdate = true;
  boite.appendChild(renderer.domElement);
  taille();
  rendre(0);
  boite.classList.add("ile--vivante");
  new ResizeObserver(() => { taille(); if (reduit || !boucle) renderer.render(scene, camera); }).observe(boite);
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; if (visible) relancer(); else arreter(); }).observe(boite);
  document.addEventListener("visibilitychange", () => { if (document.hidden) arreter(); else relancer(); });
  relancer();
}
