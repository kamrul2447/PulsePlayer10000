/* ============================================================================
   Pulse Player — navigation (top tabs + Android back handling)
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var TITLES = { home: 'Home', library: 'Library', search: 'Search', settings: 'Settings' };
  var current = 'home', mounted = false, tabs = [], pages = {};

  function setAppbar(title, subtitle, actions) {
    var t = document.getElementById('appbarTitle');
    var s = document.getElementById('appbarSubtitle');
    var a = document.getElementById('appbarActions');
    if (t && title != null) t.textContent = title;
    if (s) { s.textContent = subtitle || ''; s.style.display = subtitle ? '' : 'none'; }
    if (a && actions !== undefined) {
      U.clear(a);
      (actions || []).forEach(function (n) { a.appendChild(n); });
    }
  }

  function moveIndicator(index) {
    var ind = document.getElementById('navIndicator');
    if (!ind) return;
    var pct = ((index + 0.5) / tabs.length) * 100;
    ind.style.left = pct + '%';
    ind.style.transform = 'translateX(-50%)';
  }

  var Nav = {
    setAppbar: setAppbar,
    current: function () { return current; },
    go: function (name, opts) {
      if (!pages[name]) return;
      if (name === current) {
        if (opts && opts.force) {
          setAppbar(TITLES[name] || '', '');
          tabs.forEach(function (btn, i) {
            var active = btn.dataset.page === name;
            btn.classList.toggle('is-active', active);
            btn.setAttribute('aria-current', active ? 'page' : 'false');
            if (active) moveIndicator(i);
          });
          return;
        }
        var sc = document.querySelector('#page-' + name + ' .page__scroll');
        if (sc) sc.scrollTo({ top: 0, behavior: U.reducedMotion() ? 'auto' : 'smooth' });
        return;
      }

      var prev = pages[current];
      if (prev) prev.classList.add('is-leaving');
      setTimeout(function () { if (prev) prev.classList.remove('is-leaving'); }, 320);
      Object.keys(pages).forEach(function (k) { pages[k].classList.toggle('is-active', k === name); });
      tabs.forEach(function (btn, i) {
        var active = btn.dataset.page === name;
        btn.classList.toggle('is-active', active);
        btn.setAttribute('aria-current', active ? 'page' : 'false');
        if (active) moveIndicator(i);
      });
      current = name;
      setAppbar(TITLES[name] || '', '');
      P.bus.emit('page', name);
    },
    back: function () {
      if (P.sheet && P.sheet.close()) return true;
      if (P.playerUI && P.playerUI.close()) return true;
      if (current !== 'home') { Nav.go('home'); return true; }
      return false;
    },
    mount: function () {
      if (mounted) return;
      mounted = true;
      tabs = U.$$('#bottomNav .nav__tab');
      pages = {};
      U.$$('.page').forEach(function (p) { pages[p.dataset.page] = p; });
      tabs.forEach(function (btn, i) {
        btn.addEventListener('click', function () { Nav.go(btn.dataset.page); });
        if (btn.classList.contains('is-active')) moveIndicator(i);
      });
      document.addEventListener('keydown', function (e) {
        if (e.target && /input|textarea/i.test(e.target.tagName)) {
          if (e.key === 'Escape') e.target.blur();
          return;
        }
        switch (e.key) {
          case ' ': e.preventDefault(); P.playback.toggle(); break;
          case 'ArrowRight': if (e.shiftKey) P.playback.next(); else P.playback.seekBy(5000); break;
          case 'ArrowLeft': if (e.shiftKey) P.playback.prev(); else P.playback.seekBy(-5000); break;
          case 'ArrowUp': P.playback.volumeUp(); break;
          case 'ArrowDown': P.playback.volumeDown(); break;
          case 'Escape': Nav.back(); break;
          case 'f': case 'F': if (P.store.state.currentUid) P.favorites.toggle(P.store.state.currentUid); break;
          default: break;
        }
      });
      moveIndicator(0);
    }
  };

  P.nav = Nav;
  global.PulseBack = function () { return Nav.back(); };
})(window);