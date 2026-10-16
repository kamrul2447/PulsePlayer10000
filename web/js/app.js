/* ============================================================================
   Pulse Player — bootstrap
   Wires modules together, starts the tick loops and tells Android we're ready.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;

  var rafId = null;
  var secondTimer = null;

  /* ------------------------------------------------------------- ticking */
  function frame() {
    if (S.state.player.playing && !document.hidden) {
      P.progress.tick();
      P.miniPlayer.tick();
    }
    rafId = requestAnimationFrame(frame);
  }

  function startLoops() {
    if (rafId == null) rafId = requestAnimationFrame(frame);
    if (secondTimer == null) {
      secondTimer = setInterval(function () {
        P.sleep.tick();
        P.bus.emit('tick');
      }, 1000);
    }
  }
  function stopLoops() {
    if (rafId != null) { cancelAnimationFrame(rafId); rafId = null; }
    if (secondTimer != null) { clearInterval(secondTimer); secondTimer = null; }
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) stopLoops(); else startLoops();
  });

  /* -------------------------------------------------------------- render */
  function renderInitial() {
    P.nav.go('home', { force: true });
    P.homeUI.render();
    P.libraryUI.render();
    P.searchUI.render();
    P.settingsUI.render();
    P.miniPlayer.render();
    P.playerUI.render();
  }

  /* --------------------------------------------------------------- boot */
  function boot() {
    try {
      P.theme.apply(S.state.settings.theme || 'system');
      P.nav.mount();
      P.miniPlayer.mount();
      P.playerUI.mount();

      renderInitial();

      /* Native state fan-out ------------------------------------------- */
      /* Structural change: track, play/pause, shuffle, repeat, volume… */
      /* State arrives at 2 Hz while music is playing. Do not rebuild the
         Library for every position snapshot: rebuilding its .stagger list
         restarts the entrance animation and makes the whole section appear
         to shake. Refresh rows only when the visible playing state actually
         changes (track switch or play/pause). */
      var lastLibraryUid = null;
      var lastLibraryPlaying = null;
      P.bus.on('state', function (pl) {
        P.progress.setDuration(pl.duration);
        P.progress.setPosition(pl.position);
        P.miniPlayer.render();

        if (P.nav.current() === 'library') {
          var uidChanged = S.state.currentUid !== lastLibraryUid;
          var playingChanged = !!S.state.player.playing !== lastLibraryPlaying;
          if (uidChanged || playingChanged) {
            lastLibraryUid = S.state.currentUid;
            lastLibraryPlaying = !!S.state.player.playing;
            P.libraryUI.refreshRows();
          }
        }

        if (P.nav.current() === 'home' && S.state.currentUid) keepHomeFresh();
      });

      /* Clock tick only — keep it cheap. */
      P.bus.on('position', function (pl) {
        P.progress.setPosition(pl.position);
      });

      var lastLibrarySignature = null;
      P.bus.on('library', function (tracks) {
        var list = tracks || [];
        var signature = list.map(function (t) { return t.uid + ':' + (t.dateModified || 0) + ':' + (t.hidden ? 1 : 0); }).join('|');
        if (signature === lastLibrarySignature) return;
        lastLibrarySignature = signature;

        // Repaint only the page the user can currently see. Repainting hidden pages
        // on every startup snapshot made several entrance/layout passes happen in
        // quick succession, which looked like the whole app was shaking.
        if (P.nav.current() === 'library') P.libraryUI.render();
        else if (P.nav.current() === 'home') P.homeUI.render();
        else if (P.nav.current() === 'search') P.searchUI.render();
      });

      P.bus.on('queue', function () {
        if (P.queue && P.sheet.isOpen()) P.queue.render();
      });

      P.bus.on('nativeError', function (e) {
        // Never crash; show something actionable instead.
        P.toast.show(e && e.message ? e.message : 'Something went wrong', 2600);
      });

      P.bus.on('permission', function (state) {
        P.onboarding.render(state);
        if (state === 'granted') P.homeUI.render();
      });

      /* Optional future ad slots (no-ops while disabled) ---------------- */
      P.ads.init();

      startLoops();

      /* Say hello to Android -------------------------------------------- */
      P.bridge.ready();

      /* If nothing ever answers (e.g. broken bridge), still show the UI. */
      setTimeout(function () { P.splash.hide(); }, 3000);
    } catch (err) {
      console.error('[Pulse] boot failed', err);
      P.splash.hide(true);
    }
  }

  /* Home only needs a light touch while playing: swap the resume card copy. */
  var lastResumeUid = null;
  function keepHomeFresh() {
    if (lastResumeUid === S.state.currentUid) return;
    lastResumeUid = S.state.currentUid;
    P.homeUI.render();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  P.app = { renderAll: renderInitial, startLoops: startLoops, stopLoops: stopLoops };
})(window);