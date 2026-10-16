/* ============================================================================
   Pulse Player — bottom sheet
   Shared sheet for queue, track options, sleep timer and information screens.
   Supports drag-to-dismiss and Android back.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var root, backdrop, panel, headEl, bodyEl, onCloseCb = null;

  function ensure() {
    if (root) return;
    root = document.getElementById('sheet');
    backdrop = document.getElementById('sheetBackdrop');
    panel = document.getElementById('sheetPanel');
    headEl = document.getElementById('sheetHead');
    bodyEl = document.getElementById('sheetBody');
    backdrop.addEventListener('click', Sheet.close);

    var startY = 0, dragging = false, dy = 0;
    panel.addEventListener('touchstart', function (e) {
      if (e.target.closest('input, .sheet__body')) return;
      startY = e.touches[0].clientY; dragging = true; dy = 0;
      panel.style.transition = 'none';
    }, { passive: true });
    panel.addEventListener('touchmove', function (e) {
      if (!dragging) return;
      dy = Math.max(0, e.touches[0].clientY - startY);
      panel.style.transform = 'translateY(' + dy + 'px)';
    }, { passive: true });
    panel.addEventListener('touchend', function () {
      if (!dragging) return;
      dragging = false;
      panel.style.transition = '';
      panel.style.transform = '';
      if (dy > 110) Sheet.close();
    });
  }

  var Sheet = {
    open: function (opts) {
      ensure();
      opts = opts || {};
      U.clear(headEl);
      U.clear(bodyEl);
      if (opts.title) headEl.appendChild(U.h('div', { text: opts.title }));
      var content = opts.body;
      if (Array.isArray(content)) content.forEach(function (n) { bodyEl.appendChild(n); });
      else if (content) bodyEl.appendChild(content);
      onCloseCb = opts.onClose || null;
      root.classList.add('is-open');
      root.setAttribute('aria-hidden', 'false');
      bodyEl.scrollTop = 0;
      P.bus.emit('sheet', { open: true, title: opts.title });
    },
    close: function () {
      ensure();
      if (!root.classList.contains('is-open')) return false;
      root.classList.remove('is-open');
      root.setAttribute('aria-hidden', 'true');
      var cb = onCloseCb; onCloseCb = null;
      U.clear(bodyEl);
      U.clear(headEl);
      if (cb) cb();
      P.bus.emit('sheet', { open: false });
      return true;
    },
    isOpen: function () { return !!root && root.classList.contains('is-open'); },
    row: function (opts) {
      return U.h('button.sheetrow', {
        type: 'button',
        onclick: function (e) { U.ripple(e); if (opts.onClick) opts.onClick(); }
      },
        opts.icon ? U.icon(opts.icon) : null,
        U.h('span.sheetrow__text', { text: opts.label }),
        opts.value ? U.h('span.rowitem__value', { text: opts.value }) : null,
        opts.trailing || null
      );
    },
    trackOptions: function (track, extra) {
      var isOnline = track && track.source === 'online';
      var isArchived = track && track.archiveKey;
      var rows = [];
      rows.push(Sheet.row({ icon: 'i-play', label: 'Play', onClick: function () {
        Sheet.close();
        if (isArchived) P.bridge.playArchived(track);
        else if (isOnline) P.playback.playRemote(track);
        else P.playback.playTrack(track);
      }}));

      if (isArchived) {
        rows.push(Sheet.row({ icon: 'i-archive', label: 'Restore', onClick: function () { Sheet.close(); P.bridge.restoreTrack(track); } }));
      } else {
        if (!isOnline) {
          rows.push(Sheet.row({ icon: 'i-queue', label: 'Add to Queue', onClick: function () { Sheet.close(); P.bridge.enqueueNext([track.uid]); P.toast.show('Added to queue'); } }));
          rows.push(Sheet.row({ icon: P.favorites.has(track.uid) ? 'i-heart-fill' : 'i-heart', label: P.favorites.has(track.uid) ? 'Unfavorite' : 'Favorite', onClick: function () { Sheet.close(); P.favorites.toggle(track.uid); } }));
          rows.push(Sheet.row({
            icon: 'i-trash', label: 'Delete from device',
            onClick: function () {
              var title = U.trackTitle(track);
              Sheet.open({
                title: 'Delete song?',
                body: U.h('div.deleteconfirm',
                  U.h('div.deleteconfirm__text', { text: 'This permanently removes “' + title + '” from this device.' }),
                  U.h('div.deleteconfirm__actions',
                    U.h('button.iconbtn', { type: 'button', onclick: function () { Sheet.close(); } }, U.icon('i-close')),
                    U.h('button.actionbtn.deleteconfirm__button', { type: 'button', onclick: function () { Sheet.close(); P.bridge.deleteTrack(track.uid); } }, U.icon('i-trash', 'ico--sm'), 'Delete permanently')
                  )
                )
              });
            }
          }));
        }
        rows.push(Sheet.row({ icon: 'i-archive', label: 'Archive', onClick: function () { Sheet.close(); P.bridge.archiveTrack(track); } }));
      }

      rows.push(Sheet.row({
        icon: 'i-note', label: 'Details',
        onClick: function () {
          Sheet.open({ title: 'Details', body: U.h('div', { style: { padding: '0 20px 20px' } },
            U.h('div.rowitem', U.h('div.rowitem__text', U.h('div.rowitem__title', { text: U.trackTitle(track) }), U.h('div.rowitem__sub', { text: U.trackArtist(track) }))),
            U.h('div.rowitem', U.h('div.rowitem__text', U.h('div.rowitem__title', { text: 'Album' }), U.h('div.rowitem__sub', { text: U.trackAlbum(track) || 'Unknown album' }))),
            U.h('div.rowitem', U.h('div.rowitem__text', U.h('div.rowitem__title', { text: 'Duration' }), U.h('div.rowitem__sub', { text: U.fmtDuration((track.duration || track.durationMs || 0) / 1000) })))
          ) });
        }
      }));
      if (extra) rows = rows.concat(extra);
      Sheet.open({
        title: U.trackTitle(track),
        body: [
          U.h('div.row', { style: { padding: '0 12px 12px', gap: '12px' } },
            (function () {
              var art = U.h('div', { style: { width: '56px', height: '56px', borderRadius: '12px', overflow: 'hidden', flex: 'none', background: 'var(--surface-3)' } });
              var img = U.h('img', { alt: '', style: { width: '100%', height: '100%', objectFit: 'cover' } });
              P.artwork.mount(img, track, 160);
              art.appendChild(img);
              return art;
            })(),
            U.h('div.grow',
              U.h('div', { text: U.trackArtist(track), style: { fontSize: '13px', color: 'var(--text-3)' } }),
              U.h('div', { text: U.fmtDuration((track.duration || track.durationMs || 0) / 1000) + (track.year ? ' · ' + track.year : ''), style: { fontSize: '12px', color: 'var(--text-3)', marginTop: '2px' } })
            )
          ),
          U.h('div', {}, rows)
        ]
      });
    }
  };

  P.sheet = Sheet;
})(window);