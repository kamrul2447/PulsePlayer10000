(function () {
  "use strict";

  var bridge = window.PulseBridge;
  var appRoot = document.getElementById("app");
  var toastRegion = document.getElementById("toast-region");
  var state = {
    view: "home",
    tracks: [],
    favorites: new Set(),
    queue: [],
    currentUid: null,
    current: null,
    playing: false,
    position: 0,
    duration: 0,
    buffered: 0,
    buffering: false,
    volume: 1,
    shuffle: false,
    repeat: 0,
    settings: {
      theme: "dark",
      reduceMotion: false,
      resumeOnOpen: true,
      scanOnStart: true,
      hideShortClips: true,
      keepScreenAwake: false,
      onlineSearchEnabled: false,
      localSearchEnabled: true,
      apiEndpoints: "[]",
      youtubeApiKeySet: false,
      audiusApiKeySet: false,
      apiYoutubeEnabled: true,
      apiAudiusEnabled: true
    },
    online: false,
    scan: { state: "idle", found: 0, total: 0 },
    searchResults: [],
    searchQuery: "",
    searchMessage: "",
    searchProvider: "",
    searchLoading: false,
    apiMenuOpen: false,
    apis: [],
    apiActivity: [],
    apiScanning: false,
    archive: [],
    reviewQueue: [],
    menuTrackUid: null,
    localQuery: "",
    sleep: null,
    showSleepModal: false,
    toastTimer: null,
    notifyScanFinish: false
  };

  var titles = {
    home: ["YOUR SPACE", "Good sound starts here"],
    library: ["YOUR COLLECTION", "Library"],
    search: ["DISCOVER", "Online search"],
    favorites: ["YOUR COLLECTION", "Favorites"],
    queue: ["NOW AND NEXT", "Playing queue"],
    player: ["NOW PLAYING", "Player"],
    archive: ["SAVED FOR LATER", "Archive"],
    review: ["LIBRARY TOOLS", "Review songs"],
    settings: ["MAKE IT YOURS", "Settings"]
  };

  var ICONS = {
    home: '<path d="m3 10 9-7 9 7"/><path d="M5 9v11h14V9"/><path d="M9 20v-6h6v6"/>',
    library: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21z"/><path d="M4 5.5v13A2.5 2.5 0 0 1 6.5 16H20"/>',
    search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/>',
    heart: '<path d="M20.8 8.8c0 5.2-8.8 10.2-8.8 10.2S3.2 14 3.2 8.8A4.8 4.8 0 0 1 12 6.1a4.8 4.8 0 0 1 8.8 2.7Z"/>',
    queue: '<path d="M4 6h16M4 12h11M4 18h8"/><path d="m17 15 4 3-4 3z"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="m19.4 15 .1.1 1.4 1.1-1.4 2.4-1.7-.6a7.8 7.8 0 0 1-1.7 1l-.3 1.8h-2.8l-.3-1.8a7.8 7.8 0 0 1-1.7-1l-1.7.6-1.4-2.4 1.4-1.1a7.2 7.2 0 0 1 0-2l-1.4-1.1 1.4-2.4 1.7.6a7.8 7.8 0 0 1 1.7-1l.3-1.8h2.8l.3 1.8a7.8 7.8 0 0 1 1.7 1l1.7-.6 1.4 2.4-1.4 1.1a7.2 7.2 0 0 1 0 2Z" transform="translate(-1 -1) scale(1.08)"/>',
    archive: '<rect x="3" y="4" width="18" height="4" rx="1"/><path d="M5 8v12h14V8M10 12h4"/>',
    review: '<path d="M4 4h16v16H4z"/><path d="m8 12 2.5 2.5L16 9"/>',
    music: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    play: '<path d="m8 5 11 7-11 7z" fill="currentColor" stroke="none"/>',
    pause: '<path d="M8 5h3v14H8zM15 5h3v14h-3z" fill="currentColor" stroke="none"/>',
    previous: '<path d="M6 5v14M19 5 9 12l10 7z"/>',
    next: '<path d="M18 5v14M5 5l10 7-10 7z"/>',
    shuffle: '<path d="m16 3 5 4-5 4M3 7h2c5 0 7 10 12 10h4M16 13l5 4-5 4M3 17h2c1.8 0 3.2-1.4 4.6-3.2"/>',
    repeat: '<path d="m17 2 4 4-4 4M3 11V8a2 2 0 0 1 2-2h16M7 22l-4-4 4-4M21 13v3a2 2 0 0 1-2 2H3"/>',
    scan: '<path d="M4 7V4h3M17 4h3v3M20 17v3h-3M7 20H4v-3"/><path d="M7 12h10M12 7v10"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    more: '<circle cx="5" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="19" cy="12" r="1" fill="currentColor"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 10v6M14 10v6"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.5 9a7 7 0 0 1 11.7-2L20 12M4 12l2.8 5a7 7 0 0 0 11.7-2"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
    volume: '<path d="M4 10v4h4l5 4V6l-5 4z"/><path d="M16 9a5 5 0 0 1 0 6M18.5 6.5a8.5 8.5 0 0 1 0 11"/>',
    chevron: '<path d="m9 18 6-6-6-6"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18"/>',
    wifi: '<path d="M5 9a11 11 0 0 1 14 0M8 12a6.5 6.5 0 0 1 8 0M11 15a2 2 0 0 1 2 0"/><circle cx="12" cy="18" r=".6" fill="currentColor"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    playNext: '<path d="M4 5v14l11-7z"/><path d="M19 5v14"/>',
    moon: '<path d="M20.8 14A8.5 8.5 0 0 1 10 3.2 8.5 8.5 0 1 0 20.8 14Z"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    headphones: '<path d="M3 14v-3a9 9 0 0 1 18 0v3"/><rect x="3" y="13" width="4" height="7" rx="2"/><rect x="17" y="13" width="4" height="7" rx="2"/>',
    zap: '<path d="m13 2-3 8h7l-7 12 2-9H5z"/>',
    list: '<path d="M9 6h12M9 12h12M9 18h12"/><path d="M4 6h.01M4 12h.01M4 18h.01"/>',
    link: '<path d="M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1"/><path d="M14 11a5 5 0 0 0-7.1 0l-2 2a5 5 0 0 0 7.1 7.1l1.1-1.1"/>',
    save: '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z"/><path d="M17 21v-8H7v8M7 3v5h8"/>',
    sliders: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M2 14h4M10 8h4M18 16h4"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 7h.01"/>'
  };

  function icon(name, size) {
    return '<svg width="' + (size || 18) + '" height="' + (size || 18) + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (ICONS[name] || ICONS.music) + '</svg>';
  }

  function esc(value) {
    return String(value == null ? "" : value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function fmtTime(value) {
    var total = Math.max(0, Math.floor((Number(value) || 0) / 1000));
    var minutes = Math.floor(total / 60);
    var seconds = total % 60;
    var hours = Math.floor(minutes / 60);
    if (hours > 0) return hours + ":" + String(minutes % 60).padStart(2, "0") + ":" + String(seconds).padStart(2, "0");
    return minutes + ":" + String(seconds).padStart(2, "0");
  }

  function fmtCount(count) {
    var n = Number(count) || 0;
    return n.toLocaleString();
  }

  function coverMarkup(item, size) {
    var image = "";
    if (item && item.artKey) {
      image = "https://pulse.local/art/" + encodeURIComponent(String(item.artKey)) + "?s=" + (size || 96);
    } else if (item && item.artworkUrl && /^https:\/\//i.test(item.artworkUrl)) {
      image = item.artworkUrl;
    }
    return '<div class="track-cover">' + (image ? '<img src="' + esc(image) + '" alt="" loading="lazy" data-fallback="true">' : icon("music", 20)) + '</div>';
  }

  function allVisibleTracks() {
    if (state.view === "favorites") return state.tracks.filter(function (track) { return state.favorites.has(track.uid); });
    var query = state.localQuery.trim().toLowerCase();
    if (!query) return state.tracks.slice();
    return state.tracks.filter(function (track) {
      return [track.title, track.artist, track.album, track.folder, track.path].join(" ").toLowerCase().indexOf(query) >= 0;
    });
  }

  function showToast(message, isError) {
    if (!message) return;
    var toast = document.createElement("div");
    toast.className = "toast" + (isError ? " error" : "");
    toast.textContent = String(message);
    toastRegion.appendChild(toast);
    window.setTimeout(function () {
      toast.style.opacity = "0";
      toast.style.transform = "translateY(5px)";
      window.setTimeout(function () { toast.remove(); }, 220);
    }, 2800);
  }

  function setView(view) {
    if (!titles[view]) return;
    state.view = view;
    state.menuTrackUid = null;
    render();
    if (view === "settings") bridge.send("listApis", {});
    if (view === "queue") bridge.send("restoreQueue", {});
    if (view === "library" || view === "favorites") {
      // Always show the locally cached library; playback state never decides visibility.
      if (!state.tracks.length) bridge.send("scanLibrary", { full: false });
    }
    window.scrollTo(0, 0);
  }

  function updateSettings(payload) {
    if (!payload || typeof payload !== "object") return;
    state.settings = Object.assign({}, state.settings, payload);
    applyTheme();
  }

  function resolvedTheme() {
    var theme = state.settings.theme || "dark";
    var light = theme === "light" || (theme === "system" && window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches);
    return light ? "light" : "dark";
  }

  function applyTheme() {
    var light = resolvedTheme() === "light";
    appRoot.classList.toggle("theme-light", !!light);
    document.documentElement.style.colorScheme = light ? "light" : "dark";
  }

  function playLocal(uid) {
    var list = allVisibleTracks();
    var index = list.findIndex(function (track) { return track.uid === uid; });
    if (index < 0) index = state.tracks.findIndex(function (track) { return track.uid === uid; });
    if (index < 0) return;
    var tracks = (state.view === "favorites" || state.localQuery.trim()) ? list : state.tracks;
    bridge.send("playTracks", { uids: tracks.map(function (track) { return track.uid; }), index: index });
    state.view = "player";
    render();
  }

  function toggleFavorite(uid) {
    var favorite = !state.favorites.has(uid);
    if (favorite) state.favorites.add(uid); else state.favorites.delete(uid);
    render();
    bridge.send("setFavorite", { uid: uid, favorite: favorite });
  }

  function archiveLocal(uid) {
    var track = state.tracks.find(function (item) { return item.uid === uid; });
    if (!track) return;
    bridge.send("archiveTrack", { source: "local", uid: uid });
  }

  function archiveOnline(index) {
    var item = state.searchResults[index];
    if (!item) return;
    bridge.send("archiveTrack", {
      source: "online",
      provider: item.provider || "youtube",
      trackId: item.id,
      title: item.title || "Unknown title",
      artist: item.artist || "Unknown artist",
      album: item.album || "",
      artworkUrl: item.artworkUrl || "",
      durationMs: item.durationMs || 0,
      streamUrl: item.streamUrl || ""
    });
  }

  function playOnline(index) {
    var item = state.searchResults[index];
    if (!item) return;
    bridge.send("playRemote", { item: item });
    state.view = "player";
    render();
  }

  function findYouTubeVideoId(value) {
    try {
      var url = new URL(value);
      var host = url.hostname.toLowerCase().replace(/^www\./, "");
      var id = "";
      if (host === "youtu.be") id = url.pathname.split("/").filter(Boolean)[0] || "";
      if (host === "youtube.com" || host === "m.youtube.com" || host === "music.youtube.com") {
        id = url.searchParams.get("v") || "";
        var parts = url.pathname.split("/").filter(Boolean);
        if (!id && parts.length > 1 && ["shorts", "embed", "live", "v"].indexOf(parts[0]) >= 0) id = parts[1];
      }
      if (!/^[A-Za-z0-9_-]{11}$/.test(id)) return null;
      return { id: id, hasPlaylist: !!url.searchParams.get("list") };
    } catch (error) {
      return null;
    }
  }

  function submitOnlineSearch(rawValue) {
    var query = String(rawValue || "").trim();
    state.searchQuery = query;
    state.searchMessage = "";
    state.searchResults = [];
    state.searchLoading = false;
    if (!query) {
      bridge.send("cancelSearch", {});
      state.searchProvider = "";
      render();
      return;
    }

    var video = findYouTubeVideoId(query);
    if (video) {
      state.searchResults = [{
        id: video.id,
        title: "YouTube video",
        artist: "YouTube",
        album: "",
        artworkUrl: "https://i.ytimg.com/vi/" + video.id + "/hqdefault.jpg",
        durationMs: 0,
        streamUrl: "",
        previewOnly: false,
        provider: "youtube",
        source: "online"
      }];
      state.searchMessage = video.hasPlaylist
        ? "This link includes a playlist, but the current native bridge can only resolve the selected video. Playlist importing needs a dedicated native playlist endpoint."
        : "Video link recognized. Select Play to start it.";
      render();
      return;
    }

    if (/youtube\.com\/playlist|[?&]list=/.test(query)) {
      state.searchMessage = "The current Android bridge does not expose a playlist-loading command yet. Search for a song or paste a single YouTube video URL.";
      render();
      return;
    }

    if (!state.settings.onlineSearchEnabled) {
      state.searchMessage = "Online search is turned off. Enable it in Settings to search YouTube, Audius, and configured APIs.";
      render();
      return;
    }

    if (!state.online) {
      state.searchMessage = "Connect to the internet to search online music.";
      render();
      return;
    }

    state.searchLoading = true;
    render();
    bridge.send("searchOnline", { query: query });
  }

  function navButton(view, label, symbol, count) {
    var active = state.view === view || (view === "library" && state.view === "player");
    return '<button class="nav-button' + (active ? " active" : "") + '" data-action="view" data-view="' + view + '">' + icon(symbol, 18) + '<span>' + label + '</span>' + (count != null ? '<span class="nav-count">' + esc(count) + '</span>' : "") + '</button>';
  }

  function renderSidebar() {
    return '<aside class="sidebar">' +
      '<div class="brand"><div class="brand-mark">P</div><div class="brand-word">PULSE<span>PLAYER</span></div></div>' +
      '<nav class="nav-group" aria-label="Main navigation"><div class="nav-caption">Workspace</div>' +
      navButton("home", "Home", "home") +
      navButton("library", "Library", "library", state.tracks.length || null) +
      navButton("search", "Online search", "search") +
      navButton("favorites", "Favorites", "heart", state.favorites.size || null) +
      navButton("queue", "Play queue", "queue") +
      '</nav><nav class="nav-group" aria-label="Your music"><div class="nav-caption">Your music</div>' +
      navButton("archive", "Archive", "archive", state.archive.length || null) +
      navButton("review", "Review songs", "review", state.reviewQueue.length || null) +
      '</nav><div class="sidebar-bottom"><div class="storage-card"><strong>Music stays yours.</strong><p>Your local library lives on this device. Online search is optional.</p><button class="button ghost" data-action="view" data-view="settings">' + icon("settings", 15) + ' Preferences</button></div></div>' +
      '</aside>';
  }

  function renderHeader() {
    var title = titles[state.view] || titles.home;
    var scanLabel = state.scan.state === "progress" || state.scan.state === "started" ? "Scanning" : (state.online ? "Online" : "Offline");
    return '<header class="topbar"><div class="topbar-copy"><div class="topbar-eyebrow">' + esc(title[0]) + '</div><h1>' + esc(title[1]) + '</h1></div><div class="topbar-actions"><span class="status-pill"><i class="status-dot ' + (state.online ? "online" : "") + '"></i>' + esc(scanLabel) + '</span>' +
      '<button class="icon-button" aria-label="Scan music library" title="Scan music library" data-action="scan">' + icon("refresh", 17) + '</button>' +
      '<button class="icon-button" aria-label="Settings" title="Settings" data-action="view" data-view="settings">' + icon("settings", 17) + '</button></div></header>';
  }

  function renderTrackRow(track, index, options) {
    options = options || {};
    var uid = String(track.uid || "");
    var isFavorite = state.favorites.has(uid);
    var isCurrent = uid === state.currentUid || uid === (state.current && state.current.uid);
    var menu = state.menuTrackUid === uid;
    return '<div class="track-row">' +
      '<button class="track-index icon-button" title="Play ' + esc(track.title) + '" data-action="play-local" data-uid="' + esc(uid) + '">' + (isCurrent && state.playing ? icon("volume", 15) : '<span>' + (index + 1) + '</span>') + '</button>' +
      coverMarkup(track, 96) +
      '<div class="track-copy" data-action="play-local" data-uid="' + esc(uid) + '" role="button" tabindex="0"><div class="track-title">' + esc(track.title || "Unknown title") + '</div><div class="track-meta"><span>' + esc(track.artist || "Unknown artist") + '</span><span class="dot-sep">•</span><span>' + esc(track.album || track.folder || "Local audio") + '</span></div></div>' +
      '<span class="track-duration">' + fmtTime(track.duration) + '</span>' +
      '<div class="row-actions">' +
      '<button class="icon-button favorite-button' + (isFavorite ? " favorited" : "") + '" aria-label="' + (isFavorite ? "Remove favorite" : "Add favorite") + '" title="' + (isFavorite ? "Remove favorite" : "Add favorite") + '" data-action="favorite" data-uid="' + esc(uid) + '">' + icon("heart", 16) + '</button>' +
      '<button class="icon-button optional-action" aria-label="More options" title="More options" data-action="track-menu" data-uid="' + esc(uid) + '">' + icon("more", 17) + '</button></div>' +
      (menu ? '<div class="track-menu"><button data-action="enqueue-local" data-uid="' + esc(uid) + '">' + icon("playNext", 14) + ' Play next</button><button data-action="archive-local" data-uid="' + esc(uid) + '">' + icon("archive", 14) + ' Add to archive</button><button class="danger-text" data-action="delete-local" data-uid="' + esc(uid) + '">' + icon("trash", 14) + ' Delete from device</button></div>' : "") +
      '</div>';
  }

  function renderTrackList(tracks, options) {
    options = options || {};
    if (!tracks.length) {
      var filtered = !!state.localQuery.trim();
      return '<div class="empty-state"><div class="empty-orb">' + icon(filtered ? "search" : "music", 25) + '</div><h3>' + (filtered ? "No matching songs" : "Your library is waiting") + '</h3><p>' + (filtered ? "Try a different title, artist, album, or folder." : "Scan your device to find playable audio. Your songs remain stored locally.") + '</p>' +
        (filtered ? '<button class="button ghost" data-action="clear-local-search">' + icon("close", 15) + ' Clear search</button>' : '<button class="button primary" data-action="scan">' + icon("scan", 15) + ' Scan music library</button>') + '</div>';
    }
    return '<div class="track-list">' + tracks.map(function (track, index) { return renderTrackRow(track, index, options); }).join("") + '</div>';
  }

  function renderHome() {
    var recent = state.tracks.slice(0, 5);
    var count = state.tracks.length;
    var current = state.current;
    var heroTitle = current && current.title ? "Let the music move you." : "Find your frequency.";
    var heroText = current && current.title ? "Pick up where you left off, or let the next track set the mood." : "Your tracks, your rhythm, one calm place for all the music on your device.";
    return '<section class="hero"><div class="hero-copy"><div class="hero-badge">' + icon("zap", 14) + ' A little more in tune</div><h2>' + heroTitle + '</h2><p>' + heroText + '</p><div class="hero-actions">' +
      (current && current.title ? '<button class="button primary" data-action="view" data-view="player">' + icon("play", 16) + ' Return to player</button>' : '<button class="button primary" data-action="view" data-view="library">' + icon("library", 16) + ' Open library</button>') +
      '<button class="button ghost" data-action="view" data-view="search">' + icon("globe", 16) + ' Discover online</button></div></div>' +
      '<div class="hero-art"><div class="hero-orbit"></div><div class="hero-disc"><div class="hero-wave"></div><div class="hero-wave wave-two"></div><div class="hero-wave wave-three"></div></div></div></section>' +
      '<div class="section-heading"><div><h2>Your listening space</h2><p>A few quick ways to get moving.</p></div></div>' +
      '<div class="quick-grid">' +
      '<button class="quick-card" data-action="view" data-view="library"><span class="quick-icon">' + icon("library", 20) + '</span><div><strong>Local library</strong><span>' + fmtCount(count) + ' songs on this device</span></div></button>' +
      '<button class="quick-card" data-action="view" data-view="favorites"><span class="quick-icon">' + icon("heart", 20) + '</span><div><strong>Favorites</strong><span>' + fmtCount(state.favorites.size) + ' saved tracks</span></div></button>' +
      '<button class="quick-card" data-action="view" data-view="queue"><span class="quick-icon">' + icon("queue", 20) + '</span><div><strong>Play queue</strong><span>' + fmtCount(state.queue.length) + ' tracks in the queue</span></div></button>' +
      '</div><div class="section-heading"><div><h2>On your device</h2><p>' + (count ? "Your recently available local tracks." : "Scan to make your music appear here.") + '</p></div><button class="button ghost" data-action="view" data-view="library">View all ' + icon("chevron", 15) + '</button></div>' +
      (recent.length ? renderTrackList(recent) : renderTrackList([])) +
      '<div class="section-heading"><div><h2>Keep it flowing</h2><p>Simple controls, without getting in your way.</p></div></div>' +
      '<div class="panel"><div class="setting-row"><div class="setting-copy"><strong>Online search</strong><span>Search YouTube, Audius, and any configured JSON music API when internet is available.</span></div><button class="button ghost" data-action="view" data-view="settings">Configure ' + icon("chevron", 14) + '</button></div></div>';
  }

  function renderLibrary() {
    var tracks = allVisibleTracks();
    var busy = state.scan.state === "started" || state.scan.state === "progress";
    return '<div class="toolbar"><label class="search-field">' + icon("search", 17) + '<input id="local-filter" type="search" placeholder="Find a song, artist, album or folder" value="' + esc(state.localQuery) + '" autocomplete="off" spellcheck="false"><button class="icon-button" aria-label="Clear search" data-action="clear-local-search">' + icon("close", 15) + '</button></label><button class="button primary" data-action="scan" ' + (busy ? "disabled" : "") + '>' + icon(busy ? "refresh" : "scan", 16) + (busy ? "Scanning…" : "Rescan") + '</button></div>' +
      '<div class="section-heading"><div><h2>' + fmtCount(tracks.length) + ' songs</h2><p>Local audio on this device, independent of playback state.</p></div><button class="button ghost" data-action="play-all" ' + (!tracks.length ? "disabled" : "") + '>' + icon("play", 15) + ' Play all</button></div>' +
      (busy ? '<div class="scan-note">Scanning your library… ' + fmtCount(state.scan.found) + ' found so far.</div>' : "") +
      renderTrackList(tracks);
  }

  function renderFavorites() {
    var tracks = allVisibleTracks();
    return '<div class="toolbar"><label class="search-field">' + icon("search", 17) + '<input id="local-filter" type="search" placeholder="Search your favorites" value="' + esc(state.localQuery) + '" autocomplete="off"></label><button class="button ghost" data-action="view" data-view="library">All songs</button></div>' +
      '<div class="section-heading"><div><h2>' + fmtCount(tracks.length) + ' favorites</h2><p>Your saved local tracks are stored by the Android app.</p></div><button class="button ghost" data-action="play-all" ' + (!tracks.length ? "disabled" : "") + '>' + icon("play", 15) + ' Play favorites</button></div>' +
      renderTrackList(tracks);
  }

  function renderOnlineCard(item, index) {
    return '<article class="online-card"><div class="online-art">' +
      (item.artworkUrl && /^https:\/\//i.test(item.artworkUrl) ? '<img src="' + esc(item.artworkUrl) + '" alt="" loading="lazy" data-fallback="true">' : icon("music", 28)) +
      '<button class="play-circle play-overlay" aria-label="Play ' + esc(item.title) + '" data-action="play-online" data-index="' + index + '">' + icon("play", 18) + '</button></div>' +
      '<div class="track-title">' + esc(item.title || "Unknown title") + '</div><div class="track-meta"><span>' + esc(item.artist || item.provider || "Online track") + '</span></div>' +
      '<div class="online-card-actions"><button class="button primary" data-action="play-online" data-index="' + index + '">' + icon("play", 13) + ' Play</button><button class="button" title="Add to archive" aria-label="Add to archive" data-action="archive-online" data-index="' + index + '">' + icon("archive", 14) + '</button></div></article>';
  }

  function renderSearch() {
    var searchInput = '<div class="toolbar"><label class="search-field">' + icon("search", 18) + '<input id="online-search" type="search" placeholder="Search YouTube, Audius, or paste a video link" value="' + esc(state.searchQuery) + '" autocomplete="off" spellcheck="false"><button class="icon-button" data-action="clear-online-search" aria-label="Clear search">' + icon("close", 15) + '</button></label>' +
      '<button class="icon-button' + (state.apiMenuOpen ? " active" : "") + '" aria-label="Search API options" title="Search API options" data-action="toggle-api-menu">' + icon("more", 18) + '</button>' +
      (state.apiMenuOpen ? '<div class="api-popover"><strong>Online sources</strong><p>Manage providers, keys, and custom HTTPS JSON APIs.</p><button class="button primary" data-action="view" data-view="settings">' + icon("sliders", 15) + ' Open API settings</button><button class="button ghost" data-action="scan-apis">' + icon("refresh", 15) + ' Test enabled APIs</button></div>' : "") +
      '</div>';
    if (!state.searchQuery) {
      var intro = state.settings.onlineSearchEnabled
        ? "Search supported music sources, or paste a direct YouTube video URL."
        : "Online search is optional. Turn it on in Settings when you want to discover music.";
      return searchInput + '<div class="empty-state"><div class="empty-orb">' + icon("globe", 25) + '</div><h3>Something new to listen to</h3><p>' + intro + '</p>' +
        (state.settings.onlineSearchEnabled ? '<button class="button ghost" data-action="scan-apis">' + icon("refresh", 15) + ' Check API status</button>' : '<button class="button primary" data-action="view" data-view="settings">' + icon("settings", 15) + ' Enable online search</button>') + '</div>';
    }
    if (state.searchLoading) return searchInput + '<div class="empty-state"><div class="empty-orb">' + icon("refresh", 24) + '</div><h3>Looking for your next track…</h3><p>Searching enabled providers. Results will appear here.</p></div>';
    if (state.searchMessage) return searchInput + '<div class="empty-state"><div class="empty-orb">' + icon(state.searchMessage.indexOf("recognized") >= 0 ? "check" : "info", 24) + '</div><h3>' + (state.searchResults.length ? "Video link detected" : "A quick note") + '</h3><p>' + esc(state.searchMessage) + '</p></div>' + (state.searchResults.length ? '<div class="online-result-grid">' + state.searchResults.map(renderOnlineCard).join("") + '</div>' : "");
    if (!state.searchResults.length) return searchInput + '<div class="empty-state"><div class="empty-orb">' + icon("search", 24) + '</div><h3>No results yet</h3><p>Try a different query, or check that online search and at least one API provider are enabled.</p><button class="button ghost" data-action="scan-apis">' + icon("refresh", 15) + ' Test APIs</button></div>';

    var grouped = {};
    state.searchResults.forEach(function (item, index) {
      var key = item.provider || "Online";
      if (!grouped[key]) grouped[key] = [];
      grouped[key].push({ item: item, index: index });
    });
    var output = searchInput + '<div class="section-heading"><div><h2>' + fmtCount(state.searchResults.length) + ' results</h2><p>' + esc(state.searchProvider || "Online sources") + '</p></div></div>';
    Object.keys(grouped).forEach(function (provider) {
      output += '<div class="provider-heading"><span>' + esc(provider) + '</span><span>' + grouped[provider].length + '</span></div><div class="online-result-grid">';
      output += grouped[provider].map(function (entry) { return renderOnlineCard(entry.item, entry.index); }).join("");
      output += '</div>';
    });
    return output;
  }

  function renderQueue() {
    if (!state.queue.length) return '<div class="empty-state"><div class="empty-orb">' + icon("queue", 25) + '</div><h3>Your queue is clear</h3><p>Play a local song to start a queue, or browse your library first.</p><button class="button primary" data-action="view" data-view="library">' + icon("library", 15) + ' Browse library</button></div>';
    var rows = state.queue.map(function (uid, index) {
      var track = state.tracks.find(function (item) { return item.uid === uid; });
      if (!track && state.current && (state.currentUid === uid || state.current.uid === uid)) track = state.current;
      if (!track) track = { uid: uid, title: uid, artist: "Queue item", duration: 0 };
      return renderTrackRow(track, index);
    }).join("");
    return '<div class="section-heading"><div><h2>' + fmtCount(state.queue.length) + ' items</h2><p>Queue is controlled by the native Media3 player.</p></div><button class="button ghost" data-action="clear-queue">' + icon("trash", 15) + ' Clear queue</button></div><div class="track-list">' + rows + '</div>';
  }

  function renderPlayer() {
    var track = state.current;
    if (!track || !track.title) return '<div class="empty-state"><div class="empty-orb">' + icon("headphones", 25) + '</div><h3>Nothing playing right now</h3><p>Choose a song from your local library or find a track online.</p><button class="button primary" data-action="view" data-view="library">' + icon("library", 15) + ' Open library</button></div>';
    var pct = state.duration > 0 ? Math.min(100, state.position / state.duration * 100) : 0;
    var local = (track.source || "local") !== "online";
    var fav = local && track.uid ? state.favorites.has(track.uid) : false;
    return '<div class="player-screen"><div class="player-layout"><div class="player-art">' +
      (track.artKey || track.artworkUrl ? '<img src="' + esc(track.artKey ? ("https://pulse.local/art/" + encodeURIComponent(String(track.artKey)) + "?s=720") : track.artworkUrl) + '" alt="" data-fallback="true">' : '<div class="hero-disc"><div class="hero-wave"></div><div class="hero-wave wave-two"></div></div>') +
      '</div><div class="player-details"><div class="eyebrow">' + (local ? 'IN YOUR LIBRARY' : 'PLAYING ONLINE') + '</div><h2>' + esc(track.title) + '</h2><p class="artist">' + esc(track.artist || "Unknown artist") + (track.album ? ' · ' + esc(track.album) : '') + '</p>' +
      '<div class="player-progress"><span id="player-current-time">' + fmtTime(state.position) + '</span><input class="range" id="player-seek" type="range" min="0" max="' + Math.max(1, state.duration) + '" value="' + Math.min(state.position, state.duration || 0) + '" aria-label="Seek position"><span id="player-total-time">' + fmtTime(state.duration) + '</span></div>' +
      '<div class="player-big-controls"><button class="icon-button" aria-label="Previous track" data-action="prev">' + icon("previous", 22) + '</button><button class="play-circle" aria-label="' + (state.playing ? "Pause" : "Play") + '" data-action="toggle-play">' + icon(state.playing ? "pause" : "play", 24) + '</button><button class="icon-button" aria-label="Next track" data-action="next">' + icon("next", 22) + '</button></div>' +
      '<div class="player-secondary"><button class="icon-button' + (state.shuffle ? " active" : "") + '" aria-label="Toggle shuffle" title="Shuffle" data-action="shuffle">' + icon("shuffle", 17) + '</button><button class="icon-button' + (state.repeat ? " active" : "") + '" aria-label="Cycle repeat" title="Repeat" data-action="repeat">' + icon("repeat", 17) + (state.repeat === 2 ? '<sup>1</sup>' : '') + '</button>' +
      (local && track.uid ? '<button class="icon-button' + (fav ? " favorited" : "") + '" aria-label="Toggle favorite" data-action="favorite" data-uid="' + esc(track.uid) + '">' + icon("heart", 17) + '</button>' : '') +
      '<button class="icon-button" aria-label="Sleep timer" title="Sleep timer" data-action="sleep-modal">' + icon("clock", 17) + '</button><button class="icon-button" aria-label="Volume" title="Volume" data-action="volume-down">' + icon("volume", 17) + '</button></div></div></div></div>';
  }

  function renderArchive() {
    if (!state.archive.length) return '<div class="empty-state"><div class="empty-orb">' + icon("archive", 25) + '</div><h3>Keep tracks close</h3><p>Use a song or online result menu to add it to your archive. Archived online tracks may still need an internet connection to play.</p><button class="button primary" data-action="view" data-view="library">' + icon("library", 15) + ' Browse songs</button></div>';
    return '<div class="section-heading"><div><h2>' + fmtCount(state.archive.length) + ' archived tracks</h2><p>Saved references, not duplicate audio files.</p></div></div><div class="track-list">' + state.archive.map(function (item, index) {
      var local = item.provider === "local";
      var uid = item.localUid || item.trackId || "";
      var cover = item.artworkUrl ? '<div class="track-cover"><img src="' + esc(item.artworkUrl) + '" alt="" loading="lazy" data-fallback="true"></div>' : '<div class="track-cover">' + icon("archive", 18) + '</div>';
      return '<div class="track-row"><span class="track-index">' + (index + 1) + '</span>' + cover + '<div class="track-copy"><div class="track-title">' + esc(item.title || "Unknown title") + '</div><div class="track-meta"><span>' + esc(item.artist || "Unknown artist") + '</span><span class="dot-sep">•</span><span>' + esc(local ? "Local" : item.provider || "Online") + '</span></div></div><span class="track-duration">' + fmtTime(item.durationMs) + '</span><div class="row-actions"><button class="icon-button" title="Play archived track" aria-label="Play archived track" data-action="play-archived" data-provider="' + esc(item.provider || "local") + '" data-track-id="' + esc(uid) + '">' + icon("play", 16) + '</button><button class="icon-button" title="Remove from archive" aria-label="Remove from archive" data-action="restore-archive" data-provider="' + esc(item.provider || "local") + '" data-track-id="' + esc(item.trackId || uid) + '">' + icon("close", 16) + '</button></div></div>';
    }).join("") + '</div>';
  }

  function renderReview() {
    if (!state.reviewQueue.length) return '<div class="empty-state"><div class="empty-orb">' + icon("review", 25) + '</div><h3>No songs to review</h3><p>If the scanner finds a file that looks like music but cannot confidently classify it, it can appear here for your approval.</p><button class="button ghost" data-action="scan">' + icon("scan", 15) + ' Scan again</button></div>';
    return '<div class="section-heading"><div><h2>' + fmtCount(state.reviewQueue.length) + ' candidates</h2><p>Approve only files you recognize as music.</p></div></div><div class="track-list">' + state.reviewQueue.map(function (item, index) {
      return '<div class="track-row"><span class="track-index">' + (index + 1) + '</span><div class="track-cover">' + icon("music", 18) + '</div><div class="track-copy"><div class="track-title">' + esc(item.title || item.displayName || "Unknown file") + '</div><div class="track-meta"><span>' + esc(item.artist || "Unknown artist") + '</span><span class="dot-sep">•</span><span>' + esc(item.path || item.folder || "Unclassified file") + '</span></div></div><span class="track-duration">' + fmtTime(item.durationMs) + '</span><div class="row-actions"><button class="icon-button" aria-label="Approve song" title="Add to Songs" data-action="approve-review" data-uid="' + esc(item.uid) + '">' + icon("check", 16) + '</button><button class="icon-button" aria-label="Reject candidate" title="Remove from review" data-action="reject-review" data-uid="' + esc(item.uid) + '">' + icon("close", 16) + '</button></div></div>';
    }).join("") + '</div>';
  }

  function settingSwitch(key, title, description) {
    var checked = state.settings[key] === true;
    return '<div class="setting-row"><div class="setting-copy"><strong>' + title + '</strong><span>' + description + '</span></div><label class="switch"><input type="checkbox" data-setting="' + key + '" ' + (checked ? "checked" : "") + ' aria-label="' + title + '"><span class="switch-track"></span></label></div>';
  }

  function parseApis() {
    try {
      var value = state.settings.apiEndpoints;
      var parsed = typeof value === "string" ? JSON.parse(value || "[]") : value;
      return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
      return [];
    }
  }

  function renderApiCard(api) {
    var status = api.state || "idle";
    var pill = status === "ok" ? "Working" : (status === "needs_key" ? "Needs key" : (status === "error" ? "Error" : (status === "locked" ? "Disabled in build" : "Not tested")));
    var toggle = api.kind === "ads" ? '<span class="api-status">Off in this build</span>' :
      '<label class="switch"><input type="checkbox" data-api-toggle="' + esc(api.id) + '" ' + (api.enabled ? "checked" : "") + ' aria-label="Enable ' + esc(api.name) + '"><span class="switch-track"></span></label>';
    return '<div class="api-card"><div class="api-head"><div class="api-symbol">' + icon(api.id === "youtube" ? "play" : (api.kind === "custom" ? "link" : "globe"), 17) + '</div><div class="api-info"><strong>' + esc(api.name || api.id) + '</strong><span>' + esc(api.host || api.kind || "Provider") + (api.keySource ? " · " + esc(api.keySource) : "") + '</span></div><span class="api-status ' + esc(status) + '">' + pill + '</span>' + toggle + '</div><div class="api-note">' + esc(api.message || "") + (api.ms ? ' · ' + esc(api.ms) + ' ms' : '') + '</div></div>';
  }

  function renderSettings() {
    var apiCards = state.apis.length ? state.apis.map(renderApiCard).join("") : '<p class="muted-note">Loading API registry…</p>';
    var customApis = parseApis();
    return '<div class="panel-grid"><section class="panel"><h3>Appearance</h3><p>Keep the interface comfortable in any light.</p>' +
      '<div class="setting-row"><div class="setting-copy"><strong>Theme</strong><span>Choose a dark, light, or system-matched interface.</span></div><select class="select-input theme-select" id="theme-select" aria-label="Theme"><option value="dark" ' + (state.settings.theme === "dark" ? "selected" : "") + '>Dark</option><option value="light" ' + (state.settings.theme === "light" ? "selected" : "") + '>Light</option><option value="system" ' + (state.settings.theme === "system" ? "selected" : "") + '>System</option></select></div>' +
      settingSwitch("reduceMotion", "Reduce motion", "Tone down decorative movement and transitions.") +
      settingSwitch("keepScreenAwake", "Keep screen awake", "Prevent the display from sleeping while the app is open.") +
      '</section><section class="panel"><h3>Library & playback</h3><p>Control scanning and local playback behavior.</p>' +
      settingSwitch("scanOnStart", "Scan on startup", "Refresh the local music library when Pulse opens.") +
      settingSwitch("hideShortClips", "Hide short clips", "Filter very short audio files from the Songs list.") +
      settingSwitch("resumeOnOpen", "Remember playback", "Keep a resume snapshot for the next app session.") +
      settingSwitch("localSearchEnabled", "Local search", "Allow searching your device music library.") +
      '</section></div>' +
      '<div class="section-heading"><div><h2>Online & APIs</h2><p>Online features are optional and only use enabled sources.</p></div><button class="button" data-action="scan-apis" ' + (state.apiScanning ? "disabled" : "") + '>' + icon("refresh", 15) + (state.apiScanning ? "Testing…" : "Test APIs") + '</button></div>' +
      '<div class="panel">' + settingSwitch("onlineSearchEnabled", "Enable online search", "Allow the app to search enabled providers when connected to the internet.") +
      '<div class="form-stack" style="margin-top:12px"><label class="form-label">YouTube Data API v3 key <input id="youtube-key" class="text-input" type="password" autocomplete="off" placeholder="' + (state.settings.youtubeApiKeySet ? "Key saved. Enter a replacement to update it." : "Optional. Paste your API key.") + '"></label><label class="form-label">Audius public API key <input id="audius-key" class="text-input" type="password" autocomplete="off" placeholder="' + (state.settings.audiusApiKeySet ? "Key saved. Enter a replacement to update it." : "Optional. Paste your public client key.") + '"></label><div><button class="button primary" data-action="save-api-keys">' + icon("save", 15) + ' Save keys</button></div></div>' +
      '<div class="api-list">' + apiCards + '</div></div>' +
      '<div class="section-heading"><div><h2>Custom JSON music APIs</h2><p>Use HTTPS GET endpoints returning a JSON array or common fields such as data, results, items, tracks, or songs.</p></div></div>' +
      '<div class="panel"><div class="form-stack"><label class="form-label">API name<input class="text-input" id="custom-api-name" placeholder="My music API"></label><label class="form-label">HTTPS JSON endpoint<input class="text-input" id="custom-api-url" placeholder="https://example.com/api/search?q={query}"></label><p class="muted-note">Use {query} or {q} where the search term belongs. Do not paste passwords or private bearer tokens here.</p><div><button class="button primary" data-action="add-custom-api">' + icon("plus", 15) + ' Add API</button></div></div>' +
      (customApis.length ? '<div class="custom-api-list"><div class="provider-heading">Configured custom APIs <span>' + customApis.length + '</span></div>' + customApis.map(function (api) { return '<div class="setting-row"><div class="setting-copy"><strong>' + esc(api.name || "Custom API") + '</strong><span>' + esc(api.url) + '</span></div><button class="icon-button" title="Remove API" aria-label="Remove API" data-action="delete-api" data-api-id="' + esc(api.id) + '">' + icon("trash", 15) + '</button></div>'; }).join("") + '</div>' : "") +
      '</div><div class="section-heading"><div><h2>About Pulse</h2><p>Native Media3 handles playback; this interface talks to it through the versioned bridge.</p></div></div><div class="panel"><div class="setting-row"><div class="setting-copy"><strong>Current app interface</strong><span>Pulse Player WebView · bridge protocol v1</span></div><span class="api-status ' + (bridge.isNative() ? "ok" : "") + '">' + (bridge.isNative() ? "Connected" : "Browser preview") + '</span></div><div class="setting-row"><div class="setting-copy"><strong>API activity</strong><span>Last health-check messages reported by native providers.</span></div><button class="button ghost" data-action="scan-apis">' + icon("refresh", 14) + ' Refresh</button></div>' +
      (state.apiActivity.length ? state.apiActivity.slice(-6).reverse().map(function (item) { return '<div class="api-card"><div class="api-info"><strong>' + esc(item.name || item.id || "API") + '</strong><span>' + esc(item.message || item.state || "") + '</span></div></div>'; }).join("") : '<p class="muted-note">No recent API activity to display.</p>') +
      '</div>';
  }

  function renderPage() {
    switch (state.view) {
      case "library": return renderLibrary();
      case "search": return renderSearch();
      case "favorites": return renderFavorites();
      case "queue": return renderQueue();
      case "player": return renderPlayer();
      case "archive": return renderArchive();
      case "review": return renderReview();
      case "settings": return renderSettings();
      default: return renderHome();
    }
  }

  function renderMobileNav() {
    return '<nav class="mobile-nav" aria-label="Primary navigation">' +
      navButton("home", "Home", "home") +
      navButton("library", "Library", "library") +
      navButton("search", "Search", "search") +
      navButton("favorites", "Likes", "heart") +
      navButton("settings", "Settings", "settings") +
      '</nav>';
  }

  function renderDock() {
    var track = state.current;
    if (!track || !track.title) return "";
    return '<div class="player-dock"><div class="dock-track" data-action="view" data-view="player">' + coverMarkup(track, 96) + '<div class="dock-copy"><strong>' + esc(track.title) + '</strong><span>' + esc(track.artist || "Unknown artist") + '</span></div></div>' +
      '<div class="dock-controls"><button class="icon-button" aria-label="Previous track" data-action="prev">' + icon("previous", 17) + '</button><button class="play-circle" aria-label="' + (state.playing ? "Pause" : "Play") + '" data-action="toggle-play">' + icon(state.playing ? "pause" : "play", 17) + '</button><button class="icon-button" aria-label="Next track" data-action="next">' + icon("next", 17) + '</button></div>' +
      '<div class="dock-progress"><span id="dock-current-time">' + fmtTime(state.position) + '</span><input class="range" id="dock-seek" type="range" min="0" max="' + Math.max(1, state.duration) + '" value="' + Math.min(state.position, state.duration || 0) + '" aria-label="Seek position"><span id="dock-total-time">' + fmtTime(state.duration) + '</span></div>' +
      '<div class="dock-right"><button class="icon-button' + (state.shuffle ? " active" : "") + '" title="Shuffle" aria-label="Toggle shuffle" data-action="shuffle">' + icon("shuffle", 16) + '</button><button class="icon-button' + (state.repeat ? " active" : "") + '" title="Repeat" aria-label="Cycle repeat" data-action="repeat">' + icon("repeat", 16) + '</button></div></div>';
  }

  function renderSleepModal() {
    if (!state.showSleepModal) return "";
    var options = [5, 10, 15, 20, 30, 45, 60, 90];
    return '<div class="modal-backdrop" data-action="dismiss-modal"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="sleep-title"><div class="modal-head"><h2 id="sleep-title">Sleep timer</h2><button class="icon-button" data-action="close-sleep" aria-label="Close">' + icon("close", 17) + '</button></div><p class="modal-copy">Let the music fade out when your time is up.</p><div class="quick-grid">' + options.map(function (minutes) { return '<button class="quick-card" data-action="sleep-minutes" data-minutes="' + minutes + '"><span class="quick-icon">' + icon("clock", 18) + '</span><div><strong>' + minutes + ' min</strong><span>Stop playback</span></div></button>'; }).join("") + '</div>' +
      (state.sleep && state.sleep.active ? '<div class="section-heading"><div><h3>Timer active</h3><p>' + esc(fmtTime(state.sleep.remainingMs || 0)) + ' remaining</p></div><button class="button danger" data-action="cancel-sleep">Cancel timer</button></div>' : "") +
      '</section></div>';
  }

  function render() {
    if (!appRoot) return;
    var active = document.activeElement;
    var activeId = active && active.id;
    var selection = null;
    if (active && typeof active.selectionStart === "number") selection = [active.selectionStart, active.selectionEnd];
    applyTheme();
    var header = titles[state.view] || titles.home;
    appRoot.innerHTML = '<div class="app-shell">' + renderSidebar() + '<main class="main-column">' + renderHeader() + '<section class="page" aria-label="' + esc(header[1]) + '">' + renderPage() + '</section></main></div>' + renderDock() + renderMobileNav() + renderSleepModal();
    var images = appRoot.querySelectorAll("img[data-fallback]");
    images.forEach(function (img) {
      img.addEventListener("error", function () {
        var parent = img.parentElement;
        if (parent) parent.innerHTML = icon("music", 20);
      }, { once: true });
    });
    if (activeId) {
      var replacement = document.getElementById(activeId);
      if (replacement) {
        replacement.focus({ preventScroll: true });
        if (selection && typeof replacement.setSelectionRange === "function") {
          try { replacement.setSelectionRange(selection[0], selection[1]); } catch (error) {}
        }
      }
    }
    updateProgressUi();
  }

  function updateProgressUi() {
    var current = document.getElementById("player-current-time");
    var total = document.getElementById("player-total-time");
    var dockCurrent = document.getElementById("dock-current-time");
    var dockTotal = document.getElementById("dock-total-time");
    var playerSeek = document.getElementById("player-seek");
    var dockSeek = document.getElementById("dock-seek");
    if (current) current.textContent = fmtTime(state.position);
    if (total) total.textContent = fmtTime(state.duration);
    if (dockCurrent) dockCurrent.textContent = fmtTime(state.position);
    if (dockTotal) dockTotal.textContent = fmtTime(state.duration);
    [playerSeek, dockSeek].forEach(function (range) {
      if (!range) return;
      if (document.activeElement !== range) range.value = String(Math.min(state.position, state.duration || 0));
      range.max = String(Math.max(1, state.duration));
    });
  }

  function receive(type, payload) {
    payload = payload || {};
    switch (type) {
      case "ready":
        if (payload.settings) updateSettings(payload.settings);
        bridge.send("themeChanged", { resolved: resolvedTheme() });
        bridge.send("listApis", {});
        bridge.send("restoreQueue", {});
        break;
      case "library":
        if (Array.isArray(payload.tracks)) state.tracks = payload.tracks.filter(function (track) { return track && track.uid; });
        break;
      case "favorites":
        if (Array.isArray(payload.uids)) state.favorites = new Set(payload.uids);
        break;
      case "queue":
        if (Array.isArray(payload.uids)) state.queue = payload.uids;
        if (payload.currentUid !== undefined) state.currentUid = payload.currentUid;
        break;
      case "state":
      case "position":
        if (payload.uid !== undefined || payload.title !== undefined || payload.source !== undefined) {
          if (payload.uid || payload.title) {
            var matched = state.tracks.find(function (track) { return track.uid === payload.uid; });
            state.current = Object.assign({}, matched || {}, state.current || {}, payload);
            state.current.uid = payload.uid || (matched && matched.uid) || null;
            state.current.source = payload.source || (matched ? "local" : (state.current.source || "local"));
          } else if (type === "state") {
            state.current = null;
          }
        }
        if (payload.uid !== undefined) state.currentUid = payload.uid;
        if (payload.playing !== undefined) state.playing = !!payload.playing;
        if (payload.position !== undefined) state.position = Math.max(0, Number(payload.position) || 0);
        if (payload.duration !== undefined) state.duration = Math.max(0, Number(payload.duration) || 0);
        if (payload.buffered !== undefined) state.buffered = Math.max(0, Number(payload.buffered) || 0);
        if (payload.buffering !== undefined) state.buffering = !!payload.buffering;
        if (payload.volume !== undefined) state.volume = Math.max(0, Math.min(1, Number(payload.volume)));
        if (payload.shuffle !== undefined) state.shuffle = !!payload.shuffle;
        if (payload.repeat !== undefined) state.repeat = Number(payload.repeat) || 0;
        if (payload.error) showToast(payload.error.message || "Playback error", true);
        break;
      case "settings":
        updateSettings(payload);
        break;
      case "onlineStatus":
        state.online = !!payload.available;
        break;
      case "scan":
        state.scan = Object.assign({}, state.scan, payload);
        if (payload.state === "finished" && state.notifyScanFinish) {
          showToast((Number(payload.found) || 0) + " playable songs found");
          state.notifyScanFinish = false;
        }
        break;
      case "searchResults":
        state.searchLoading = false;
        state.searchResults = Array.isArray(payload.items) ? payload.items : [];
        state.searchProvider = payload.provider || "Online sources";
        state.searchMessage = state.searchResults.length ? "" : "No results found. Check your search phrase, provider settings, API keys, and network connection.";
        break;
      case "searchError":
        state.searchLoading = false;
        state.searchResults = [];
        state.searchMessage = payload.message || "Online search failed.";
        break;
      case "apis":
        state.apis = Array.isArray(payload.apis) ? payload.apis : [];
        state.apiActivity = Array.isArray(payload.activity) ? payload.activity : state.apiActivity;
        state.apiScanning = !!payload.scanning;
        break;
      case "archive":
        state.archive = Array.isArray(payload.items) ? payload.items : [];
        break;
      case "reviewQueue":
        state.reviewQueue = Array.isArray(payload.items) ? payload.items : [];
        break;
      case "sleep":
        state.sleep = payload;
        break;
      case "toast":
        showToast(payload.message || "Done");
        break;
      case "error":
        showToast(payload.message || "Something went wrong", true);
        break;
      case "bridgeUnavailable":
        showToast(payload.message || "Native bridge unavailable", true);
        break;
      default:
        break;
    }
    render();
  }

  function setSetting(key, value) {
    state.settings[key] = value;
    if (key === "theme") applyTheme();
    render();
    bridge.send("setSetting", { key: key, value: value });
    if (key === "theme") bridge.send("themeChanged", { resolved: resolvedTheme() });
  }

  function addCustomApi() {
    var nameEl = document.getElementById("custom-api-name");
    var urlEl = document.getElementById("custom-api-url");
    var name = nameEl ? nameEl.value.trim() : "";
    var url = urlEl ? urlEl.value.trim() : "";
    if (!/^https:\/\//i.test(url)) {
      showToast("Custom API URLs must use HTTPS.", true);
      return;
    }
    var apis = parseApis();
    var id = "custom-" + Date.now().toString(36);
    apis.push({ id: id, name: name || "Custom API", url: url, enabled: true });
    setSetting("apiEndpoints", JSON.stringify(apis));
    bridge.send("listApis", {});
    showToast("Custom API added");
  }

  function deleteApi(id) {
    var apis = parseApis().filter(function (api) { return api.id !== id; });
    setSetting("apiEndpoints", JSON.stringify(apis));
    bridge.send("listApis", {});
    showToast("Custom API removed");
  }

  function toggleApi(id, enabled) {
    if (id === "youtube") setSetting("apiYoutubeEnabled", enabled);
    else if (id === "audius") setSetting("apiAudiusEnabled", enabled);
    else {
      var apis = parseApis().map(function (api) {
        if (api.id === id) return Object.assign({}, api, { enabled: enabled });
        return api;
      });
      setSetting("apiEndpoints", JSON.stringify(apis));
    }
    bridge.send("listApis", {});
  }

  var searchTimer = null;
  var lastSearchSent = "";
  appRoot.addEventListener("click", function (event) {
    var target = event.target.closest("[data-action]");
    if (!target) return;
    var action = target.getAttribute("data-action");
    var uid = target.getAttribute("data-uid") || "";
    var index = Number(target.getAttribute("data-index"));
    switch (action) {
      case "view": setView(target.getAttribute("data-view")); break;
      case "scan": state.notifyScanFinish = true; bridge.send("scanLibrary", { full: true }); break;
      case "play-local": playLocal(uid); break;
      case "play-all": {
        var tracks = allVisibleTracks();
        if (tracks.length) bridge.send("playTracks", { uids: tracks.map(function (track) { return track.uid; }), index: 0 });
        state.view = "player"; render(); break;
      }
      case "favorite": toggleFavorite(uid); break;
      case "track-menu": state.menuTrackUid = state.menuTrackUid === uid ? null : uid; render(); break;
      case "enqueue-local": bridge.send("enqueueNext", { uids: [uid] }); state.menuTrackUid = null; render(); break;
      case "archive-local": archiveLocal(uid); state.menuTrackUid = null; render(); break;
      case "delete-local":
        if (window.confirm("Delete this audio file from your device? This cannot be undone.")) bridge.send("deleteTrack", { uid: uid });
        state.menuTrackUid = null; render(); break;
      case "view-player": setView("player"); break;
      case "toggle-play": bridge.send("togglePlay", {}); break;
      case "play": bridge.send("play", {}); break;
      case "pause": bridge.send("pause", {}); break;
      case "next": bridge.send("next", {}); break;
      case "prev": bridge.send("prev", {}); break;
      case "shuffle": state.shuffle = !state.shuffle; render(); bridge.send("setShuffle", { enabled: state.shuffle }); break;
      case "repeat": state.repeat = (state.repeat + 1) % 3; render(); bridge.send("setRepeat", { mode: state.repeat }); break;
      case "volume-down": state.volume = Math.max(0, state.volume - 0.1); bridge.send("setVolume", { volume: state.volume }); showToast("Volume " + Math.round(state.volume * 100) + "%"); break;
      case "play-online": if (!Number.isNaN(index)) playOnline(index); break;
      case "archive-online": if (!Number.isNaN(index)) archiveOnline(index); break;
      case "archive-track": archiveLocal(uid); break;
      case "play-archived": bridge.send("playArchived", { provider: target.getAttribute("data-provider") || "local", trackId: target.getAttribute("data-track-id") || "" }); setView("player"); break;
      case "restore-archive": bridge.send("restoreTrack", { provider: target.getAttribute("data-provider") || "local", trackId: target.getAttribute("data-track-id") || "" }); break;
      case "approve-review": bridge.send("approveReview", { uid: uid }); break;
      case "reject-review": bridge.send("rejectReview", { uid: uid }); break;
      case "clear-queue": if (window.confirm("Clear the current playback queue?")) bridge.send("clearQueue", {}); break;
      case "clear-local-search": state.localQuery = ""; render(); break;
      case "clear-online-search":
        state.searchQuery = ""; state.searchResults = []; state.searchMessage = ""; state.searchLoading = false; lastSearchSent = "";
        bridge.send("cancelSearch", {}); render(); break;
      case "toggle-api-menu": state.apiMenuOpen = !state.apiMenuOpen; render(); break;
      case "scan-apis": bridge.send("scanApis", {}); break;
      case "save-api-keys": {
        var yt = document.getElementById("youtube-key");
        var au = document.getElementById("audius-key");
        var saved = false;
        if (yt && yt.value.trim()) { bridge.send("setSetting", { key: "youtubeApiKey", value: yt.value.trim() }); state.settings.youtubeApiKeySet = true; yt.value = ""; saved = true; }
        if (au && au.value.trim()) { bridge.send("setSetting", { key: "audiusApiKey", value: au.value.trim() }); state.settings.audiusApiKeySet = true; au.value = ""; saved = true; }
        if (saved) { render(); bridge.send("listApis", {}); showToast("API key settings submitted"); }
        else showToast("Enter a key first.", true);
        break;
      }
      case "add-custom-api": addCustomApi(); break;
      case "delete-api": if (window.confirm("Remove this custom API from Pulse Player?")) deleteApi(target.getAttribute("data-api-id") || ""); break;
      case "sleep-modal": state.showSleepModal = true; render(); break;
      case "close-sleep": state.showSleepModal = false; render(); break;
      case "dismiss-modal": if (event.target === target) { state.showSleepModal = false; render(); } break;
      case "sleep-minutes": bridge.send("startSleepTimer", { durationMs: Number(target.getAttribute("data-minutes")) * 60 * 1000 }); state.showSleepModal = false; render(); showToast("Sleep timer started"); break;
      case "cancel-sleep": bridge.send("cancelSleepTimer", {}); state.sleep = null; render(); break;
      default: break;
    }
  });

  appRoot.addEventListener("input", function (event) {
    var target = event.target;
    if (target.id === "local-filter") {
      state.localQuery = target.value;
      render();
      return;
    }
    if (target.id === "online-search") {
      var value = target.value;
      state.searchQuery = value;
      if (searchTimer) window.clearTimeout(searchTimer);
      searchTimer = window.setTimeout(function () {
        if (value.trim() === lastSearchSent && value.trim()) return;
        lastSearchSent = value.trim();
        submitOnlineSearch(value);
      }, 380);
    }
  });

  appRoot.addEventListener("change", function (event) {
    var target = event.target;
    if (target.matches("[data-setting]")) {
      setSetting(target.getAttribute("data-setting"), !!target.checked);
      return;
    }
    if (target.matches("[data-api-toggle]")) {
      toggleApi(target.getAttribute("data-api-toggle"), !!target.checked);
      return;
    }
    if (target.id === "theme-select") {
      setSetting("theme", target.value);
      return;
    }
    if (target.id === "player-seek" || target.id === "dock-seek") {
      bridge.send("seek", { positionMs: Number(target.value) || 0 });
    }
  });

  appRoot.addEventListener("keydown", function (event) {
    if (event.key === "Enter" && event.target.matches(".track-copy[data-action]")) {
      event.preventDefault();
      event.target.click();
    }
    if (event.key === "Escape" && state.showSleepModal) {
      state.showSleepModal = false;
      render();
    }
  });

  if (bridge) {
    ["ready", "library", "favorites", "queue", "state", "position", "settings", "onlineStatus", "scan", "searchResults", "searchError", "apis", "archive", "reviewQueue", "sleep", "toast", "error", "bridgeUnavailable"].forEach(function (eventName) {
      bridge.on(eventName, function (payload) { receive(eventName, payload); });
    });
  }

  applyTheme();
  render();
  bridge.send("ready", {});
  // Native position events keep authoritative timing; this local tick only animates progress smoothly between them.
  window.setInterval(function () {
    if (state.playing && !state.buffering && state.duration > 0) {
      state.position = Math.min(state.duration, state.position + 1000);
      updateProgressUi();
    }
  }, 1000);
  window.setTimeout(function () {
    var splash = document.getElementById("splash");
    if (splash) splash.classList.add("hidden");
  }, 950);
  if (window.matchMedia) {
    window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", function () {
      if (state.settings.theme === "system") applyTheme();
    });
  }
}());
