/*
 * Le bandeau direct. Kotlin pose tous les mots dans le HTML ; ce fichier relit /site/direct.json toutes les trente
 * secondes, remplace des nombres, montre ou cache des morceaux du bandeau, et le cache entièrement si silooo-core ne
 * répond plus. Il n'écrit aucune phrase. Quand la Vote party passe de « pas atteinte » à « atteinte » sous ses yeux,
 * il le dit à la page par l'évènement `silooo:voteparty` (Oooscar y réagit) : jamais au premier chargement.
 */
(function () {
  'use strict';

  const PERIODE_MS = 30000;

  function demarrer() {
    const bandeau = document.querySelector('.direct[data-direct]');
    if (!bandeau) return;
    const url = bandeau.dataset.direct;
    if (!url) return;

    // Dernier état « atteinte » observé : null tant qu'aucune lecture ne l'a vu.
    let atteinteAvant = null;
    let enCours = false;

    const present = (v) => v !== null && v !== undefined;

    function remplir(nom, valeur) {
      if (!present(valeur)) return;
      bandeau.querySelectorAll('[data-valeur="' + nom + '"]').forEach((el) => { el.textContent = String(valeur); });
    }

    function afficher(d) {
      if (!d || typeof d !== 'object') throw new Error('réponse inattendue');
      const vote = present(d.voteParty) && typeof d.voteParty === 'object' ? d.voteParty : null;

      remplir('joueurs', d.joueurs);
      remplir('version', d.version);
      if (vote) {
        remplir('votes', vote.votes);
        remplir('objectif', vote.objectif);
      }

      bandeau.querySelectorAll('[data-champ]').forEach((el) => {
        switch (el.dataset.champ) {
          case 'joueurs': el.hidden = !present(d.joueurs); break;
          case 'version': el.hidden = !present(d.version); break;
          case 'voteParty': el.hidden = vote === null; break;
          case 'etat': el.hidden = (el.dataset.etat === 'ouvert') !== (d.etat === 'ouvert'); break;
          default: break;
        }
      });
      bandeau.hidden = false;

      if (vote && typeof vote.atteinte === 'boolean') {
        if (atteinteAvant === false && vote.atteinte) document.dispatchEvent(new CustomEvent('silooo:voteparty'));
        atteinteAvant = vote.atteinte;
      }
    }

    function relire() {
      if (enCours) return;
      enCours = true;
      fetch(url)
        .then((reponse) => {
          if (!reponse.ok) throw new Error('statut ' + reponse.status);
          return reponse.json();
        })
        .then(afficher)
        .catch(() => { bandeau.hidden = true; })
        .then(() => { enCours = false; });
    }

    relire();
    setInterval(() => { if (!document.hidden) relire(); }, PERIODE_MS);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', demarrer, { once: true });
  else demarrer();
})();
