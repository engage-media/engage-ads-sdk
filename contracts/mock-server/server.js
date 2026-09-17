'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { URL } = require('node:url');

const FIXTURES = path.resolve(__dirname, '..', 'fixtures');
const SUCCESS_SCENARIOS = new Set(['banner', 'banner-two-part', 'native', 'native-video', 'interstitial', 'rewarded', 'rewarded-skippable', 'pod', 'nurl-markup', 'omid-inline', 'omid-wrapper', 'omid-pod', 'omid-failure', 'omid-banner', 'omid-native']);
const STATIC_RESPONSE_SCENARIOS = new Set(['no-fill-empty-seatbid', 'no-fill-empty-bid', 'malformed-seatbid-type', 'malformed-bid-type', 'unmatched-extra-bid', 'invalid-price-string', 'invalid-price-boolean', 'invalid-native-missing-required', 'native-id-zero', 'wrong-request-id', 'negative-price', 'missing-burl']);
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_MEASUREMENT_BODY_BYTES = 4096;
const MAX_MEASUREMENT_RECORDS = 512;
const MEASUREMENT_EVENTS = new Set(['isSupported', 'clientError', 'sessionStart', 'sessionFinish', 'sessionError', 'impression', 'loaded', 'geometryChange', 'start', 'firstQuartile', 'midpoint', 'thirdQuartile', 'complete', 'pause', 'resume', 'skipped', 'volumeChange', 'verificationNotExecuted']);

function read(relative) {
  return fs.readFileSync(path.join(FIXTURES, relative));
}

function replaceTokens(value, replacements) {
  if (typeof value === 'string') {
    let result = value;
    for (const [token, replacement] of Object.entries(replacements)) result = result.split(token).join(replacement);
    return result;
  }
  if (Array.isArray(value)) return value.map(item => replaceTokens(item, replacements));
  if (value && typeof value === 'object') {
    const result = {};
    for (const [key, item] of Object.entries(value)) result[key] = replaceTokens(item, replacements);
    return result;
  }
  return value;
}

function send(response, status, contentType, body, extraHeaders = {}) {
  const data = Buffer.isBuffer(body) ? body : Buffer.from(String(body));
  response.writeHead(status, { 'content-type': contentType, 'content-length': data.length, 'cache-control': 'no-store', ...extraHeaders });
  response.end(data);
}

function json(response, status, value) {
  send(response, status, 'application/json; charset=utf-8', JSON.stringify(value));
}

function vastCorsHeaders(request) {
  const origin = request.headers.origin;
  return origin
    ? { 'access-control-allow-origin': origin, 'access-control-allow-credentials': 'true', vary: 'Origin' }
    : { 'access-control-allow-origin': '*' };
}

function readBody(request, maxBytes = MAX_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    request.on('data', chunk => {
      length += chunk.length;
      if (length > maxBytes) {
        reject(Object.assign(new Error('request body too large'), { statusCode: 413 }));
        request.destroy();
      } else chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function createMockServer() {
  const state = { requests: [], notices: [], measurement: { scriptRequests: [], events: [] } };
  const server = http.createServer(async (request, response) => {
    const origin = `http://${request.headers.host}`;
    const url = new URL(request.url, origin);
    const pathname = url.pathname;
    try {
      if (request.method === 'GET' && pathname === '/_inspect') return json(response, 200, state);
      if (request.method === 'POST' && pathname === '/_reset') {
        await readBody(request);
        state.requests.length = 0;
        state.notices.length = 0;
        state.measurement.scriptRequests.length = 0;
        state.measurement.events.length = 0;
        return json(response, 200, { reset: true });
      }

      if (request.method === 'OPTIONS' && pathname.startsWith('/verification/')) {
        response.writeHead(204, { ...vastCorsHeaders(request), 'access-control-allow-methods': 'GET, POST, OPTIONS', 'access-control-allow-headers': 'content-type', 'access-control-max-age': '300' });
        return response.end();
      }

      const verificationScript = pathname.match(/^\/verification\/(omid-verification\.js|omid-failure\.js)$/);
      if (verificationScript) {
        if (request.method !== 'GET') return json(response, 405, { error: 'GET required' });
        const id = url.searchParams.get('id') || '';
        if (!/^[A-Za-z0-9._-]{1,64}$/.test(id)) return json(response, 400, { error: 'invalid fixture id' });
        if (state.measurement.scriptRequests.length >= MAX_MEASUREMENT_RECORDS) return json(response, 429, { error: 'measurement record limit reached' });
        state.measurement.scriptRequests.push({ resource: verificationScript[1], id, method: 'GET', query: { id } });
        const headers = vastCorsHeaders(request);
        if (verificationScript[1] === 'omid-failure.js') return send(response, 404, 'application/javascript; charset=utf-8', '/* deterministic failed verification resource */', headers);
        const source = read('verification/omid-verification.js').toString('utf8')
          .replaceAll('{{BASE_URL}}', origin)
          .replaceAll('{{FIXTURE_ID}}', id);
        return send(response, 200, 'application/javascript; charset=utf-8', source, headers);
      }

      const verificationEvent = pathname.match(/^\/verification\/event\/([^/]+)\/([^/]+)$/);
      if (verificationEvent) {
        if (request.method !== 'GET' && request.method !== 'POST') return json(response, 405, { error: 'GET or POST required' });
        const event = decodeURIComponent(verificationEvent[1]);
        const id = decodeURIComponent(verificationEvent[2]);
        if (!MEASUREMENT_EVENTS.has(event) || !/^[A-Za-z0-9._-]{1,64}$/.test(id)) return json(response, 400, { error: 'invalid measurement event' });
        if (state.measurement.events.length >= MAX_MEASUREMENT_RECORDS) return json(response, 429, { error: 'measurement record limit reached' });
        let payload = null;
        if (request.method === 'POST') {
          if (!String(request.headers['content-type'] || '').toLowerCase().startsWith('application/json')) return json(response, 415, { error: 'application/json required' });
          let submitted;
          try { submitted = JSON.parse(await readBody(request, MAX_MEASUREMENT_BODY_BYTES)); }
          catch (error) { if (error.statusCode) throw error; return json(response, 400, { error: 'invalid measurement JSON' }); }
          if (!submitted || submitted.fixtureId !== id || submitted.kind !== event || !submitted.detail || typeof submitted.detail !== 'object' || Array.isArray(submitted.detail)) return json(response, 400, { error: 'measurement payload mismatch' });
          const detail = {};
          if (typeof submitted.detail.supported === 'boolean') detail.supported = submitted.detail.supported;
          for (const key of ['clientVersion', 'reason', 'name', 'type']) if (typeof submitted.detail[key] === 'string') detail[key] = submitted.detail[key].slice(0, 128);
          if (typeof submitted.detail.adSessionId === 'string') detail.adSessionId = submitted.detail.adSessionId.slice(0, 128);
          if (typeof submitted.detail.timestamp === 'number' && Number.isFinite(submitted.detail.timestamp)) detail.timestamp = submitted.detail.timestamp;
          payload = { fixtureId: id, kind: event, detail };
        }
        const reason = url.searchParams.get('reason');
        state.measurement.events.push({ event, id, method: request.method, payload, query: reason === null ? {} : { reason: reason.slice(0, 128) } });
        return send(response, 200, 'application/json; charset=utf-8', JSON.stringify({ received: true }), vastCorsHeaders(request));
      }

      const tracking = pathname.match(/^\/(notice|track)\/([^/]+)\/([^/]+)$/);
      if (tracking) {
        const body = request.method === 'GET' || request.method === 'HEAD' ? '' : await readBody(request);
        state.notices.push({ category: tracking[1], kind: tracking[2], id: decodeURIComponent(tracking[3]), method: request.method, query: Object.fromEntries(url.searchParams), body });
        return json(response, 200, { received: true });
      }
      if (pathname === '/markup/banner') {
        state.notices.push({ category: 'notice', kind: 'nurl-markup', id: url.searchParams.get('bid') || '', method: request.method, query: Object.fromEntries(url.searchParams), body: '' });
        const creative = read('creatives/banner-mraid.html').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        return send(response, 200, 'text/html; charset=utf-8', creative);
      }

      const openRtb = pathname.match(/^\/openrtb\/2\.6\/([^/]+)$/);
      if (openRtb) {
        if (request.method !== 'POST') return json(response, 405, { error: 'POST required' });
        const raw = await readBody(request);
        let auctionRequest;
        try { auctionRequest = JSON.parse(raw); }
        catch { return json(response, 400, { error: 'invalid request JSON' }); }
        state.requests.push({
          scenario: openRtb[1],
          id: auctionRequest.id || null,
          impid: auctionRequest.imp && auctionRequest.imp[0] ? auctionRequest.imp[0].id : null,
          headers: {
            'x-openrtb-version': request.headers['x-openrtb-version'] || null,
            'content-type': request.headers['content-type'] || null
          },
          body: auctionRequest
        });
        const scenario = openRtb[1];
        if (scenario === 'no-fill') { response.writeHead(204, { 'cache-control': 'no-store' }); return response.end(); }
        if (scenario === 'malformed') return send(response, 200, 'application/json; charset=utf-8', '{"id":');
        if (scenario === 'ambiguous') {
          const impid = auctionRequest.imp && auctionRequest.imp[0] ? auctionRequest.imp[0].id : 'imp-1';
          return json(response, 200, { id: auctionRequest.id, seatbid: [{ seat: 'a', bid: [{ id: 'ambiguous-1', impid, price: 1, adm: '<p>one</p>' }] }, { seat: 'b', bid: [{ id: 'ambiguous-2', impid, price: 2, adm: '<p>two</p>' }] }], cur: 'USD' });
        }
        if (STATIC_RESPONSE_SCENARIOS.has(scenario)) {
          let fixture = JSON.parse(read(`responses/${scenario}.json`).toString('utf8'));
          fixture = replaceTokens(fixture, { '{{BASE_URL}}': origin });
          if (scenario !== 'wrong-request-id') fixture.id = auctionRequest.id;
          const runtimeImpid = auctionRequest.imp && auctionRequest.imp[0] ? auctionRequest.imp[0].id : null;
          if (runtimeImpid && Array.isArray(fixture.seatbid)) {
            for (const seat of fixture.seatbid) {
              if (!Array.isArray(seat.bid)) continue;
              for (const bid of seat.bid) if (bid.impid === 'imp-1') bid.impid = runtimeImpid;
            }
          }
          return json(response, 200, fixture);
        }
        let effectiveScenario = scenario === 'delayed' ? (url.searchParams.get('fixture') || 'banner') : scenario;
        if (scenario === 'auto') {
          const impression = auctionRequest.imp?.[0];
          if (!impression) return json(response, 400, { error: 'one impression is required' });
          if (impression.native) effectiveScenario = 'native';
          else if (impression.video) effectiveScenario = impression.rwdd === 1 ? 'rewarded' :
            (impression.video.poddur || impression.video.maxseq > 1 ? 'pod' : 'interstitial');
          else if (impression.banner) effectiveScenario = 'banner';
          else return json(response, 400, { error: 'unsupported fixture format' });
        }
        if (!SUCCESS_SCENARIOS.has(effectiveScenario)) return json(response, 404, { error: 'unknown scenario' });
        const vastLinear = read('vast/linear.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const vastPod = read('vast/pod.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const vastRewardedSkippable = read('vast/rewarded-skippable.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const vastOmidInline = read('vast/omid-inline.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const vastOmidWrapper = read('vast/omid-wrapper.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const vastOmidPod = read('vast/omid-pod.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const vastOmidFailure = read('vast/omid-failure.xml').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const bannerTwoPart = read('creatives/banner-two-part.html').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        const omidBanner = read('creatives/banner-omid.html').toString('utf8').replaceAll('{{BASE_URL}}', origin);
        let auctionResponse = JSON.parse(read(`responses/${effectiveScenario}.json`).toString('utf8'));
        auctionResponse = replaceTokens(auctionResponse, {
          '{{BASE_URL}}': origin,
          '{{VAST_LINEAR}}': vastLinear,
          '{{VAST_POD}}': vastPod,
          '{{VAST_REWARDED_SKIPPABLE}}': vastRewardedSkippable,
          '{{BANNER_TWO_PART}}': bannerTwoPart,
          '{{VAST_OMID_INLINE}}': vastOmidInline,
          '{{VAST_OMID_WRAPPER}}': vastOmidWrapper,
          '{{VAST_OMID_POD}}': vastOmidPod,
          '{{VAST_OMID_FAILURE}}': vastOmidFailure,
          '{{OMID_BANNER}}': omidBanner
        });
        if (scenario === 'auto' && effectiveScenario === 'native') {
          const requested = JSON.parse(auctionRequest.imp[0].native.request).assets;
          const native = JSON.parse(auctionResponse.seatbid[0].bid[0].adm);
          const templates = native.assets;
          native.assets = requested.map(asset => {
            const type = ['title', 'img', 'data', 'video'].find(key => asset[key]);
            const template = templates.find(candidate => candidate[type]);
            if (type === 'video') return { id: asset.id, video: { vasttag: vastLinear } };
            return template ? { ...template, id: asset.id } : null;
          }).filter(Boolean);
          auctionResponse.seatbid[0].bid[0].adm = JSON.stringify(native);
        }
        auctionResponse.id = auctionRequest.id;
        const impid = auctionRequest.imp && auctionRequest.imp[0] ? auctionRequest.imp[0].id : null;
        if (impid) auctionResponse.seatbid[0].bid[0].impid = impid;
        const respond = () => json(response, 200, auctionResponse);
        if (scenario === 'delayed') {
          const requestedDelay = Number(url.searchParams.get('ms') || 250);
          return setTimeout(respond, Number.isFinite(requestedDelay) ? Math.max(0, Math.min(5000, requestedDelay)) : 250);
        }
        return respond();
      }

      const staticRoutes = new Map([
        ['/vast/linear.xml', ['vast/linear.xml', 'application/xml; charset=utf-8']],
        ['/vast/wrapper.xml', ['vast/wrapper.xml', 'application/xml; charset=utf-8']],
        ['/vast/pod.xml', ['vast/pod.xml', 'application/xml; charset=utf-8']],
        ['/vast/rewarded-skippable.xml', ['vast/rewarded-skippable.xml', 'application/xml; charset=utf-8']],
        ['/vast/media-error.xml', ['vast/media-error.xml', 'application/xml; charset=utf-8']],
        ['/vast/omid-inline.xml', ['vast/omid-inline.xml', 'application/xml; charset=utf-8']],
        ['/vast/omid-wrapper.xml', ['vast/omid-wrapper.xml', 'application/xml; charset=utf-8']],
        ['/vast/omid-pod.xml', ['vast/omid-pod.xml', 'application/xml; charset=utf-8']],
        ['/vast/omid-failure.xml', ['vast/omid-failure.xml', 'application/xml; charset=utf-8']],
        ['/vast/malformed.xml', ['vast/malformed.xml', 'application/xml; charset=utf-8']],
        ['/vast/empty.xml', ['vast/empty.xml', 'application/xml; charset=utf-8']],
        ['/creatives/banner-mraid.html', ['creatives/banner-mraid.html', 'text/html; charset=utf-8']],
        ['/creatives/banner-two-part.html', ['creatives/banner-two-part.html', 'text/html; charset=utf-8']],
        ['/creatives/banner-expanded.html', ['creatives/banner-expanded.html', 'text/html; charset=utf-8']],
        ['/creatives/interstitial-mraid.html', ['creatives/interstitial-mraid.html', 'text/html; charset=utf-8']],
        ['/creatives/banner-omid.html', ['creatives/banner-omid.html', 'text/html; charset=utf-8']],
        ['/assets/native-main.svg', ['assets/native-main.svg', 'image/svg+xml']],
        ['/assets/native-main.png', ['assets/native-main.png', 'image/png']],
        ['/media/test-card.mp4', ['media/test-card.mp4', 'video/mp4']],
        ['/media/test-card-15s.mp4', ['media/test-card-15s.mp4', 'video/mp4']]
      ]);
      if (pathname === '/vast/no-fill.xml') { response.writeHead(204, { 'cache-control': 'no-store', ...vastCorsHeaders(request) }); return response.end(); }
      if (staticRoutes.has(pathname)) {
        const [file, type] = staticRoutes.get(pathname);
        const binary = type === 'video/mp4' || type === 'image/png';
        const body = binary ? read(file) : read(file).toString('utf8').replaceAll('{{BASE_URL}}', origin);
        if (type === 'video/mp4' && request.headers.range) {
          const match = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range);
          if (!match) return send(response, 416, 'text/plain; charset=utf-8', 'invalid range', { 'content-range': `bytes */${body.length}` });
          const start = match[1] === '' ? Math.max(0, body.length - Number(match[2])) : Number(match[1]);
          const end = match[1] === '' || match[2] === '' ? body.length - 1 : Math.min(body.length - 1, Number(match[2]));
          if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || start > end || start >= body.length) return send(response, 416, 'text/plain; charset=utf-8', 'unsatisfiable range', { 'content-range': `bytes */${body.length}` });
          return send(response, 206, type, body.subarray(start, end + 1), { 'accept-ranges': 'bytes', 'content-range': `bytes ${start}-${end}/${body.length}` });
        }
        const extraHeaders = {
          ...(type === 'video/mp4' ? { 'accept-ranges': 'bytes' } : {}),
          ...(type.startsWith('application/xml') ? vastCorsHeaders(request) : {})
        };
        return send(response, 200, type, body, extraHeaders);
      }
      if (pathname.startsWith('/landing/')) return send(response, 200, 'text/html; charset=utf-8', '<!doctype html><title>Fixture landing</title><p>No external navigation.</p>');
      return json(response, 404, { error: 'not found' });
    } catch (error) {
      if (!response.headersSent) json(response, error.statusCode || 500, { error: error.message });
      else response.destroy(error);
    }
  });
  return { server, state };
}

function listen(port = 0, host = '127.0.0.1') {
  const instance = createMockServer();
  return new Promise((resolve, reject) => {
    instance.server.once('error', reject);
    instance.server.listen(port, host, () => {
      instance.server.removeListener('error', reject);
      const address = instance.server.address();
      resolve({ ...instance, origin: `http://${address.address}:${address.port}`, close: () => new Promise((done, fail) => instance.server.close(error => error ? fail(error) : done())) });
    });
  });
}

if (require.main === module) {
  const index = process.argv.indexOf('--port');
  const port = index >= 0 ? Number(process.argv[index + 1]) : 8787;
  listen(port).then(instance => process.stdout.write(`Engage fixture server listening at ${instance.origin}\n`)).catch(error => { process.stderr.write(`${error.stack || error}\n`); process.exitCode = 1; });
}

module.exports = { createMockServer, listen };
