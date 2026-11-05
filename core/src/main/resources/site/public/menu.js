/*
 * Le menu du site sous 700 px. Le HTML livre la navigation toujours dépliée et le bouton « Menu » caché : sans script,
 * personne ne perd un lien. Ce fichier est chargé sans defer, avant le rendu, pour que la classe `js` soit posée avant
 * la première image : c'est elle qui autorise le CSS à replier la navigation sur téléphone. Il ne fait rien d'autre.
 */
(function () {
  'use strict';

  document.documentElement.classList.add('js');

  function demarrer() {
    const bouton = document.querySelector('.menu__bouton');
    const menu = document.getElementById('menu');
    if (!bouton || !menu) return;

    const OUVERT = 'navigation--ouverte';
    const ouvert = () => menu.classList.contains(OUVERT);
    function regler(etat) {
      menu.classList.toggle(OUVERT, etat);
      bouton.setAttribute('aria-expanded', etat ? 'true' : 'false');
    }

    bouton.hidden = false;
    bouton.addEventListener('click', () => regler(!ouvert()));
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape' || !ouvert()) return;
      regler(false);
      bouton.focus();
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', demarrer, { once: true });
  else demarrer();
})();
