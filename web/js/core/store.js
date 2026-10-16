/* ============================================================================
   Pulse Player — application state + event bus

   IMPORTANT: this state is a *mirror*. The single authoritative playback state
   lives in the Android Media3 service (PulsePlaybackService). Native pushes
   events here, and this module rebroadcasts them to the UI. The UI never
   mutates playback truth directly — it sends intents through P.bridge.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};

  /* ------------------------------------------------------------------ bus */
  var handlers = Object.create(null);

  var bus = {
    /** bus.on('state', fn) — '*' receives every event. */
    on: function (event, fn) {
      (handlers[event] = handlers[event] || []).push(fn);
      return function () { bus.off(event, fn); };
    },
    off: function (event, fn) {
      var list = handlers[event];
      if (!list) return;
      var i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    },
    emit: function (event, payload) {
      var list = handlers[event];
      if (list) {
        for (var i = 0; i < list.length; i++) {
          try { list[i](payload); }
          catch (err) { console.error('[Pulse] handler failed for "' + event + '"', err); }
        }
      }
      var any = handlers['*'];
      if (any) {
        for (var j = 0; j < any.length; j++) {
          try { any[j](event, payload); } catch (err2) { console.error(err2); }
        }
      }
    }
  };

  /* ---------------------------------------------------------------- state */
  var state = {
    ready: false,
    /* 'unknown' | 'granted' | 'denied' | 'permanently_denied' */
    permission: 'unknown',
    scanning: false,
    scan: { found: 0, scanned: 0, total: 0 },

    tracks: [],
    albums: [],
    artists: [],
    trackByUid: Object.create(null),
    albumById: Object.create(null),
    artistById: Object.create(null),

    favorites: new Set(),
    archive: { items: [], byKey: Object.create(null) },
    review: { items: [], byUid: Object.create(null) },

    queue: [],          // local uids mirrored from Android
    remoteQueue: [],     // online playlist snapshot used for the Up Next sheet
    remoteQueueTitle: '',
    currentUid: null,
    currentTrack: null,

    player: {
      playing: false,
      position: 0,      // ms
      duration: 0,      // ms
      buffered: 0,
      buffering: false,
      volume: 1,
      shuffle: false,
      repeat: 0,        // 0 = off, 1 = repeat all, 2 = repeat one
      error: null
    },

    sleep: { active: false, endsAt: 0, remainingMs: 0, durationMs: 0 },

    settings: {
      theme: 'dark',            // dark | system | light (dark is Pulse's default)
      reduceMotion: false,
      resumeOnOpen: true,
      scanOnStart: true,
      hideShortClips: true,     // ignore < 30s audio (ringtones/notification sounds)
      keepScreenAwake: false,
      onlineSearchEnabled: false,
      localSearchEnabled: true,
      showVolumeRow: false
    },

    library: { view: 'songs', sort: 'title', artistId: null, albumId: null },
    search: {
      query: '',
      online: { state: 'disabled', items: [], error: null }
    },
    online: { available: null },

    ads: { enabled: false, slots: {} }
  };

  /* --------------------------------------------------------------- helpers */
  var Store = {
    bus: bus,
    state: state,

    track: function (uid) { return state.trackByUid[uid] || null; },

    setTracks: function (tracks) {
      state.tracks = tracks || [];
      state.trackByUid = Object.create(null);
      for (var i = 0; i < state.tracks.length; i++) {
        state.trackByUid[state.tracks[i].uid] = state.tracks[i];
      }
      Store.rebuildIndexes();
    },

    /** Derive album + artist groupings from the flat song list. */
    rebuildIndexes: function () {
      var albumMap = Object.create(null);
      var artistMap = Object.create(null);
      var tracks = state.tracks;

      for (var i = 0; i < tracks.length; i++) {
        var t = tracks[i];
        if (t.isMusic === false || Store.isArchivedLocal(t.uid)) continue;
        if (t.mediaType === 'video' && t.isMusic !== true) continue;
        var aKey = (t.albumId != null ? t.albumId : ('album:' + (t.album || 'Unknown album') + '|' + t.artist));
        var album = albumMap[aKey];
        if (!album) {
          album = albumMap[aKey] = {
            id: String(aKey),
            name: P.util.trackAlbum(t),
            artist: P.util.trackArtist(t),
            artistId: null,
            artKey: t.artKey || null,
            year: t.year || 0,
            trackCount: 0,
            duration: 0,
            uids: []
          };
        }
        album.trackCount++;
        album.duration += (t.duration || 0);
        album.uids.push(t.uid);
        if (!album.artKey && t.artKey) album.artKey = t.artKey;

        var arKey = (t.artistId != null ? t.artistId : ('artist:' + P.util.trackArtist(t)));
        var artist = artistMap[arKey];
        if (!artist) {
          artist = artistMap[arKey] = {
            id: String(arKey),
            name: P.util.trackArtist(t),
            artKey: t.artKey || null,
            albumIds: {},
            trackCount: 0,
            uids: []
          };
        }
        artist.trackCount++;
        artist.uids.push(t.uid);
        artist.albumIds[album.id] = 1;
        if (!artist.artKey && t.artKey) artist.artKey = t.artKey;
        album.artistId = artist.id;
      }

      state.albums = Object.keys(albumMap).map(function (k) { return albumMap[k]; });
      state.artists = Object.keys(artistMap).map(function (k) { return artistMap[k]; });
      state.albumById = Object.create(null);
      state.artistById = Object.create(null);
      state.albums.forEach(function (a) { state.albumById[a.id] = a; });
      state.artists.forEach(function (a) { state.artistById[a.id] = a; });
    },

    /* --------------------------------------------------------- favourites */
    setFavorites: function (list) {
      state.favorites = new Set(list || []);
      bus.emit('favorites', state.favorites);
    },
    isFavorite: function (uid) { return state.favorites.has(uid); },
    toggleFavoriteLocal: function (uid) {
      if (state.favorites.has(uid)) state.favorites.delete(uid);
      else state.favorites.add(uid);
      bus.emit('favorites', state.favorites);
      return state.favorites.has(uid);
    },


    setArchive: function (items) {
      state.archive.items = items || [];
      state.archive.byKey = Object.create(null);
      state.archive.items.forEach(function (item) {
        state.archive.byKey[item.archiveKey] = item;
      });
      Store.rebuildIndexes();
      bus.emit('archive', state.archive.items);
      bus.emit('library', state.tracks);
    },
    isArchivedLocal: function (uid) { return !!state.archive.byKey['local:' + uid]; },
    isArchivedOnline: function (provider, trackId) { return !!state.archive.byKey[String(provider || 'audius') + ':' + trackId]; },

    setReview: function (items) {
      state.review.items = items || [];
      state.review.byUid = Object.create(null);
      state.review.items.forEach(function (item) { state.review.byUid[item.uid] = item; });
      bus.emit('reviewQueue', state.review.items);
    },

    /* -------------------------------------------------------------- queue */
    setQueue: function (uids, currentUid) {
      state.queue = (uids || []).filter(function (u) { return !!state.trackByUid[u]; });
      if (currentUid !== undefined) state.currentUid = currentUid;
      state.currentTrack = Store.track(state.currentUid);
      bus.emit('queue', state.queue);
    },

    setCurrent: function (uid) {
      if (state.currentUid === uid) return false;
      state.currentUid = uid;
      state.currentTrack = Store.track(uid);
      return true;
    },

    favouriteTracks: function () {
      return state.tracks.filter(function (t) { return state.favorites.has(t.uid); });
    }
  };

  P.store = Store;
  P.bus = bus;
})(window);