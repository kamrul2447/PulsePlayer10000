/* ============================================================================
   Pulse Player — toast
   Small, non-blocking confirmation. Never used for errors we can't act on.
   ========================================================================== */
(function (global) {
  'use strict';
  var P = global.Pulse = global.Pulse || {};
  var el = null, timer = null, queue = [], showing = false;

  function next() {
    if (showing || !queue.length) return;
    showing = true;
    var item = queue.shift();
    el.textContent = item.message;
    el.classList.add('is-visible');
    timer = setTimeout(function () {
      el.classList.remove('is-visible');
      setTimeout(function () { showing = false; next(); }, 180);
    }, item.duration);
  }

  P.toast = {
    show: function (message, duration) {
      el = el || document.getElementById('toast');
      if (!el || !message) return;
      queue.push({ message: message, duration: duration || 2000 });
      if (queue.length > 3) queue.splice(0, queue.length - 3);
      next();
    }
  };
})(window);