(function (global) {
  'use strict';

  var COLLECTOR = '{{BASE_URL}}/verification/event';
  var FIXTURE_ID = '{{FIXTURE_ID}}';
  var VENDOR_KEY = 'engage-fixture';
  var MAX_EVENTS = 64;
  var MAX_BODY_CHARS = 2048;
  var sent = 0;
  var allowed = {
    isSupported: true, clientError: true,
    sessionStart: true, sessionFinish: true, sessionError: true,
    impression: true, loaded: true, geometryChange: true,
    start: true, firstQuartile: true, midpoint: true, thirdQuartile: true,
    complete: true, pause: true, resume: true, skipped: true, volumeChange: true
  };

  function text(value, limit) {
    return typeof value === 'string' ? value.slice(0, limit) : undefined;
  }

  function normalizedEvent(event) {
    var result = {};
    if (!event || typeof event !== 'object') return result;
    var type = text(event.type, 64);
    var adSessionId = text(event.adSessionId, 128);
    if (type) result.type = type;
    if (adSessionId) result.adSessionId = adSessionId;
    if (typeof event.timestamp === 'number' && isFinite(event.timestamp)) result.timestamp = event.timestamp;
    return result;
  }

  function post(kind, detail) {
    if (!allowed[kind] || sent >= MAX_EVENTS) return;
    var body;
    try {
      body = JSON.stringify({ fixtureId: FIXTURE_ID, kind: kind, detail: detail || {} });
    } catch (_ignored) { return; }
    if (body.length > MAX_BODY_CHARS) return;
    sent += 1;
    var url = COLLECTOR + '/' + encodeURIComponent(kind) + '/' + encodeURIComponent(FIXTURE_ID);
    try {
      if (typeof global.fetch === 'function') {
        var request = global.fetch(url, {
          method: 'POST', headers: { 'content-type': 'application/json' }, body: body,
          credentials: 'omit', cache: 'no-store', keepalive: false
        });
        if (request && typeof request.catch === 'function') request.catch(function () {});
      } else if (typeof global.XMLHttpRequest === 'function') {
        var xhr = new global.XMLHttpRequest();
        xhr.open('POST', url, true);
        xhr.setRequestHeader('content-type', 'application/json');
        xhr.send(body);
      }
    } catch (_ignored) {}
  }

  try {
    var versions = global.OmidVerificationClient;
    var versionNames = versions && typeof versions === 'object' ? Object.keys(versions) : [];
    if (!versionNames.length) { post('clientError', { reason: 'clientUnavailable' }); return; }
    var version = versionNames[0];
    var Client = versions[version];
    if (typeof Client !== 'function') { post('clientError', { reason: 'clientUnavailable' }); return; }
    var client = new Client();
    var supported = client.isSupported() === true;
    post('isSupported', { supported: supported, clientVersion: text(version, 64) });
    if (!supported) return;

    client.registerSessionObserver(function (event) {
      var type = event && event.type;
      if (type === 'sessionStart' || type === 'sessionFinish' || type === 'sessionError') post(type, normalizedEvent(event));
    }, VENDOR_KEY);

    ['impression', 'loaded', 'geometryChange', 'start', 'firstQuartile', 'midpoint',
      'thirdQuartile', 'complete', 'pause', 'resume', 'skipped', 'volumeChange']
      .forEach(function (type) {
        client.addEventListener(type, function (event) { post(type, normalizedEvent(event)); });
      });
  } catch (error) {
    post('clientError', { reason: 'initializationFailed', name: text(error && error.name, 64) });
  }
})(typeof window !== 'undefined' ? window : globalThis);
