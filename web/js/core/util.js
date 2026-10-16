/* ============================================================================
   Pulse Player — utilities
   Small, dependency-free helpers shared by every module.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};

  var U = {};

  /* ---------------------------------------------------------------- time */
  U.fmtTime = function (sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    var total = Math.floor(sec);
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = total % 60;
    var mm = h > 0 ? String(m).padStart(2, '0') : String(m);
    return (h > 0 ? h + ':' : '') + mm + ':' + String(s).padStart(2, '0');
  };

  /** Long, human friendly duration: "3 min 42 s", "1 hr 12 min". */
  U.fmtDuration = function (sec) {
    if (!isFinite(sec) || sec <= 0) return '—';
    var m = Math.round(sec / 60);
    if (m < 1) return '< 1 min';
    if (m < 60) return m + ' min';
    var h = Math.floor(m / 60), rm = m % 60;
    return h + ' hr' + (rm ? ' ' + rm + ' min' : '');
  };

  U.fmtCount = function (n, singular, plural) {
    return n + ' ' + (n === 1 ? (singular || 'song') : (plural || (singular || 'song') + 's'));
  };

  U.fmtDate = function (epochSeconds) {
    if (!epochSeconds) return '';
    var d = new Date(epochSeconds * 1000);
    var now = new Date();
    var days = Math.floor((now - d) / 86400000);
    if (days < 1) return 'Today';
    if (days < 2) return 'Yesterday';
    if (days < 7) return days + ' days ago';
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: d.getFullYear() !== now.getFullYear() ? 'numeric' : undefined });
  };

  /* ---------------------------------------------------------------- maths */
  U.clamp = function (v, min, max) { return v < min ? min : (v > max ? max : v); };
  U.lerp = function (a, b, t) { return a + (b - a) * t; };

  U.hash = function (str) {
    var h = 2166136261;
    str = String(str || '');
    for (var i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return Math.abs(h >>> 0);
  };

  /* ---------------------------------------------------------------- timing */
  U.debounce = function (fn, ms) {
    var t;
    return function () {
      var args = arguments, self = this;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(self, args); }, ms);
    };
  };
  U.throttle = function (fn, ms) {
    var last = 0, timer = null;
    return function () {
      var now = Date.now(), args = arguments, self = this;
      var wait = ms - (now - last);
      if (wait <= 0) { last = now; fn.apply(self, args); }
      else if (!timer) {
        timer = setTimeout(function () { timer = null; last = Date.now(); fn.apply(self, args); }, wait);
      }
    };
  };
  U.raf = function (fn) {
    var queued = false, lastArgs;
    return function () {
      lastArgs = arguments;
      if (queued) return;
      queued = true;
      requestAnimationFrame(function () { queued = false; fn.apply(null, lastArgs); });
    };
  };

  /* ---------------------------------------------------------------- arrays */
  U.uniqBy = function (list, keyFn) {
    var seen = Object.create(null), out = [];
    for (var i = 0; i < list.length; i++) {
      var k = keyFn(list[i]);
      if (!seen[k]) { seen[k] = 1; out.push(list[i]); }
    }
    return out;
  };
  U.groupBy = function (list, keyFn) {
    var map = Object.create(null);
    for (var i = 0; i < list.length; i++) {
      var k = keyFn(list[i]);
      (map[k] = map[k] || []).push(list[i]);
    }
    return map;
  };
  U.sortBy = function (list, keyFn, desc) {
    var collator = U._collator || (U._collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' }));
    return list.slice().sort(function (a, b) {
      var ka = keyFn(a), kb = keyFn(b);
      var r = (typeof ka === 'number' && typeof kb === 'number') ? (ka - kb) : collator.compare(String(ka), String(kb));
      return desc ? -r : r;
    });
  };

  /* ---------------------------------------------------------------- strings */
  U.escapeHtml = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  U.unknown = function (v, fallback) {
    var s = (v == null ? '' : String(v)).trim();
    if (!s) return fallback;
    if (/^(unknown|<unknown>|null|none|\(null\))$/i.test(s)) return fallback;
    return s;
  };

  /** "<unknown>"-safe display values for a track. */
  U.trackTitle = function (t) {
    if (!t) return 'Unknown title';
    var title = U.unknown(t.title, '');
    if (title) return title;
    // Fall back to the file name without extension.
    var name = U.unknown(t.displayName || t.path || '', 'Unknown title');
    return name.replace(/\.[^.]+$/, '');
  };
  U.trackArtist = function (t) { return U.unknown(t && t.artist, 'Unknown artist'); };
  U.trackAlbum = function (t) { return U.unknown(t && t.album, 'Unknown album'); };

  /* ---------------------------------------------------------------- DOM */
  /**
   * Minimal hyperscript: h('div.card#id', {onclick: fn, html: '...'}, children)
   */
  U.h = function (tag, attrs) {
    var parts = String(tag).split(/(?=[.#])/);
    var node = document.createElement(parts[0] || 'div');
    for (var i = 1; i < parts.length; i++) {
      var p = parts[i];
      if (p[0] === '.') node.classList.add(p.slice(1));
      else if (p[0] === '#') node.id = p.slice(1);
    }
    var children = Array.prototype.slice.call(arguments, 2);
    if (attrs && (typeof attrs !== 'object' || attrs instanceof Node)) {
      children.unshift(attrs);
      attrs = null;
    }
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v == null || v === false) return;
        if (k === 'class' || k === 'className') { node.className += (node.className ? ' ' : '') + v; }
        else if (k === 'html') { node.innerHTML = v; }
        else if (k === 'text') { node.textContent = v; }
        else if (k === 'style' && typeof v === 'object') { Object.assign(node.style, v); }
        else if (k === 'dataset') { Object.keys(v).forEach(function (d) { node.dataset[d] = v[d]; }); }
        else if (k.slice(0, 2) === 'on' && typeof v === 'function') { node.addEventListener(k.slice(2).toLowerCase(), v); }
        else if (k === 'svg') { node.innerHTML = '<svg class="ico"><use href="#' + v + '"/></svg>'; }
        else if (v === true) { node.setAttribute(k, ''); }
        else { node.setAttribute(k, v); }
      });
    }
    append(node, children);
    return node;
  };

  function append(node, children) {
    children.forEach(function (c) {
      if (c == null || c === false) return;
      if (Array.isArray(c)) return append(node, c);
      node.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
    });
  }

  /** Inline icon: U.icon('play', 'ico ico--sm') */
  U.icon = function (name, cls) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
    var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#i-' + String(name).replace(/^i-/, ''));
    svg.appendChild(use);
    return svg;
  };

  U.iconHtml = function (name, cls) {
    return '<svg class="ico' + (cls ? ' ' + cls : '') + '"><use href="#i-' + String(name).replace(/^i-/, '') + '"/></svg>';
  };

  U.$ = function (sel, root) { return (root || document).querySelector(sel); };
  U.$$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  U.clear = function (node) { while (node && node.firstChild) node.removeChild(node.firstChild); };

  /** Touch/click press ripple inside a relatively-positioned element. */
  U.ripple = function (evt, host) {
    if (U.reducedMotion()) return;
    host = host || evt.currentTarget;
    if (!host) return;
    var rect = host.getBoundingClientRect();
    var point = evt.touches ? evt.touches[0] : evt;
    var size = Math.max(rect.width, rect.height) * 1.1;
    var r = document.createElement('span');
    r.className = 'ripple';
    r.style.width = r.style.height = size + 'px';
    r.style.left = ((point.clientX - rect.left) - size / 2) + 'px';
    r.style.top = ((point.clientY - rect.top) - size / 2) + 'px';
    var pos = getComputedStyle(host).position;
    if (pos === 'static') host.style.position = 'relative';
    host.appendChild(r);
    setTimeout(function () { if (r.parentNode) r.parentNode.removeChild(r); }, 540);
  };

  /* ---------------------------------------------------------------- motion */
  U.reducedMotion = function () {
    if (document.documentElement.dataset.reducedMotion === 'true') return true;
    return global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches;
  };

  /** Re-trigger a CSS animation class on an element. */
  U.replay = function (node, cls) {
    if (!node) return;
    node.classList.remove(cls);
    void node.offsetWidth;
    node.classList.add(cls);
  };

  /* ---------------------------------------------------------------- misc */
  U.uid = function (prefix) {
    return (prefix || 'id') + '-' + Math.random().toString(36).slice(2, 9);
  };

  U.safeParse = function (json, fallback) {
    try { return JSON.parse(json); } catch (e) { return fallback; }
  };

  P.util = U;
})(window);