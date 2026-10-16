/* ============================================================================
   Pulse Player — native bridge

   Direction  JS → native : window.PulseNative.postMessage(JSON string)
   Direction  native → JS : window.PulseBridge.onNativeEvent(JSON string)

   The native side exposes exactly one method on purpose: a single, versioned
   message channel keeps the attack surface small and lets us add commands
   without touching the @JavascriptInterface.

   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;
  var bus = P.bus;

  var PROTOCOL_VERSION = 1;
  var seq = 0;
  var pending = Object.create(null);

  var Bridge = {
    version: PROTOCOL_VERSION,
    nativeAvailable: !!global.PulseNative,

    /* ------------------------------------------------------------ sending */
    send: function (cmd, payload) {
      var msg = JSON.stringify({ v: PROTOCOL_VERSION, cmd: cmd, payload: payload || {} });
      if (global.PulseNative && typeof global.PulseNative.postMessage === 'function') {
        try { global.PulseNative.postMessage(msg); return true; }
        catch (err) { console.error('[Pulse] native postMessage failed', err); return false; }
      }
      console.warn('[Pulse] no native bridge; dropped command "' + cmd + '"');
      return false;
    },

    /** Promise-based round trip. Native answers with {type:'reply', requestId}. */
    request: function (cmd, payload, timeoutMs) {
      var id = 'r' + (++seq);
      var envelope = { v: PROTOCOL_VERSION, cmd: cmd, requestId: id, payload: payload || {} };
      return new Promise(function (resolve, reject) {
        var timer = setTimeout(function () {
          delete pending[id];
          reject(new Error('timeout:' + cmd));
        }, timeoutMs || 5000);
        pending[id] = {
          resolve: function (p) { clearTimeout(timer); resolve(p); },
          reject: function (e) { clearTimeout(timer); reject(e); }
        };
        if (global.PulseNative && typeof global.PulseNative.postMessage === 'function') {
          global.PulseNative.postMessage(JSON.stringify(envelope));
        } else if (global.PulseNative && typeof global.PulseNative.request === 'function') {
          // Mock engines may answer synchronously without the event loop.
          global.PulseNative.request(JSON.stringify(envelope));
        } else {
          clearTimeout(timer);
          delete pending[id];
          reject(new Error('bridge-unavailable:' + cmd));
        }
      });
    },

    /* ---------------------------------------------------------- receiving */
    onEvent: function (raw) {
      var msg = U.safeParse(typeof raw === 'string' ? raw : JSON.stringify(raw), null);
      if (!msg || !msg.type) return;

      if (msg.type === 'reply') {
        var slot = pending[msg.requestId];
        if (!slot) return;
        delete pending[msg.requestId];
        if (msg.error) slot.reject(new Error(msg.error));
        else slot.resolve(msg.payload);
        return;
      }
      applyEvent(msg.type, msg.payload || {});
    },

    /* ------------------------------------------------ convenience helpers */
    ready: function () { return Bridge.send('ready', { v: PROTOCOL_VERSION }); },
    requestPermission: function () { return Bridge.send('requestPermission'); },
    openAppSettings: function () { return Bridge.send('openAppSettings'); },
    scanLibrary: function (full) { return Bridge.send('scanLibrary', { full: !!full }); },

    playTracks: function (uids, index, source) {
      return Bridge.send('playTracks', { uids: uids, index: index | 0, source: source || 'library' });
    },
    play: function () { return Bridge.send('play'); },
    pause: function () { return Bridge.send('pause'); },
    togglePlay: function () { return Bridge.send('togglePlay'); },
    next: function () { return Bridge.send('next'); },
    prev: function () { return Bridge.send('prev'); },
    seek: function (ms) { return Bridge.send('seek', { positionMs: Math.max(0, Math.round(ms)) }); },
    seekBy: function (deltaMs) { return Bridge.send('seekBy', { deltaMs: Math.round(deltaMs) }); },
    setVolume: function (v) { return Bridge.send('setVolume', { volume: U.clamp(v, 0, 1) }); },
    setShuffle: function (on) { return Bridge.send('setShuffle', { enabled: !!on }); },
    cycleRepeat: function () { return Bridge.send('cycleRepeat'); },
    setRepeat: function (mode) { return Bridge.send('setRepeat', { mode: mode | 0 }); },
    setFavorite: function (uid, fav) { return Bridge.send('setFavorite', { uid: uid, favorite: !!fav }); },
    enqueueNext: function (uids) { return Bridge.send('enqueueNext', { uids: uids }); },
    archiveTrack: function (track) { return Bridge.send('archiveTrack', track && track.source === 'online' ? { source: 'online', provider: track.provider || 'audius', trackId: track.trackId || track.id, title: track.title, artist: track.artist, album: track.album || '', artworkUrl: track.artworkUrl || null, durationMs: track.durationMs || track.duration || 0, streamUrl: track.streamUrl || null } : { source: 'local', uid: track.uid }); },
    deleteTrack: function (uid) { return Bridge.send('deleteTrack', { uid: uid }); },
    restoreTrack: function (item) { return Bridge.send('restoreTrack', { provider: item.provider || 'local', trackId: item.trackId || item.localUid }); },
    playArchived: function (item) { return Bridge.send('playArchived', { provider: item.provider || 'local', trackId: item.trackId || item.localUid }); },

    startSleepTimer: function (ms) { return Bridge.send('startSleepTimer', { durationMs: Math.round(ms) }); },
    cancelSleepTimer: function () { return Bridge.send('cancelSleepTimer'); },

    setSetting: function (key, value) { return Bridge.send('setSetting', { key: key, value: value }); },

    searchOnline: function (query) { return Bridge.send('searchOnline', { query: query }); },
    cancelSearch: function () { return Bridge.send('cancelSearch'); },
    loadYouTubePlaylist: function (url) { return Bridge.send('loadYouTubePlaylist', { url: url }); },
    playRemoteQueue: function (items) { return Bridge.send('playRemoteQueue', { items: Array.isArray(items) ? items : [] }); },
    appendRemoteQueue: function (items) { return Bridge.send('appendRemoteQueue', { items: Array.isArray(items) ? items : [] }); },
    clearQueue: function () { return Bridge.send('clearQueue'); },

    clearArtCache: function () { return Bridge.send('clearArtCache'); },
    openAppSpace: function () { return Bridge.send('openAppSpace'); },
    addAppSpace: function () { return Bridge.send('addAppSpace'); },
    approveReview: function (uid) { return Bridge.send('approveReview', { uid: uid }); },
    rejectReview: function (uid) { return Bridge.send('rejectReview', { uid: uid }); },
    keepAwake: function (on) { return Bridge.send('keepAwake', { enabled: !!on }); },
    log: function (level, message) { return Bridge.send('log', { level: level, message: message }); }
  };

  /* ======================================================================
     Native event → store mapping (the only place state is mutated from native)
     ====================================================================== */
  function applyEvent(type, p) {
    switch (type) {

      case 'ready':
        S.state.ready = true;
        if (p.appVersion) S.state.appVersion = p.appVersion;
        if (p.appId) S.state.appId = p.appId;
        if (p.settings) applySettings(p.settings);
        if (p.online !== undefined) S.state.online.available = !!p.online;
        bus.emit('ready', p);
        break;

      case 'permission':
        S.state.permission = p.state || 'unknown';
        bus.emit('permission', S.state.permission);
        break;

      case 'scan':
        S.state.scanning = (p.state === 'started' || p.state === 'progress');
        if (p.found !== undefined) S.state.scan.found = p.found;
        if (p.scanned !== undefined) S.state.scan.scanned = p.scanned;
        if (p.total !== undefined) S.state.scan.total = p.total;
        bus.emit('scan', S.state.scan);
        break;

      case 'library':
        S.setTracks(p.tracks || []);
        bus.emit('library', S.state.tracks);
        break;

      case 'archive':
        S.setArchive(p.items || []);
        break;

      case 'reviewQueue':
        S.setReview(p.items || []);
        break;

      case 'favorites':
        S.setFavorites(p.uids || []);
        break;

      case 'queue':
        S.setQueue(p.uids || [], p.currentUid);
        break;

      case 'state':
        applyPlayerState(p);
        break;

      case 'sleep':
        S.state.sleep = {
          active: !!p.active,
          endsAt: p.endsAt || 0,
          remainingMs: p.remainingMs || 0,
          durationMs: p.durationMs || 0
        };
        bus.emit('sleep', S.state.sleep);
        break;

      case 'settings':
        applySettings(p);
        break;

      case 'onlineStatus':
        S.state.online.available = !!p.available;
        bus.emit('onlineStatus', S.state.online);
        break;

      case 'searchResults':
        S.state.search.online = {
          state: 'results',
          items: p.items || [],
          error: p.error || null
        };
        bus.emit('searchResults', S.state.search.online);
        break;

      case 'searchError':
        S.state.search.online = { state: 'error', items: [], error: p.message || 'Search failed' };
        bus.emit('searchResults', S.state.search.online);
        break;

      case 'ads':
        S.state.ads.enabled = !!p.enabled;
        S.state.ads.slots = p.slots || {};
        bus.emit('ads', S.state.ads);
        break;

      case 'toast':
        P.toast && P.toast.show(p.message || '');
        break;

      case 'error':
        console.warn('[Pulse] native error', p);
        bus.emit('nativeError', p);
        break;

      default:
        bus.emit(type, p);
    }
  }

  /**
   * Two kinds of update arrive from the service:
   *  • 'state'    — something structural changed (track, play/pause, shuffle,
   *                 repeat, volume, buffering, duration). Broad UI re-render.
   *  • 'position' — just the clock moved. Only the progress bar cares, so we
   *                 keep the rest of the UI (and the main thread) quiet.
   */
  var STRUCTURAL = ['playing', 'duration', 'buffering', 'volume', 'shuffle', 'repeat', 'error'];

  function applyPlayerState(p) {
    var previousUid = S.state.currentUid;
    S.state.currentUid = p.uid || null;

    if (p.uid && p.source === 'online') {
      S.state.currentTrack = {
        uid: p.uid,
        id: p.trackId || p.uid,
        trackId: p.trackId || p.uid,
        title: p.title || 'Unknown title',
        artist: p.artist || 'Unknown artist',
        album: p.album || '',
        duration: p.duration || 0,
        durationMs: p.duration || 0,
        artworkUrl: p.artworkUrl || null,
        source: 'online',
        provider: p.provider || 'audius'
      };
    } else {
      S.state.currentTrack = p.uid ? S.track(p.uid) : null;
    }

    S.state.player.playing = !!p.playing;
    S.state.player.position = p.position || 0;
    S.state.player.duration = p.duration || 0;
    S.state.player.buffered = p.buffered || 0;
    S.state.player.buffering = !!p.buffering;
    S.state.player.volume = p.volume == null ? 1 : p.volume;
    S.state.player.shuffle = !!p.shuffle;
    S.state.player.repeat = p.repeat == null ? 0 : p.repeat;
    S.state.player.error = p.error || null;

    if (previousUid !== S.state.currentUid) bus.emit('state', S.state.player);
    else bus.emit('state', S.state.player);
  }

  function applySettings(p) {
    Object.keys(p).forEach(function (k) { S.state.settings[k] = p[k]; });
    if (p.theme) P.theme.apply(p.theme);
    if (p.reduceMotion !== undefined) {
      document.documentElement.dataset.reducedMotion = String(!!p.reduceMotion);
    }
    bus.emit('settings', S.state.settings);
  }

  /* ----------------------------------------------------------- export */
  P.bridge = Bridge;

  global.PulseBridge = global.PulseBridge || {
    onNativeEvent: function (json) { Bridge.onEvent(json); }
  };
})(window);