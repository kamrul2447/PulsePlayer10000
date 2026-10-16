/* ============================================================================
   Pulse Player — splash / startup
   The splash goes away on the first real library snapshot, a definitive
   permission state, or a hard safety timeout so startup never hangs.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var el = null, statusEl = null, hidden = false, safety = null, readyReceived = false, scanStarted = false;

  var Splash = {
    setStatus: function (text) {
      statusEl = statusEl || document.getElementById('splashStatus');
      if (statusEl) statusEl.textContent = text || '';
    },
    hide: function (immediate) {
      if (hidden) return;
      hidden = true;
      clearTimeout(safety);
      el = el || document.getElementById('splash');
      if (!el) return finish();
      if (immediate) { el.style.display = 'none'; return finish(); }
      el.classList.add('is-hiding');
      setTimeout(function () { el.style.display = 'none'; finish(); }, 340);
    },
    isVisible: function () { return !hidden; }
  };

  function finish() {
    document.body.classList.remove('booting');
    document.body.classList.add('ready');
    var app = document.getElementById('app');
    if (app) app.setAttribute('aria-hidden', 'false');
  }

  P.bus.on('ready', function () {
    readyReceived = true;
    clearTimeout(safety);
    safety = setTimeout(function () {
      Splash.setStatus('Finishing up…');
      Splash.hide();
    }, 2600);
  });
  P.bus.on('library', function (tracks) {
    if ((tracks || []).length > 0) Splash.hide();
    else if (scanStarted && !P.store.state.scanning) Splash.hide();
  });
  P.bus.on('scan', function () {
    if (!readyReceived) return;
    scanStarted = scanStarted || !!P.store.state.scanning;
    if (scanStarted && !P.store.state.scanning) Splash.hide();
  });
  P.bus.on('permission', function (state) {
    if (state === 'denied' || state === 'permanently_denied') Splash.hide();
  });

  P.splash = Splash;
})(window);