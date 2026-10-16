/* ============================================================================
   Pulse Player — theme controller
   'system' | 'dark' | 'light'. Both themes are designed (not inverted).
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};

  var media = global.matchMedia ? global.matchMedia('(prefers-color-scheme: dark)') : null;
  var current = 'dark';
  var applied = null;

  function systemPrefersDark() { return media ? media.matches : true; }

  function metaThemeColor() {
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) return;
    meta.setAttribute('content', applied === 'light' ? '#F4F4F9' : '#0A0A0E');
  }

  var Theme = {
    get: function () { return current; },

    resolved: function () {
      return current === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : current;
    },

    apply: function (theme) {
      if (!theme) return;
      current = theme;
      var next = Theme.resolved();
      if (next === applied) { document.documentElement.dataset.theme = next; return; }
      applied = next;
      document.documentElement.dataset.theme = next;
      metaThemeColor();
      P.bus.emit('theme', { setting: current, resolved: next });
    },

    cycle: function () {
      var order = ['system', 'dark', 'light'];
      var next = order[(order.indexOf(current) + 1) % order.length];
      Theme.apply(next);
      return next;
    },

    label: function () {
      return { system: 'System', dark: 'Dark', light: 'Light' }[current] || 'System';
    }
  };

  if (media) {
    var onChange = function () {
      // Preserve the user's "system" preference when Android's theme changes.
      if (current === 'system') {
        applied = null;
        Theme.apply('system');
      }
    };
    if (media.addEventListener) media.addEventListener('change', onChange);
    else if (media.addListener) media.addListener(onChange);
  }

  Theme.apply('dark');
  P.theme = Theme;
})(window);