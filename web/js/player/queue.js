/* ============================================================================
   Pulse Player — queue / up next
   Mirrors native queue state and preserves the currently fetched remote
   playlist snapshot for a useful Up Next sheet while its pages are loading.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;

  var Queue = {
    open: function () { Queue.render(); },
    render: function () {
      var remote = S.state.remoteQueue || [];
      if (remote.length) {
        var remoteRows = remote.map(function (item, i) {
          var art = U.h('div.queuerow__art');
          art.appendChild(P.ui.art(item, 96));
          return U.h('button.queuerow', {
            type: 'button',
            onclick: function (e) {
              U.ripple(e);
              var remaining = remote.slice(i);
              S.state.remoteQueue = remaining;
              P.bridge.playRemoteQueue(remaining);
              P.sheet.close();
            }
          }, art,
            U.h('div.grow',
              U.h('div.queuerow__title', { text: item.title || 'Unknown title' }),
              U.h('div.queuerow__sub', { text: item.artist || 'YouTube' })
            ),
            U.h('span.queuerow__sub', { text: U.fmtTime((item.durationMs || item.duration || 0) / 1000) })
          );
        });
        var remoteControls = U.h('div', { style: { display: 'flex', gap: '8px', padding: '4px 12px 14px' } },
          U.h('button.chip' + (S.state.player.shuffle ? '.is-active' : ''), {
            type: 'button', onclick: function () { P.playback.setShuffle(!S.state.player.shuffle); Queue.render(); }
          }, U.icon('i-shuffle', 'ico--sm'), 'Shuffle'),
          U.h('button.chip' + (S.state.player.repeat ? '.is-active' : ''), {
            type: 'button', onclick: function () { P.playback.cycleRepeat(); Queue.render(); }
          }, U.icon(S.state.player.repeat === 2 ? 'i-repeat-one' : 'i-repeat', 'ico--sm'), P.playback.repeatLabel().replace('Repeat ', '')),
          U.h('button.chip', {
            type: 'button', onclick: function () {
              S.state.remoteQueue = [];
              S.state.remoteQueueTitle = '';
              P.bridge.clearQueue();
              P.sheet.close();
              P.toast.show('Queue cleared');
            }
          }, U.icon('i-trash', 'ico--sm'), 'Clear')
        );
        P.sheet.open({
          title: (S.state.remoteQueueTitle || 'Playlist') + ' · ' + U.fmtCount(remote.length),
          body: U.h('div', {}, remoteControls, U.h('div.stagger', {}, remoteRows))
        });
        return;
      }

      var uids = S.state.queue.length ? S.state.queue : S.state.tracks.map(function (t) { return t.uid; });
      if (!uids.length) {
        P.sheet.open({
          title: 'Up next',
          body: P.ui.empty({ icon: 'i-queue', title: 'Nothing queued', text: 'Play something and it will appear here.' })
        });
        return;
      }

      var rows = uids.map(function (uid, i) {
        var t = S.track(uid);
        if (!t) return null;
        var isCurrent = uid === S.state.currentUid;
        var art = U.h('div.queuerow__art');
        art.appendChild(P.ui.art(t, 96));
        return U.h('button.queuerow' + (isCurrent ? '.is-current' : ''), {
          type: 'button',
          onclick: function (e) {
            U.ripple(e);
            P.bridge.playTracks(uids, i, 'queue');
            S.setCurrent(uid);
            P.bus.emit('state', S.state.player);
          }
        }, art,
          U.h('div.grow',
            U.h('div.queuerow__title', { text: U.trackTitle(t) }),
            U.h('div.queuerow__sub', { text: U.trackArtist(t) })
          ),
          isCurrent
            ? U.h('span.eq' + (S.state.player.playing ? '' : ' is-paused'), {}, U.h('span'), U.h('span'), U.h('span'))
            : U.h('span.queuerow__sub', { text: U.fmtTime((t.duration || 0) / 1000) })
        );
      }).filter(Boolean);

      var controls = U.h('div', { style: { display: 'flex', gap: '8px', padding: '4px 12px 14px' } },
        U.h('button.chip' + (S.state.player.shuffle ? '.is-active' : ''), { type: 'button', onclick: function () { P.playback.setShuffle(!S.state.player.shuffle); Queue.render(); } }, U.icon('i-shuffle', 'ico--sm'), 'Shuffle'),
        U.h('button.chip' + (S.state.player.repeat ? '.is-active' : ''), { type: 'button', onclick: function () { P.playback.cycleRepeat(); Queue.render(); } }, U.icon(S.state.player.repeat === 2 ? 'i-repeat-one' : 'i-repeat', 'ico--sm'), P.playback.repeatLabel().replace('Repeat ', '')),
        U.h('button.chip', { type: 'button', onclick: function () { P.bridge.clearQueue(); P.sheet.close(); P.toast.show('Queue cleared'); } }, U.icon('i-trash', 'ico--sm'), 'Clear')
      );

      P.sheet.open({ title: 'Up next · ' + U.fmtCount(uids.length), body: U.h('div', {}, controls, U.h('div.stagger', {}, rows)) });
    }
  };

  P.queue = Queue;
})(window);