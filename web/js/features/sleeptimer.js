/* ============================================================================
   Pulse Player — sleep timer
   The timer is owned by Android's foreground service, so it remains reliable
   while backgrounded or the screen is off. This module renders and sends intents.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var S = P.store;
  var U = P.util;

  var PRESETS = [
    { ms: 15 * 60000, label: '15 min' },
    { ms: 30 * 60000, label: '30 min' },
    { ms: 45 * 60000, label: '45 min' },
    { ms: 60 * 60000, label: '1 hr' },
    { ms: 90 * 60000, label: '1.5 hr' },
    { ms: 120 * 60000, label: '2 hr' }
  ];
  var localEndsAt = 0;
  var SleepTimer = {
    presets: PRESETS,
    start: function (ms) {
      ms = Math.round(ms);
      if (ms <= 0) return SleepTimer.cancel();
      localEndsAt = Date.now() + ms;
      S.state.sleep = { active: true, endsAt: localEndsAt, remainingMs: ms, durationMs: ms };
      P.bridge.startSleepTimer(ms);
      P.bus.emit('sleep', S.state.sleep);
    },
    cancel: function () {
      localEndsAt = 0;
      S.state.sleep = { active: false, endsAt: 0, remainingMs: 0, durationMs: 0 };
      P.bridge.cancelSleepTimer();
      P.bus.emit('sleep', S.state.sleep);
    },
    isActive: function () { return S.state.sleep.active; },
    remaining: function () {
      var s = S.state.sleep;
      if (!s.active) return 0;
      if (localEndsAt) return Math.max(0, localEndsAt - Date.now());
      return Math.max(0, s.remainingMs || 0);
    },
    label: function () { return !S.state.sleep.active ? 'Sleep' : U.fmtTime(SleepTimer.remaining() / 1000); },
    tick: function () {
      var s = S.state.sleep;
      if (!s.active) return;
      if (localEndsAt) s.remainingMs = Math.max(0, localEndsAt - Date.now());
      else if (s.remainingMs > 0) s.remainingMs = Math.max(0, s.remainingMs - 1000);
    },
    sync: function (payload) {
      if (payload.endsAt) localEndsAt = payload.endsAt;
      if (!payload.active) localEndsAt = 0;
    }
  };
  P.bus.on('sleep', SleepTimer.sync);
  P.sleep = SleepTimer;
})(window);