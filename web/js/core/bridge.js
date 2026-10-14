(function () {
  "use strict";

  var VERSION = 1;
  var listeners = new Map();
  var sequence = 0;

  function emit(type, payload, requestId) {
    var bucket = listeners.get(type);
    if (!bucket) return;
    bucket.forEach(function (callback) {
      try { callback(payload || {}, requestId || null); }
      catch (error) { console.error("Pulse event handler failed:", type, error); }
    });
  }

  function postMessage(cmd, payload, requestId) {
    var message = {
      v: VERSION,
      cmd: String(cmd || ""),
      payload: payload && typeof payload === "object" ? payload : {},
      requestId: requestId || ("pulse-" + (++sequence))
    };

    try {
      if (window.PulseNative && typeof window.PulseNative.postMessage === "function") {
        window.PulseNative.postMessage(JSON.stringify(message));
        return true;
      }
    } catch (error) {
      console.error("Pulse native bridge unavailable:", error);
    }

    emit("bridgeUnavailable", { message: "Native playback is available inside the Pulse Player Android app." });
    return false;
  }

  function subscribe(type, callback) {
    if (!listeners.has(type)) listeners.set(type, new Set());
    listeners.get(type).add(callback);
    return function () {
      var bucket = listeners.get(type);
      if (bucket) bucket.delete(callback);
    };
  }

  // MainActivity calls this method with a JSON string for every native event.
  function onNativeEvent(raw) {
    var event;
    try {
      event = typeof raw === "string" ? JSON.parse(raw) : raw;
    } catch (error) {
      console.error("Invalid Pulse native event:", error);
      return;
    }
    if (!event || typeof event.type !== "string") return;
    emit(event.type, event.payload || {}, event.requestId || null);
  }

  window.PulseBridge = {
    onNativeEvent: onNativeEvent,
    on: subscribe,
    send: postMessage,
    isNative: function () {
      return !!(window.PulseNative && typeof window.PulseNative.postMessage === "function");
    }
  };
}());
