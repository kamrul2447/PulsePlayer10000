/* ============================================================================
   Pulse Player — music search
   Local search is offline-first. Online catalogue access is optional and stays
   in Kotlin so provider secrets never need to be shipped in web assets.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var S = P.store;
  var ONLINE_TIMEOUT = 9000;
  var searchSeq = 0;
  var onlineTimer = null;

  var Search = {
    providerInfo: function () {
      return {
        name: S.state.settings.searchProviderName || 'YouTube',
        configured: S.state.settings.onlineSearchEnabled !== false,
        requiresNetwork: true,
        note: 'Online search is optional. Pulse Player works fully offline.'
      };
    },

    local: function (query) { return P.library.search(query); },

    online: function (query) {
      var mySeq = ++searchSeq;
      if (S.state.settings.onlineSearchEnabled === false) {
        S.state.search.online = { state: 'disabled', items: [], error: null };
        return Promise.resolve(S.state.search.online);
      }
      if (S.state.online.available === false) {
        S.state.search.online = { state: 'offline', items: [], error: null };
        return Promise.resolve(S.state.search.online);
      }
      S.state.search.online = { state: 'loading', items: [], error: null };
      P.bus.emit('searchResults', S.state.search.online);
      clearTimeout(onlineTimer);
      onlineTimer = setTimeout(function () {
        if (mySeq !== searchSeq) return;
        S.state.search.online = { state: 'error', items: [], error: 'The music service did not respond.' };
        P.bus.emit('searchResults', S.state.search.online);
      }, ONLINE_TIMEOUT);
      P.bridge.searchOnline(query);
      return new Promise(function (resolve) {
        var off = P.bus.on('searchResults', function (payload) {
          if (mySeq !== searchSeq) return;
          clearTimeout(onlineTimer);
          off();
          resolve(payload);
        });
      });
    },

    cancel: function () {
      searchSeq++;
      clearTimeout(onlineTimer);
      P.bridge.cancelSearch();
    },

    reset: function () {
      searchSeq++;
      clearTimeout(onlineTimer);
      S.state.search.online = { state: 'disabled', items: [], error: null };
      P.bus.emit('searchResults', S.state.search.online);
    },

    onlineBlockCopy: function () {
      var st = S.state.search.online.state;
      if (S.state.settings.onlineSearchEnabled === false) {
        return { icon: 'i-sparkle', title: 'Online search is disabled', text: 'Your local music remains available. Turn on online search in Settings to discover YouTube music.' };
      }
      if (st === 'offline') {
        return { icon: 'i-offline', title: 'You are offline', text: 'Connect to the internet to search YouTube. Local music keeps working.' };
      }
      if (st === 'error') {
        return { icon: 'i-offline', title: 'Music service unavailable', text: S.state.search.online.error || 'The provider did not respond. Your local library is unaffected.' };
      }
      if (st === 'loading') {
        return { icon: 'i-refresh', title: 'Searching YouTube…', text: 'This never blocks local playback.' };
      }
      if (st === 'results' && !S.state.search.online.items.length) {
        return { icon: 'i-search', title: 'No online matches', text: 'Try another spelling, or play a local match above.' };
      }
      return null;
    },

    _providers: Object.create(null),
    register: function (name, provider) {
      if (!provider || typeof provider.search !== 'function') throw new Error('Provider must implement search(query)');
      Search._providers[name] = provider;
    },
    get: function (name) { return Search._providers[name] || null; }
  };

  P.search = Search;
  P.bus.on('onlineStatus', function () {
    if (S.state.search.online.state === 'offline' && S.state.online.available) Search.reset();
  });
})(window);