/* ============================================================================
   Pulse Player — favorites
   Favorites are persisted by the Android layer (Room database), so they
   survive app restarts, device reboots and library rescans. The UI applies an
   optimistic change immediately and the native side confirms it.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var S = P.store;

  var Favorites = {
    has: function (uid) { return S.isFavorite(uid); },

    toggle: function (uid) {
      if (!uid) return false;
      var nowFav = S.toggleFavoriteLocal(uid);
      P.bridge.setFavorite(uid, nowFav);
      P.bus.emit('favoriteChanged', { uid: uid, favorite: nowFav });
      return nowFav;
    },

    set: function (uid, fav) {
      if (!uid) return;
      if (S.isFavorite(uid) !== !!fav) S.toggleFavoriteLocal(uid);
      P.bridge.setFavorite(uid, !!fav);
    },

    burst: function (btn) {
      if (!btn) return;
      P.util.replay(btn, 'is-burst');
      setTimeout(function () { btn.classList.remove('is-burst'); }, 500);
    }
  };

  P.favorites = Favorites;
})(window);