/* Pulse Player — audio-first home. */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;
  var scrollEl = null;
  var lastRenderKey = null;

  var Home = {
    render: function () {
      scrollEl = scrollEl || document.getElementById('homeScroll');
      if (!scrollEl) return;
      var trackKey = S.state.tracks.map(function (t) { return t.uid + ':' + (t.dateModified || 0); }).join('|');
      var currentKey = S.state.currentUid || '';
      var key = trackKey + '::' + currentKey;
      if (lastRenderKey === key) return;
      lastRenderKey = key;
      if (P.nav.current() === 'home') P.nav.setAppbar('Home', S.state.tracks.length ? U.fmtCount(P.library.count()) + ' songs' : '');
      U.clear(scrollEl);

      if (S.state.scanning && !S.state.tracks.length) {
        scrollEl.appendChild(U.h('div', { style: { paddingTop: '8px' } }, P.ui.loading(8)));
        return;
      }
      var songs = P.library.songs();
      if (!songs.length) {
        scrollEl.appendChild(P.ui.empty({
          icon: 'i-note', title: 'No music found yet',
          text: 'Add music to your device, then rescan the MediaStore library.',
          action: { label: 'Rescan Music', onClick: function () { P.bridge.scanLibrary(true); P.toast.show('Scanning your music…'); } }
        }));
        return;
      }

      var hero = U.h('section.homehero',
        U.h('div.homehero__orb'),
        U.h('div.homehero__copy',
          U.h('div.homehero__eyebrow', U.h('span.homehero__dot'), 'YOUR LIBRARY'),
          U.h('h2.homehero__title', { text: S.state.currentTrack ? 'Continue listening' : 'Your music, beautifully organized' }),
          U.h('p.homehero__meta', { text: S.state.currentTrack ? U.trackTitle(S.state.currentTrack) + ' · ' + U.trackArtist(S.state.currentTrack) : U.fmtCount(songs.length) + ' ready to play' })
        )
      );
      if (S.state.currentTrack) {
        var heroArt = U.h('div.homehero__art');
        heroArt.appendChild(P.ui.art(S.state.currentTrack, 180));
        hero.appendChild(heroArt);
      }

      hero.appendChild(U.h('div.homehero__stats',
        U.h('span.homehero__stat', U.h('b', { text: String(songs.length) }), U.h('small', { text: 'Songs' })),
        U.h('span.homehero__stat', U.h('b', { text: String(S.state.albums.length) }), U.h('small', { text: 'Albums' })),
        U.h('span.homehero__stat', U.h('b', { text: String(S.state.artists.length) }), U.h('small', { text: 'Artists' }))
      ));
      if (S.state.currentTrack) {
        hero.appendChild(U.h('button.homehero__action', { type: 'button', onclick: function (e) { U.ripple(e); P.playerUI.open(); } }, U.icon('play', 'ico--sm'), 'Open player'));
      } else {
        hero.appendChild(U.h('button.homehero__action', { type: 'button', onclick: function (e) { U.ripple(e); P.libraryUI.setView('songs'); } }, U.icon('library', 'ico--sm'), 'Browse library'));
      }
      scrollEl.appendChild(hero);

      var all = songs.slice(0, 12);
      scrollEl.appendChild(P.ui.rail('All Songs', all.map(function (t) {
        return P.ui.railCard(t, function (track) { P.playback.playTrack(track, 'home'); });
      }), { linkLabel: 'See all', onLink: function () { P.libraryUI.setView('songs'); } }));

      var recent = P.library.recentlyAdded(12);
      if (recent.length) scrollEl.appendChild(P.ui.rail('Recently Added', recent.map(function (t) {
        return P.ui.railCard(t, function (track) { P.playback.playTrack(track, 'recent'); }, U.fmtDate(t.dateAdded));
      }), { linkLabel: 'See all', onLink: function () { P.libraryUI.setView('recent'); } }));
    }
  };
  P.homeUI = Home;
  P.bus.on('library', function () { if (P.nav.current() === 'home') Home.render(); });
  P.bus.on('archive', function () { if (P.nav.current() === 'home') Home.render(); });
  P.bus.on('favorites', function () { if (P.nav.current() === 'home') Home.render(); });
  P.bus.on('settings', function () { if (P.nav.current() === 'home') Home.render(); });
  P.bus.on('page', function (name) { if (name === 'home') Home.render(); });

  var lastScanningState = null;
  P.bus.on('scan', function () {
    if (P.nav.current() !== 'home') return;
    var active = !!S.state.scanning;
    if (lastScanningState === active) return;
    lastScanningState = active;
    Home.render();
  });
})(window);