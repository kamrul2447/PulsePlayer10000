/* ============================================================================
   Pulse Player — shared UI building blocks
   Row renderers, artwork blocks, sections, rails, empty + loading states.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var S = P.store;
  var ui = {};

  ui.art = function (track, size, cls) {
    var box = U.h('div.' + (cls || 'art'), { style: { width: '100%', height: '100%', position: 'relative' } });
    var img = U.h('img', { alt: '', loading: 'lazy', decoding: 'async', style: { width: '100%', height: '100%', objectFit: 'cover' } });
    P.artwork.mount(img, track, size || 160);
    box.appendChild(img);
    return box;
  };

  ui.groupArt = function (group, size, cls, round) {
    var box = U.h('div.' + (cls || 'art'), { style: { width: '100%', height: '100%', position: 'relative' } });
    var img = U.h('img', { alt: '', loading: 'lazy', decoding: 'async', style: { width: '100%', height: '100%', objectFit: 'cover' } });
    var src = P.artwork.urlForGroup(group, size || 220);
    img.onerror = function () {
      img.onerror = null;
      img.src = P.artwork.buildFallback(group.id || group.name || 'g', group.name, size || 220);
    };
    img.src = src;
    box.appendChild(img);
    if (round) box.appendChild(U.h('div.gridcard__placeholder', { style: { display: 'none' } }));
    return box;
  };

  ui.trackRow = function (track, opts) {
    opts = opts || {};
    var isCurrent = track.uid === S.state.currentUid;
    var artBox = U.h('div.track__art');
    artBox.appendChild(ui.art(track, 120));
    var body = U.h('div.track__body',
      U.h('div.track__title', { text: U.trackTitle(track) }),
      U.h('div.track__sub', { text: U.trackArtist(track) + (U.trackAlbum(track) ? ' · ' + U.trackAlbum(track) : '') })
    );
    var end = U.h('div.track__end');
    if (isCurrent && !opts.showIndex) {
      end.appendChild(U.h('span.eq' + (S.state.player.playing ? '' : ' is-paused'), {},
        U.h('span'), U.h('span'), U.h('span')));
    }
    end.appendChild(U.h('span.track__dur', { text: U.fmtTime((track.duration || 0) / 1000) }));
    if (opts.showMore !== false) {
      end.appendChild(U.h('button.iconbtn', {
        type: 'button', 'aria-label': 'Options for ' + U.trackTitle(track),
        onclick: function (e) { e.stopPropagation(); P.sheet.trackOptions(track); }
      }, U.icon('i-more')));
    }
    var row = U.h('div.track' + (isCurrent ? '.is-current' : ''), {
      role: 'button', tabindex: '0', dataset: { uid: track.uid },
      'aria-label': U.trackTitle(track) + ' by ' + U.trackArtist(track),
      onclick: function (e) { U.ripple(e); (opts.onClick || defaultPlay)(track, opts); },
      onkeydown: function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); (opts.onClick || defaultPlay)(track, opts); }
      },
      oncontextmenu: function (e) { e.preventDefault(); P.sheet.trackOptions(track); }
    });
    if (opts.showIndex) row.appendChild(U.h('div.track__index', { text: String(opts.index + 1) }));
    row.appendChild(artBox);
    row.appendChild(body);
    row.appendChild(end);
    return row;
  };

  function defaultPlay(track, opts) {
    if (track && track.archiveKey) { P.bridge.playArchived(track); return; }
    if (track && track.source === 'online') { P.playback.playRemote(track); return; }
    var list = (opts && opts.list) || ui.lastList || P.library.songs();
    var idx = list.findIndex(function (t) { return t.uid === track.uid; });
    P.playback.playList(list, idx < 0 ? 0 : idx, opts && opts.source ? opts.source : 'library');
  }

  ui.gridCard = function (group, onClick, round) {
    var card = U.h('div.gridcard' + (round ? '.gridcard--round' : ''), {
      role: 'button', tabindex: '0',
      onclick: function (e) { U.ripple(e, card); onClick(group); },
      onkeydown: function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(group); } }
    });
    var art = U.h('div.gridcard__art');
    art.appendChild(ui.groupArt(group, 220, '', round));
    card.appendChild(art);
    card.appendChild(U.h('div.gridcard__title', { text: group.name }));
    card.appendChild(U.h('div.gridcard__sub', { text: round ? U.fmtCount(group.trackCount) : (group.artist || U.fmtCount(group.trackCount)) }));
    return card;
  };

  ui.railCard = function (track, onClick, sub) {
    var card = U.h('div.railcard', {
      role: 'button', tabindex: '0',
      onclick: function (e) { U.ripple(e, card); onClick(track); },
      onkeydown: function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(track); } }
    });
    var art = U.h('div.railcard__art');
    art.appendChild(ui.art(track, 200));
    card.appendChild(art);
    card.appendChild(U.h('div.railcard__title', { text: U.trackTitle(track) }));
    card.appendChild(U.h('div.railcard__sub', { text: sub || U.trackArtist(track) }));
    return card;
  };

  ui.section = function (title, linkLabel, onClick) {
    return U.h('div.section',
      U.h('h2.section__title', { text: title }),
      linkLabel ? U.h('button.section__link', { type: 'button', text: linkLabel, onclick: onClick }) : null
    );
  };

  ui.rail = function (title, nodeList, opts) {
    opts = opts || {};
    return U.h('div.rail',
      ui.section(title, opts.linkLabel, opts.onLink),
      U.h('div.rail__scroll.stagger', {}, nodeList)
    );
  };

  ui.empty = function (opts) {
    opts = opts || {};
    var node = U.h('div.empty',
      U.h('div.empty__glyph', {}, U.icon(opts.icon || 'i-note', 'ico--lg')),
      U.h('div.empty__title', { text: opts.title || 'Nothing here yet' }),
      U.h('p.empty__text', { text: opts.text || '' })
    );
    if (opts.action) {
      node.appendChild(U.h('div.empty__action', {},
        U.h('button.actionbtn', { type: 'button', text: opts.action.label, onclick: opts.action.onClick })
      ));
    }
    return node;
  };

  ui.loading = function (rows) {
    var wrap = U.h('div');
    for (var i = 0; i < (rows || 6); i++) {
      wrap.appendChild(U.h('div.skel-row',
        U.h('div.skel.skel-box'),
        U.h('div.grow',
          U.h('div.skel', { style: { height: '12px', width: (50 + (i * 7) % 35) + '%', marginBottom: '8px' } }),
          U.h('div.skel', { style: { height: '10px', width: (28 + (i * 5) % 22) + '%' } })
        )
      ));
    }
    return wrap;
  };

  ui.scanBar = function (text) {
    return U.h('div',
      U.h('div.scanbar', {}, U.h('span')),
      U.h('div.listhead', {}, U.h('span.listhead__count', { text: text || 'Scanning your device…' }))
    );
  };
  ui.divider = function () { return U.h('div', { style: { height: '1px', background: 'var(--border)', margin: '16px 0' } }); };
  P.ui = ui;
})(window);