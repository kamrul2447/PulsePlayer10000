/* Pulse Player — audio-first library queries. */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;

  var SORTS = {
    title: { label: 'Title', fn: function (t) { return U.trackTitle(t).toLowerCase(); } },
    artist: { label: 'Artist', fn: function (t) { return (U.trackArtist(t) + ' ' + U.trackTitle(t)).toLowerCase(); } },
    album: { label: 'Album', fn: function (t) { return (U.trackAlbum(t) + ' ' + (t.track || 0)).toLowerCase(); } },
    dateAdded: { label: 'Recently added', fn: function (t) { return -(t.dateAdded || 0); } },
    duration: { label: 'Duration', fn: function (t) { return t.duration || 0; } }
  };

  function isArchived(t) { return !!(t && S.isArchivedLocal && S.isArchivedLocal(t.uid)); }
  function isSong(t) {
    if (!t || t.isMusic === false || isArchived(t)) return false;
    if (t.mediaType !== 'audio' && t.mediaType !== 'video' && t.mediaType != null) return false;

    // Defensive UI filter for stale cache rows. Native classification is
    // authoritative. Recognized music-video downloads are valid Songs even
    // when their container MIME type is video/*.
    var mime = String(t.mimeType || '').toLowerCase();
    var name = String(t.displayName || '').toLowerCase();
    var path = String(t.path || '') + '/' + String(t.folder || '');
    path = path.replace(/\\/g, '/').toLowerCase();
    var blockedPath = /(^|\/)dcim(\/|$)|(^|\/)camera(\/|$)|(^|\/)screenrecord(\/|$)|(^|\/)screen recorder(\/|$)|(^|\/)whatsapp(\/|$)|(^|\/)movies(\/|$)|(^|\/)recordings(\/|$)|(^|\/)snapchat(\/|$)|(^|\/)android\/(data|obb)(\/|$)|(^|\/)streamingassets(\/|$)|(^|\/)game(s)?(\/|$)/.test(path);
    if (blockedPath) return false;

    var combined = (name + ' ' + String(t.title || '') + ' ' + String(t.artist || '')).toLowerCase();
    if (/(^|[^a-z0-9])(bgmc|bgm|sfx|sound[_ -]?effect|voiceover|dialogue|foley|ambient[_ -]?sfx)([^a-z0-9]|$)/.test(combined)) return false;

    if (mime.indexOf('audio/') === 0) return true;

    if (mime.indexOf('video/') === 0) {
      var musicVideoEvidence =
        /lyrics?|official\s+(music|video)|music\s+video|\baudio\b|\bsong\b|\bvevo\b/.test(combined) ||
        /(^|\/)snaptube(\/|$)/.test(path) ||
        /\s[-–]\s/.test(name);
      return !!t.isMusic && musicVideoEvidence;
    }

    return false;
  }

  var Library = {
    sorts: SORTS,
    isEmpty: function () { return this.songs().length === 0; },
    count: function () { return this.songs().length; },

    songs: function (sort) {
      var list = S.state.tracks.filter(function (t) { return isSong(t); });
      var s = SORTS[sort || S.state.library.sort] || SORTS.title;
      return U.sortBy(list, s.fn);
    },

    videos: function (sort) {
      var list = S.state.tracks.filter(function (t) { return t.isMusic === false && !isArchived(t); });
      var s = SORTS[sort || S.state.library.sort] || SORTS.title;
      return U.sortBy(list, s.fn);
    },

    albums: function (sort) {
      var list = S.state.albums.slice();
      if (sort === 'year') return U.sortBy(list, function (a) { return -a.year; });
      if (sort === 'artist') return U.sortBy(list, function (a) { return (a.artist + ' ' + a.name).toLowerCase(); });
      return U.sortBy(list, function (a) { return a.name.toLowerCase(); });
    },
    artists: function () { return U.sortBy(S.state.artists, function (a) { return a.name.toLowerCase(); }); },
    favorites: function () { return U.sortBy(S.favouriteTracks().filter(isSong), function (t) { return U.trackTitle(t).toLowerCase(); }); },

    recentlyAdded: function (limit) {
      var list = U.sortBy(S.state.tracks.filter(isSong), function (t) { return -(t.dateAdded || 0); });
      return limit ? list.slice(0, limit) : list;
    },
    recentlyPlayed: function (limit) {
      var list = S.state.tracks.filter(function (t) { return isSong(t) && t.lastPlayedAt > 0; });
      list = U.sortBy(list, function (t) { return -(t.lastPlayedAt || 0); });
      return limit ? list.slice(0, limit) : list;
    },
    mostPlayed: function (limit) {
      var list = S.state.tracks.filter(function (t) { return isSong(t) && (t.playCount || 0) > 0; });
      list = U.sortBy(list, function (t) { return -t.playCount; });
      return limit ? list.slice(0, limit) : list;
    },

    archiveTracks: function () {
      return (S.state.archive.items || []).map(function (a) {
        return {
          uid: a.localUid || 'archive:' + a.provider + ':' + a.trackId,
          id: a.trackId,
          trackId: a.trackId,
          title: a.title || 'Unknown title',
          artist: a.artist || 'Unknown artist',
          album: a.album || '',
          duration: a.durationMs || 0,
          durationMs: a.durationMs || 0,
          artworkUrl: a.artworkUrl || null,
          artKey: a.provider === 'local' ? null : null,
          source: a.provider === 'local' ? 'local' : 'online',
          provider: a.provider,
          contentUri: a.contentUri || ''
        };
      });
    },

    albumTracks: function (albumId) {
      var album = S.state.albumById[albumId];
      if (!album) return [];
      var set = Object.create(null); album.uids.forEach(function (u) { set[u] = 1; });
      return U.sortBy(S.state.tracks.filter(function (t) { return set[t.uid] && isSong(t); }), function (t) { return (t.track || 999) * 1000 + (t.title || '').length * 0.001; });
    },
    artistAlbums: function (artistId) {
      var artist = S.state.artistById[artistId];
      if (!artist) return [];
      return Object.keys(artist.albumIds).map(function (id) { return S.state.albumById[id]; }).filter(Boolean).sort(function (a, b) { return (b.year || 0) - (a.year || 0); });
    },
    artistTracks: function (artistId) {
      var artist = S.state.artistById[artistId];
      if (!artist) return [];
      var set = Object.create(null); artist.uids.forEach(function (u) { set[u] = 1; });
      return U.sortBy(S.state.tracks.filter(function (t) { return set[t.uid] && isSong(t); }), function (t) { return U.trackTitle(t).toLowerCase(); });
    },

    shuffled: function (list) {
      var a = (list || this.songs()).slice();
      for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var tmp = a[i]; a[i] = a[j]; a[j] = tmp; }
      return a;
    },

    search: function (query) {
      var q = String(query || '').trim().toLowerCase();
      if (!q || S.state.settings.localSearchEnabled === false) return { songs: [], artists: [], albums: [] };
      var words = q.split(/\s+/);
      function score(hay) {
        hay = String(hay || '').toLowerCase();
        var idx = hay.indexOf(q);
        if (idx === 0) return 100;
        if (idx > 0) return 70 - Math.min(idx, 30);
        return words.every(function (w) { return hay.indexOf(w) >= 0; }) ? 30 : -1;
      }
      function collect(items, keyFn, limit) {
        var scored = [];
        items.forEach(function (item) { var s = score(keyFn(item)); if (s >= 0) scored.push({ item: item, s: s }); });
        scored.sort(function (a, b) { return b.s - a.s; });
        return scored.slice(0, limit).map(function (x) { return x.item; });
      }
      var songs = this.songs('title');
      return {
        songs: collect(songs, function (t) { return [U.trackTitle(t), U.trackArtist(t), U.trackAlbum(t), t.displayName || ''].join(' '); }, 50),
        artists: collect(S.state.artists, function (a) { return a.name; }, 12),
        albums: collect(S.state.albums, function (a) { return a.name + ' ' + a.artist; }, 12)
      };
    },

    viewLabel: function (view) {
      return ({ songs: 'All songs', albums: 'Albums', artists: 'Artists', favorites: 'Favorites', recent: 'Recently added', played: 'Recently played', most: 'Most played', archived: 'Archived' })[view] || 'Library';
    }
  };
  P.library = Library;
})(window);