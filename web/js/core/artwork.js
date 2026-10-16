/* ============================================================================
   Pulse Player — artwork handling

   • Embedded artwork is served by the Android layer over an intercepted
     local origin: https://pulse.local/art/<artKey>?s=<px>  (disk + memory
     cached on the native side, already down-sized to the requested px).
   • When a track has no artwork we generate a deterministic, brand-consistent
     cover (curated gradient + waveform + monogram). It is produced once per
     (track, size) and cached, so we never recreate it while scrolling.
   • Nothing in here ever renders a broken-image icon: onError falls back.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;

  /* Curated gradient pairs — premium, muted, on-brand. */
  var PALETTE = [
    ['#7C3AED', '#A78BFA'], ['#4F46E5', '#818CF8'], ['#0EA5E9', '#7DD3FC'],
    ['#0D9488', '#5EEAD4'], ['#059669', '#6EE7B7'], ['#D97706', '#FCD34D'],
    ['#DB2777', '#F9A8D4'], ['#E11D48', '#FDA4AF'], ['#1E293B', '#64748B'],
    ['#6D28D9', '#C4B5FD'], ['#0891B2', '#A5F3FC'], ['#B45309', '#FDE68A']
  ];

  var fallbackCache = Object.create(null);
  var ART_HOST = 'https://pulse.local/art/';

  function paletteFor(key) {
    var h = U.hash(key);
    return PALETTE[h % PALETTE.length];
  }

  /** Deterministic pseudo-random sequence from a seed (mulberry32). */
  function rng(seed) {
    var a = seed >>> 0;
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /**
   * Build the Pulse Player fallback cover as an SVG data URI.
   * @param {string} seed  stable key (track uid / album name)
   * @param {string} label text used for the monogram
   * @param {number} size  requested square size in px
   */
  function buildFallback(seed, label, size) {
    size = size || 320;
    var key = seed + '|' + size;
    if (fallbackCache[key]) return fallbackCache[key];

    var pair = paletteFor(seed);
    var rand = rng(U.hash(seed + 'wave'));
    var bars = [];
    var count = 26;
    var gap = 4;
    var w = (size - gap * (count - 1)) / count;
    var mid = size * 0.62;
    for (var i = 0; i < count; i++) {
      // Gentle envelope so the waveform peaks in the middle.
      var env = Math.sin((i / (count - 1)) * Math.PI);
      var h = Math.max(size * 0.04, (size * 0.22) * env * (0.45 + rand() * 0.85));
      var x = i * (w + gap);
      var y = mid - h / 2 + (size * 0.16);
      bars.push('<rect x="' + x.toFixed(1) + '" y="' + y.toFixed(1) +
        '" width="' + w.toFixed(1) + '" height="' + h.toFixed(1) +
        '" rx="' + Math.min(w / 2, 3).toFixed(1) + '"/>');
    }

    var monogram = String(label || '?').trim().charAt(0).toUpperCase();
    if (!/[A-Z0-9]/.test(monogram)) monogram = '♪';

    var svg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">' +
        '<defs>' +
          '<linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
            '<stop offset="0" stop-color="' + pair[0] + '"/>' +
            '<stop offset="1" stop-color="' + pair[1] + '"/>' +
          '</linearGradient>' +
          '<radialGradient id="r" cx="26%" cy="18%" r="80%">' +
            '<stop offset="0" stop-color="#ffffff" stop-opacity="0.30"/>' +
            '<stop offset="1" stop-color="#000000" stop-opacity="0.18"/>' +
          '</radialGradient>' +
        '</defs>' +
        '<rect width="' + size + '" height="' + size + '" fill="url(#g)"/>' +
        '<g fill="#ffffff" opacity="0.16">' + bars.join('') + '</g>' +
        '<rect width="' + size + '" height="' + size + '" fill="url(#r)"/>' +
        '<text x="50%" y="' + (size * 0.44).toFixed(0) + '" text-anchor="middle" ' +
          'font-family="-apple-system,Segoe UI,Roboto,sans-serif" font-size="' + (size * 0.34).toFixed(0) + '" ' +
          'font-weight="700" fill="#ffffff" fill-opacity="0.92">' + U.escapeHtml(monogram) + '</text>' +
      '</svg>';

    var uri = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
    fallbackCache[key] = uri;
    return uri;
  }

  var Artwork = {
    /** URL for a track/album artwork, always non-null. */
    url: function (track, size) {
      size = size || 320;
      if (!track) return buildFallback('empty', 'P', size);
      if (track.artworkUrl) return track.artworkUrl;
      if (track.artKey) {
        // Ask the native layer for a pre-scaled, cached JPEG when possible.
        return ART_HOST + encodeURIComponent(track.artKey) + '?s=' + size + '&v=' + (track.artVersion || 1);
      }
      var seed = track.uid || track.id || U.trackTitle(track);
      return buildFallback(seed, U.trackTitle(track), size);
    },

    /** URL for an album / artist grouping. */
    urlForGroup: function (group, size) {
      size = size || 320;
      if (!group) return buildFallback('group', 'P', size);
      if (group.artKey) return ART_HOST + encodeURIComponent(group.artKey) + '?s=' + size;
      return buildFallback(group.id || group.name || 'g', group.name, size);
    },

    buildFallback: buildFallback,

    /** Blurred background for the full player. */
    background: function (track) {
      if (!track) return '';
      return 'url("' + Artwork.url(track, 128) + '")';
    },

    /**
     * Attach artwork to an <img>, with graceful fallback if the native
     * artwork request fails (deleted file, corrupted tag, cache miss…).
     */
    mount: function (img, track, size, onReady) {
      if (!img) return;
      var src = Artwork.url(track, size);
      if (img.dataset.pulseSrc === src) { onReady && onReady(img); return; }
      img.dataset.pulseSrc = src;
      img.classList.remove('is-loaded');

      var fallbackUri = buildFallback((track && track.uid) || 'x', U.trackTitle(track), size || 320);
      var failed = false;

      img.onerror = function () {
        if (failed) return;
        failed = true;
        img.onerror = null;
        img.src = fallbackUri;
      };
      img.onload = function () {
        img.classList.add('is-loaded');
        onReady && onReady(img);
      };
      img.src = src;
    },

    /** Render a complete art element (img + placeholder) for lists. */
    element: function (track, size, extraClass) {
      var wrap = U.h('div.' + (extraClass || 'art'), { style: {} });
      var img = U.h('img', { alt: '', loading: 'lazy', decoding: 'async' });
      Artwork.mount(img, track, size);
      wrap.appendChild(img);
      return wrap;
    },

    clearCache: function () { fallbackCache = Object.create(null); }
  };

  P.artwork = Artwork;
})(window);