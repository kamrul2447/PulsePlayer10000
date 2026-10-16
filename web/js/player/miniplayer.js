/* ============================================================================
   Pulse Player — mini player
   Slides in when a track exists, slides out when the queue is empty.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;
  var el, artEl, titleEl, subEl, playIcon, fillEl;
  var lastUid = null;

  function ensure() {
    el = el || document.getElementById('miniPlayer');
    artEl = artEl || document.getElementById('miniArt');
    titleEl = titleEl || document.getElementById('miniTitle');
    subEl = subEl || document.getElementById('miniSub');
    playIcon = playIcon || document.getElementById('miniPlayIcon');
    fillEl = fillEl || document.getElementById('miniProgressFill');
  }
  function icon(name) {
    if (!playIcon) return;
    var use = playIcon.querySelector('use');
    if (use) use.setAttribute('href', '#' + (String(name).indexOf('i-') === 0 ? name : 'i-' + name));
  }

  var MiniPlayer = {
    mount: function () {
      ensure();
      if (!el) return;
      el.addEventListener('click', function (e) { if (!e.target.closest('button')) P.playerUI.open(); });
      el.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); P.playerUI.open(); }
      });
      document.getElementById('miniPlay').addEventListener('click', function (e) {
        e.stopPropagation(); P.playback.toggle(); U.replay(this, 'is-burst');
      });
      document.getElementById('miniPrev').addEventListener('click', function (e) { e.stopPropagation(); P.playback.prev(); });
      document.getElementById('miniNext').addEventListener('click', function (e) { e.stopPropagation(); P.playback.next(); });
      MiniPlayer.render();
    },
    render: function () {
      ensure();
      if (!el) return;
      var t = S.state.currentTrack;
      if (!t) {
        el.classList.remove('is-visible');
        document.body.classList.remove('has-track');
        lastUid = null;
        return;
      }
      document.body.classList.add('has-track');
      el.classList.add('is-visible');
      el.classList.toggle('is-playing', !!S.state.player.playing);
      el.classList.toggle('is-hidden-by-player', P.playerUI && P.playerUI.isOpen());
      if (t.uid !== lastUid) {
        lastUid = t.uid;
        U.clear(artEl);
        var img = U.h('img', { alt: '', decoding: 'async' });
        P.artwork.mount(img, t, 140);
        artEl.appendChild(img);
        titleEl.textContent = U.trackTitle(t);
        subEl.textContent = U.trackArtist(t);
        titleEl.classList.remove('is-changing');
        U.replay(titleEl, 'is-changing');
      }
      icon(S.state.player.playing ? 'i-pause' : 'i-play');
      var btn = document.getElementById('miniPlay');
      if (btn) btn.setAttribute('aria-label', S.state.player.playing ? 'Pause' : 'Play');
    },
    tick: function () {
      if (!fillEl || !el || !el.classList.contains('is-visible')) return;
      fillEl.style.width = P.progress.miniPercent() + '%';
    }
  };
  P.miniPlayer = MiniPlayer;
  P.bus.on('state', MiniPlayer.render);
})(window);