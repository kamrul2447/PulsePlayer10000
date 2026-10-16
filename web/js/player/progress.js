/* ============================================================================
   Pulse Player — progress / seek bar
   Native position is authoritative; local interpolation keeps the UI smooth.
   While the user scrubs, incoming native updates never fight the drag.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;
  var track, fill, thumb, buffer, curEl, remEl;
  var scrubbing = false;
  var anchor = { position: 0, at: 0 };
  var duration = 0;
  var scrubValue = 0;
  var onSeek = null;

  function elements() {
    track = track || document.getElementById('seekTrack');
    fill = fill || document.getElementById('seekFill');
    thumb = thumb || document.getElementById('seekThumb');
    buffer = buffer || document.getElementById('seekBuffer');
    curEl = curEl || document.getElementById('timeCurrent');
    remEl = remEl || document.getElementById('timeRemaining');
  }
  function pct(value) { return !duration || duration <= 0 ? 0 : U.clamp((value / duration) * 100, 0, 100); }
  function paint(value, bufferedPct) {
    if (!fill || !thumb || !buffer) return;
    var p = pct(value);
    fill.style.width = p + '%';
    thumb.style.left = p + '%';
    if (bufferedPct != null) buffer.style.width = bufferedPct + '%';
    if (curEl) curEl.textContent = U.fmtTime(value / 1000);
    if (remEl) remEl.textContent = '-' + U.fmtTime(Math.max(0, (duration - value) / 1000));
  }
  function valueFromEvent(e) {
    var rect = track.getBoundingClientRect();
    var x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
    return U.clamp(x / rect.width, 0, 1) * duration;
  }

  var Progress = {
    mount: function (seekCallback) {
      elements();
      if (!track) return;
      onSeek = seekCallback;
      var start = function (e) {
        if (!duration) return;
        scrubbing = true;
        track.classList.add('is-active');
        scrubValue = valueFromEvent(e);
        paint(scrubValue);
        if (e.cancelable) e.preventDefault();
      };
      var move = function (e) {
        if (!scrubbing) return;
        scrubValue = valueFromEvent(e);
        paint(scrubValue);
        if (e.cancelable) e.preventDefault();
      };
      var end = function () {
        if (!scrubbing) return;
        scrubbing = false;
        track.classList.remove('is-active');
        if (onSeek) onSeek(scrubValue);
        anchor.position = scrubValue;
        anchor.at = Date.now();
      };
      track.addEventListener('touchstart', start, { passive: false });
      track.addEventListener('touchmove', move, { passive: false });
      track.addEventListener('touchend', end);
      track.addEventListener('touchcancel', end);
      track.addEventListener('mousedown', start);
      window.addEventListener('mousemove', move);
      window.addEventListener('mouseup', end);
    },
    setDuration: function (ms) { duration = ms || 0; paint(Progress.estimate(), null); },
    setPosition: function (ms) {
      if (scrubbing) return;
      anchor.position = ms || 0;
      anchor.at = Date.now();
      paint(anchor.position, S.state.player.buffered ? pct(S.state.player.buffered) : null);
    },
    estimate: function () {
      if (scrubbing) return scrubValue;
      if (!S.state.player.playing) return anchor.position;
      var delta = Date.now() - anchor.at;
      if (delta < 0 || delta > 5000) return anchor.position;
      return Math.min(duration || Infinity, anchor.position + delta);
    },
    tick: function () {
      if (!duration) return;
      paint(Progress.estimate(), S.state.player.buffered ? pct(S.state.player.buffered) : null);
    },
    isScrubbing: function () { return scrubbing; },
    miniPercent: function () { return !duration ? 0 : U.clamp((Progress.estimate() / duration) * 100, 0, 100); }
  };
  P.progress = Progress;
})(window);