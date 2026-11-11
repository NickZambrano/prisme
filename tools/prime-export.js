/*
 * Export de l'historique Prime Video → CSV pour Prisme.
 * À lancer (favori) sur https://www.primevideo.com/…/settings/watch-history, connecté à son compte.
 * Tout se passe dans le navigateur : rien n'est envoyé nulle part, le fichier est simplement téléchargé.
 * Colonnes : Title,Date,Type,Season,Episode,EpisodeTitle   (Date au format AAAA-MM-JJ)
 */
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const items = () => document.querySelectorAll('[data-testid="activity-history-item"]');
  const banner = document.createElement('div');
  banner.style.cssText = 'position:fixed;z-index:2147483647;top:16px;right:16px;padding:14px 18px;border-radius:12px;' +
    'background:#101218;color:#fff;font:15px/1.4 system-ui,sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.4);max-width:360px';
  const say = (t) => { banner.textContent = 'Prisme · ' + t; };
  document.body.appendChild(banner);

  if (!items().length) { say("Ouvre d'abord la page « Historique de visionnage » de Prime Video, puis relance."); return; }

  // 1. Charger tout l'historique (défilement jusqu'à ce que rien de nouveau n'apparaisse).
  let last = -1, stable = 0;
  while (stable < 3) {
    window.scrollTo(0, document.body.scrollHeight);
    await sleep(1200);
    const n = items().length;
    say('Chargement de l’historique… ' + n + ' titres');
    if (n === last) stable++; else { stable = 0; last = n; }
  }

  // 2. Déplier les « Episodes watched » des séries.
  const blocks = [...document.querySelectorAll('[data-testid^="wh-episodes-watched-"]')];
  for (let i = 0; i < blocks.length; i++) {
    say('Lecture des épisodes… ' + (i + 1) + ' / ' + blocks.length);
    const b = blocks[i];
    const trigger = b.querySelector('button, label, [role="button"], summary') || b;
    try { trigger.click(); } catch (e) {}
    await sleep(350);
  }
  await sleep(800);

  // 3. Lecture : chaque <h3> date suivi de sa liste d'éléments.
  const rows = [];
  const iso = (txt) => {
    const d = new Date(txt + ' 12:00');
    if (isNaN(d)) return '';
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  };
  const seasonRe = /^(.*?)\s*[-–:]\s*(?:Season|Saison|Staffel|Temporada|Stagione)\s*(\d+)\s*$/i;
  const epRe = /(?:Episode|Épisode|Ep\.?|E)\s*(\d+)\s*(?:[:\-–.]\s*(.*))?$/i;
  document.querySelectorAll('[data-testid^="wh-date-"]').forEach((dateEl) => {
    const date = iso(dateEl.textContent.trim());
    const list = dateEl.parentElement && dateEl.parentElement.querySelector('ul');
    if (!date || !list) return;
    list.querySelectorAll('[data-testid="activity-history-item"]').forEach((it) => {
      const titleEl = it.querySelector('a:not(:has(img))') || it.querySelector('div a');
      const title = (titleEl ? titleEl.textContent : '').trim();
      if (!title) return;
      const eps = it.querySelector('[data-testid^="wh-episodes-watched-"]');
      if (!eps) { rows.push([title, date, 'movie', '', '', '']); return; }
      const m = title.match(seasonRe);
      const show = m ? m[1].trim() : title;
      const season = m ? m[2] : '';
      // Épisodes dépliés : une ligne par épisode quand le texte le permet.
      const lines = [...eps.querySelectorAll('li, [role="option"], p, span, div')]
        .filter((e) => !e.children.length)
        .map((e) => e.textContent.trim())
        .filter((t) => t && !/^episodes watched$/i.test(t) && !/^épisodes regardés$/i.test(t));
      const seen = new Set();
      let found = 0;
      lines.forEach((t) => {
        const e = t.match(epRe);
        if (!e || seen.has(e[1])) return;
        seen.add(e[1]);
        found++;
        rows.push([show, date, 'episode', season, e[1], (e[2] || '').trim()]);
      });
      if (!found) rows.push([show, date, 'season', season, '', '']);
    });
  });

  // 4. Téléchargement du CSV.
  const q = (v) => '"' + String(v).replace(/"/g, '""') + '"';
  const csv = 'Title,Date,Type,Season,Episode,EpisodeTitle\n' + rows.map((r) => r.map(q).join(',')).join('\n');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }));
  a.download = 'PrimeVideoViewingHistory.csv';
  document.body.appendChild(a);
  a.click();
  const films = rows.filter((r) => r[2] === 'movie').length;
  const episodes = rows.filter((r) => r[2] === 'episode').length;
  const seasons = rows.filter((r) => r[2] === 'season').length;
  say('Terminé : ' + films + ' films, ' + episodes + ' épisodes' + (seasons ? ', ' + seasons + ' saisons sans détail' : '') +
    '. Fichier « PrimeVideoViewingHistory.csv » téléchargé — importe-le dans Prisme.');
})();
