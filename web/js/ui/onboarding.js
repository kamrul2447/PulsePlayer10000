/* ============================================================================
   Pulse Player — permission onboarding
   Clear permission explanation with a direct native action; never a dead end.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var U = P.util;
  var root = null, card = null, shown = false;

  function ensure() {
    root = root || document.getElementById('onboarding');
    card = card || document.getElementById('onboardingCard');
  }

  function render(state) {
    ensure();
    if (!root || !card) return;
    if (state === 'granted' || state === 'unknown') { hide(); return; }
    U.clear(card);
    var permanent = state === 'permanently_denied';
    card.appendChild(U.h('div.onboarding__glyph', {}, U.icon(permanent ? 'i-lock' : 'i-folder')));
    card.appendChild(U.h('h2.onboarding__title', {
      text: permanent ? 'Permission was turned off' : 'Find your music'
    }));
    card.appendChild(U.h('p.onboarding__text', {
      text: permanent
        ? 'Pulse Player can no longer read the audio files on this device. Turn music permission back on in Android settings. Nothing is uploaded.'
        : 'Pulse Player reads music already stored on your phone. It asks for access to audio files only, never contacts, location, camera, microphone or SMS.'
    }));
    var actions = U.h('div.onboarding__actions');
    actions.appendChild(U.h('button.actionbtn', {
      type: 'button',
      text: permanent ? 'Open Android settings' : 'Allow music access',
      onclick: function () {
        if (permanent) P.bridge.openAppSettings();
        else P.bridge.requestPermission();
      }
    }));
    card.appendChild(actions);
    card.appendChild(U.h('p.onboarding__note', {
      text: 'You can change this any time in Android settings · Pulse Player works offline'
    }));
    show();
  }

  function show() {
    if (shown) return;
    shown = true;
    root.hidden = false;
    P.bus.emit('onboarding', true);
  }
  function hide() {
    if (!shown) return;
    shown = false;
    root.hidden = true;
    P.bus.emit('onboarding', false);
  }

  P.onboarding = { render: render, hide: hide, isShown: function () { return shown; } };
  P.bus.on('permission', render);
})(window);