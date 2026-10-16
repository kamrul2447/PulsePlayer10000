/* ============================================================================
   Pulse Player — FUTURE advertisement layer (ARCHITECTURE ONLY — DISABLED)

   ADVERTISEMENT_ENABLED IS FALSE IN THIS RELEASE.
   While it is false this module performs no network requests, renders no UI,
   creates no timers, never interrupts music, and adds no startup dependency.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var AdConfig = {
    enabled: false,
    endpoint: '',
    fetchTimeoutMs: 6000,
    maxCacheAgeMs: 15 * 60 * 1000,
    positions: {
      top: { enabled: false, aspect: '6:1' },
      bottom: { enabled: false, aspect: '6:1' },
      art: { enabled: false, aspect: '1:1', maxDisplayMs: 15000 }
    },
    policy: { allowAudio: false, allowVideo: false, allowAutoplayMedia: false, mayPausePlayback: false, mayBlockStartup: false }
  };

  function AdCreative(data) {
    data = data || {};
    this.id = data.id || '';
    this.name = data.name || '';
    this.imageUrl = data.imageUrl || '';
    this.clickUrl = data.clickUrl || '';
    this.position = data.position || 'bottom';
    this.aspect = data.aspect || '6:1';
    this.displayDurationMs = data.displayDurationMs || 15000;
    this.enabled = data.enabled !== false;
    this.startDate = data.startDate || null;
    this.endDate = data.endDate || null;
    this.priority = data.priority || 0;
  }
  AdCreative.prototype.isLive = function (now) {
    now = now || Date.now();
    if (!this.enabled) return false;
    if (this.startDate && now < Date.parse(this.startDate)) return false;
    if (this.endDate && now > Date.parse(this.endDate)) return false;
    return !!this.imageUrl;
  };

  function AdProvider() {}
  AdProvider.prototype.fetch = function () { return Promise.resolve([]); };
  AdProvider.prototype.name = 'provider';
  function NoopAdProvider() { AdProvider.call(this); }
  NoopAdProvider.prototype = Object.create(AdProvider.prototype);
  NoopAdProvider.prototype.constructor = NoopAdProvider;
  NoopAdProvider.prototype.name = 'noop';
  NoopAdProvider.prototype.fetch = function () { return Promise.resolve([]); };

  function RemoteAdProvider(endpoint) {
    AdProvider.call(this);
    this.endpoint = endpoint;
    this.cache = [];
    this.cacheAt = 0;
  }
  RemoteAdProvider.prototype = Object.create(AdProvider.prototype);
  RemoteAdProvider.prototype.constructor = RemoteAdProvider;
  RemoteAdProvider.prototype.name = 'remote';
  RemoteAdProvider.prototype.fetch = function (position) {
    var self = this;
    if (!AdConfig.enabled || !this.endpoint) return Promise.resolve([]);
    if (Date.now() - this.cacheAt < AdConfig.maxCacheAgeMs) return Promise.resolve(self.cache.filter(byPosition(position)));
    return new Promise(function (resolve) {
      var done = false;
      var timer = setTimeout(function () { if (!done) { done = true; resolve([]); } }, AdConfig.fetchTimeoutMs);
      var xhr = new XMLHttpRequest();
      xhr.open('GET', self.endpoint + (self.endpoint.indexOf('?') >= 0 ? '&' : '?') + 'position=' + encodeURIComponent(position || ''), true);
      xhr.timeout = AdConfig.fetchTimeoutMs;
      xhr.onload = function () {
        if (done) return;
        done = true;
        clearTimeout(timer);
        var payload = P.util.safeParse(xhr.responseText, null);
        var list = (payload && payload.ads ? payload.ads : []).map(function (d) { return new AdCreative(d); });
        self.cache = list;
        self.cacheAt = Date.now();
        resolve(list.filter(byPosition(position)));
      };
      xhr.onerror = xhr.ontimeout = function () { if (!done) { done = true; clearTimeout(timer); resolve([]); } };
      try { xhr.send(); } catch (e) { clearTimeout(timer); resolve([]); }
    });
  };
  function byPosition(position) { return function (ad) { return ad.isLive() && (!position || ad.position === position); }; }

  var provider = new NoopAdProvider();
  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }

  var Ads = {
    config: AdConfig,
    AdCreative: AdCreative,
    AdProvider: AdProvider,
    NoopAdProvider: NoopAdProvider,
    RemoteAdProvider: RemoteAdProvider,
    useProvider: function (p) {
      provider = p instanceof AdProvider ? p : new NoopAdProvider();
      P.bus.emit('ads', { enabled: Ads.active(), provider: provider.name });
    },
    active: function () { return AdConfig.enabled === true && P.store.state.ads.enabled !== false; },
    init: function () {
      // Advertising stays disabled in this release, regardless of remote settings.
      Ads.clearAllSlots();
      return Promise.resolve([]);
    },
    fill: function (position) {
      var host = document.getElementById('adSlot-' + position);
      if (!host || !Ads.active() || !AdConfig.positions[position] || !AdConfig.positions[position].enabled) {
        Ads.clearSlot(position);
        return Promise.resolve(null);
      }
      return provider.fetch(position).then(function (list) {
        if (!list.length) { Ads.clearSlot(position); return null; }
        list.sort(function (a, b) { return b.priority - a.priority; });
        return Ads.render(host, list[0], position);
      }).catch(function () { Ads.clearSlot(position); return null; });
    },
    render: function (host, creative, position) {
      Ads.clearSlot(position);
      var img = document.createElement('img');
      img.src = creative.imageUrl;
      img.alt = creative.name || 'Advertisement';
      img.draggable = false;
      host.appendChild(img);
      host.hidden = false;
      host.dataset.adId = creative.id;
      host.setAttribute('aria-label', 'Advertisement: ' + (creative.name || 'sponsored'));
      if (creative.clickUrl) {
        var btn = document.createElement('button');
        btn.className = 'adslot__click';
        btn.setAttribute('aria-label', 'Open advertisement');
        btn.addEventListener('click', function () {
          P.bridge.send('openAd', { url: creative.clickUrl, id: creative.id });
        });
        host.appendChild(btn);
      }
      if (position === 'art') {
        var ms = Math.min(creative.displayDurationMs, AdConfig.positions.art.maxDisplayMs);
        setTimeout(function () { Ads.clearSlot('art'); }, ms);
      }
      return creative;
    },
    clearSlot: function (position) {
      var host = document.getElementById('adSlot-' + position);
      if (!host) return;
      clear(host);
      host.hidden = true;
      delete host.dataset.adId;
      P.bus.emit('adCleared', position);
    },
    clearAllSlots: function () { ['top', 'bottom', 'art'].forEach(Ads.clearSlot); },
    status: function () {
      return Ads.active() ? { on: true, label: 'Active', provider: provider.name } : { on: false, label: 'Disabled', provider: 'noop' };
    }
  };
  P.ads = Ads;
})(window);