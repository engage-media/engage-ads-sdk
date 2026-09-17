'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const { listen } = require('./server');

const fixtures = path.resolve(__dirname, '..', 'fixtures');
let instance;

test.before(async () => { instance = await listen(0); });
test.after(async () => { await instance.close(); });

async function postScenario(name, body = { id: `dynamic-${name}`, imp: [{ id: `imp-${name}` }] }) {
  return fetch(`${instance.origin}/openrtb/2.6/${name}`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-openrtb-version': '2.6' }, body: JSON.stringify(body) });
}

test('all successful scenarios are valid JSON and match dynamic request identifiers', async () => {
  for (const scenario of ['banner', 'banner-two-part', 'native', 'native-video', 'interstitial', 'rewarded', 'rewarded-skippable', 'pod', 'omid-inline', 'omid-wrapper', 'omid-pod', 'omid-failure', 'omid-banner', 'omid-native']) {
    const response = await postScenario(scenario);
    assert.equal(response.status, 200, scenario);
    const body = await response.json();
    assert.equal(body.id, `dynamic-${scenario}`);
    assert.equal(body.seatbid[0].bid[0].impid, `imp-${scenario}`);
    assert.equal(body.seatbid[0].bid.length, 1);
    assert.equal(body.seatbid[0].bid[0].nurl.startsWith(instance.origin), true);
    assert.equal(body.seatbid[0].bid[0].burl.startsWith(instance.origin), true);
  }
});

test('skippable rewarded fixture has one long local creative and a one-second skip offset', async () => {
  const auction = await (await postScenario('rewarded-skippable')).json();
  const bids = auction.seatbid.flatMap(seat => seat.bid);
  assert.equal(bids.length, 1);
  assert.match(bids[0].adm, /<Duration>00:00:15<\/Duration>/);
  assert.match(bids[0].adm, /skipoffset="00:00:01"/);
  assert.match(bids[0].adm, new RegExp(`${instance.origin}/media/test-card-15s\\.mp4`));
  const media = await fetch(`${instance.origin}/media/test-card-15s.mp4`);
  assert.equal(media.status, 200);
  assert.equal(media.headers.get('content-type'), 'video/mp4');
  assert.ok((await media.arrayBuffer()).byteLength > 100_000);
});

test('two-part banner points to a local expanded creative whose close invokes MRAID', async () => {
  const auction = await (await postScenario('banner-two-part')).json();
  const markup = auction.seatbid[0].bid[0].adm;
  assert.match(markup, new RegExp(`mraid\\.expand\\('${instance.origin}/creatives/banner-expanded\\.html'\\)`));
  const expanded = await (await fetch(`${instance.origin}/creatives/banner-expanded.html`)).text();
  assert.match(expanded, /Engage two-part expanded fixture/);
  assert.match(expanded, /onclick="mraid\.close\(\)"/);
});

test('native adm is valid Native 1.2 JSON and native-video embeds local VAST', async () => {
  const native = await (await postScenario('native')).json();
  const nativeAdm = JSON.parse(native.seatbid[0].bid[0].adm);
  assert.equal(nativeAdm.ver, '1.2');
  assert.equal(nativeAdm.assets.length, 3);
  const video = await (await postScenario('native-video')).json();
  const videoAdm = JSON.parse(video.seatbid[0].bid[0].adm);
  assert.match(videoAdm.assets[1].video.vasttag, /<VAST version='4\.2'>/);
  assert.match(videoAdm.assets[1].video.vasttag, new RegExp(instance.origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
});

test('auto endpoint selects deterministic creatives for one application endpoint', async () => {
  for (const [capabilities, expected] of [
    [{ banner: { w: 320, h: 50 } }, /mraid/],
    [{ video: { mimes: ['video/mp4'] } }, /<VAST/],
    [{ video: { poddur: 30 } }, /sequence="2"/],
    [{ video: {}, rwdd: 1 }, /<VAST/]
  ]) {
    const response = await postScenario('auto', { id: 'auto-request', imp: [{ id: 'auto-imp', ...capabilities }] });
    assert.equal(response.status, 200);
    const bid = (await response.json()).seatbid[0].bid[0];
    assert.equal(bid.impid, 'auto-imp');
    assert.match(bid.adm, expected);
  }
  const response = await postScenario('auto', { id: 'native-auto', imp: [{ id: 'native-imp', native: {
    request: JSON.stringify({ ver: '1.2', assets: [{ id: 0, title: { len: 90 } }, { id: 2, img: { type: 3 } }, { id: 4, video: {} }] })
  } }] });
  const assets = JSON.parse((await response.json()).seatbid[0].bid[0].adm).assets;
  assert.deepEqual(assets.map(asset => asset.id), [0, 2, 4]);
  assert.match(assets[2].video.vasttag, /<VAST/);
});

test('VAST wrapper, pod and media are self-contained local fixtures', async () => {
  const wrapper = await (await fetch(`${instance.origin}/vast/wrapper.xml`)).text();
  assert.match(wrapper, new RegExp(`${instance.origin}/vast/linear\\.xml`));
  const pod = await (await fetch(`${instance.origin}/vast/pod.xml`)).text();
  assert.match(pod, /sequence="1"/);
  assert.match(pod, /sequence="2"/);
  const media = await fetch(`${instance.origin}/media/test-card.mp4`);
  assert.equal(media.status, 200);
  assert.equal(media.headers.get('content-type'), 'video/mp4');
  assert.ok((await media.arrayBuffer()).byteLength > 1000);
  const range = await fetch(`${instance.origin}/media/test-card.mp4`, { headers: { range: 'bytes=0-99' } });
  assert.equal(range.status, 206);
  assert.equal((await range.arrayBuffer()).byteLength, 100);
  assert.match(range.headers.get('content-range'), /^bytes 0-99\//);
  assert.equal((await fetch(`${instance.origin}/vast/no-fill.xml`)).status, 204);
  assert.match(await (await fetch(`${instance.origin}/vast/empty.xml`)).text(), /<VAST[^>]*\/>/);
  const png = await fetch(`${instance.origin}/assets/native-main.png`);
  assert.equal(png.headers.get('content-type'), 'image/png');
  assert.ok((await png.arrayBuffer()).byteLength > 1000);
});

test('VAST responses reflect the requesting origin for credentialed IMA wrapper fetches', async () => {
  const origin = 'https://imasdk.googleapis.test';
  for (const route of ['/vast/wrapper.xml', '/vast/linear.xml', '/vast/no-fill.xml']) {
    const response = await fetch(`${instance.origin}${route}`, { headers: { origin } });
    assert.equal(response.headers.get('access-control-allow-origin'), origin, route);
    assert.equal(response.headers.get('access-control-allow-credentials'), 'true', route);
    assert.equal(response.headers.get('vary'), 'Origin', route);
  }
});

test('media-error VAST is valid fixture markup whose only media URL returns 404', async () => {
  const response = await fetch(`${instance.origin}/vast/media-error.xml`);
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^application\/xml/);
  const vast = await response.text();
  assert.match(vast, /<VAST version="4\.2"/);
  assert.match(vast, /<Ad id="media-error-1">/);
  assert.match(vast, new RegExp(`${instance.origin}/media/missing\\.mp4`));
  assert.equal((await fetch(`${instance.origin}/media/missing.mp4`)).status, 404);
});

test('no-fill, malformed, ambiguous and delayed behaviors are deterministic', async () => {
  assert.equal((await postScenario('no-fill')).status, 204);
  const malformed = await postScenario('malformed');
  assert.equal(malformed.status, 200);
  await assert.rejects(() => malformed.json());
  const ambiguous = await (await postScenario('ambiguous')).json();
  assert.equal(ambiguous.seatbid.length, 2);
  const start = Date.now();
  const delayed = await postScenario('delayed?ms=40&fixture=banner');
  assert.equal(delayed.status, 200);
  assert.ok(Date.now() - start >= 30);
});

test('nurl markup, win notices, burl and trackers are observable and resettable', async () => {
  await fetch(`${instance.origin}/_reset`, { method: 'POST' });
  const auction = await (await postScenario('nurl-markup')).json();
  const bid = auction.seatbid[0].bid[0];
  assert.equal(bid.adm, undefined);
  const markup = await (await fetch(bid.nurl)).text();
  assert.match(markup, /mraid\.open/);
  await fetch(`${instance.origin}/notice/nurl/bid-1`);
  await fetch(`${instance.origin}/notice/win/bid-1`, { method: 'POST', body: 'won' });
  await fetch(bid.burl);
  await fetch(`${instance.origin}/track/impression/bid-1`);
  await fetch(`${instance.origin}/track/click/bid-1?source=creative`);
  const inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.deepEqual(inspected.notices.map(item => item.kind), ['nurl-markup', 'nurl', 'win', 'burl', 'impression', 'click']);
  assert.equal(inspected.notices[2].body, 'won');
  assert.equal(inspected.notices[5].query.source, 'creative');
  await fetch(`${instance.origin}/_reset`, { method: 'POST' });
  assert.deepEqual(await (await fetch(`${instance.origin}/_inspect`)).json(), { requests: [], notices: [], measurement: { scriptRequests: [], events: [] } });
});

test('OMID VAST fixtures preserve inline, wrapper, pod and failed-resource verification metadata', async () => {
  await fetch(`${instance.origin}/_reset`, { method: 'POST' });
  const expected = new Map([
    ['/vast/omid-inline.xml', ['omid-inline-1']],
    ['/vast/omid-wrapper.xml', ['omid-wrapper-1']],
    ['/vast/omid-pod.xml', ['omid-pod-1', 'omid-pod-2']],
    ['/vast/omid-failure.xml', ['omid-failure-1']]
  ]);
  for (const [route, ids] of expected) {
    const response = await fetch(`${instance.origin}${route}`, { headers: { origin: 'https://imasdk.googleapis.test' } });
    assert.equal(response.status, 200, route);
    assert.equal(response.headers.get('access-control-allow-origin'), 'https://imasdk.googleapis.test');
    const xml = await response.text();
    assert.match(xml, /<VAST version="4\.2"/);
    assert.equal((xml.match(/apiFramework="omid"/g) || []).length, ids.length, route);
    assert.equal((xml.match(/browserOptional="true"/g) || []).length, ids.length, route);
    assert.ok(xml.indexOf('<Error>') < xml.indexOf('<Impression>'), `${route} follows the VAST 4.2 base sequence`);
    assert.ok(xml.indexOf('<AdVerifications>') < xml.indexOf('<Creatives>'), `${route} follows the VAST 4.2 verification sequence`);
    for (const id of ids) {
      assert.match(xml, new RegExp(`/verification/omid-(?:verification|failure)\\.js\\?id=${id}`));
      assert.match(xml, new RegExp(`event/verificationNotExecuted/${id}`));
      assert.match(xml, new RegExp(`fixture=${id}&mode=test-only`));
    }
  }
  const wrapper = await (await fetch(`${instance.origin}/vast/omid-wrapper.xml`)).text();
  assert.match(wrapper, new RegExp(`${instance.origin}/vast/omid-inline\\.xml`));
  assert.ok(wrapper.indexOf('<Creatives>') < wrapper.indexOf('<VASTAdTagURI>'), 'wrapper follows the VAST 4.2 wrapper sequence');
  const pod = await (await fetch(`${instance.origin}/vast/omid-pod.xml`)).text();
  assert.equal((pod.match(/<Ad id="omid-pod-/g) || []).length, 2);
  assert.match(pod, /sequence="1"/);
  assert.match(pod, /sequence="2"/);
  assert.equal((pod.match(/<AdServingId>/g) || []).length, 2);
  const inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.deepEqual(inspected.measurement, { scriptRequests: [], events: [] }, 'fetching VAST must not fabricate verification execution');
});

test('OpenRTB OMID fixtures advertise framework 7 and Native 1.2 uses event 555 method 2', async () => {
  for (const scenario of ['omid-inline', 'omid-wrapper', 'omid-pod', 'omid-failure', 'omid-banner', 'omid-native']) {
    const auction = await (await postScenario(scenario)).json();
    assert.deepEqual(auction.seatbid[0].bid[0].apis, [7], scenario);
  }
  const nativeRequest = JSON.parse(JSON.parse(fs.readFileSync(path.join(fixtures, 'requests/omid-native.json'), 'utf8')).imp[0].native.request);
  assert.deepEqual(nativeRequest.eventtrackers, [{ event: 555, methods: [2] }]);
  const nativeAuction = await (await postScenario('omid-native')).json();
  const native = JSON.parse(nativeAuction.seatbid[0].bid[0].adm);
  assert.equal(native.jstracker, undefined);
  assert.equal(native.eventtrackers.length, 1);
  assert.deepEqual(native.eventtrackers[0], {
    event: 555,
    method: 2,
    url: `${instance.origin}/verification/omid-verification.js?id=omid-native-1`,
    ext: { vendorKey: 'engage-fixture', verification_parameters: 'fixture=omid-native-1&mode=test-only' }
  });
});

test('verification collector separates script fetch from genuine OMID callbacks and billing', async () => {
  await fetch(`${instance.origin}/_reset`, { method: 'POST' });
  const auction = await (await postScenario('omid-inline')).json();
  let inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.deepEqual(inspected.measurement, { scriptRequests: [], events: [] });
  assert.deepEqual(inspected.notices, [], 'auction/preload must not bill or imply an impression');

  const script = await fetch(`${instance.origin}/verification/omid-verification.js?id=omid-inline-1`, { headers: { origin: 'https://imasdk.googleapis.test' } });
  assert.equal(script.status, 200);
  assert.equal(script.headers.get('access-control-allow-origin'), 'https://imasdk.googleapis.test');
  assert.match(await script.text(), /OmidVerificationClient/);
  inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.equal(inspected.measurement.scriptRequests.length, 1);
  assert.deepEqual(inspected.measurement.events, [], 'loading the client must not synthesize a session or impression');

  async function report(event, detail) {
    return fetch(`${instance.origin}/verification/event/${event}/omid-inline-1`, {
      method: 'POST', headers: { 'content-type': 'application/json', origin: 'https://imasdk.googleapis.test' },
      body: JSON.stringify({ fixtureId: 'omid-inline-1', kind: event, detail })
    });
  }
  assert.equal((await report('isSupported', { supported: true, clientVersion: '1.0.3-test' })).status, 200);
  assert.equal((await report('sessionStart', { type: 'sessionStart', adSessionId: 'bounded-session' })).status, 200);
  assert.equal((await report('impression', { type: 'impression', adSessionId: 'bounded-session' })).status, 200);
  await fetch(auction.seatbid[0].bid[0].burl);
  inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.deepEqual(inspected.measurement.events.map(item => item.event), ['isSupported', 'sessionStart', 'impression']);
  assert.deepEqual(inspected.notices.map(item => item.kind), ['burl']);
  assert.equal(inspected.measurement.events[1].payload.detail.adSessionId, 'bounded-session');
});

test('verification collector is CORS-enabled, bounded and records deterministic failure fetches', async () => {
  await fetch(`${instance.origin}/_reset`, { method: 'POST' });
  const preflight = await fetch(`${instance.origin}/verification/event/impression/omid-inline-1`, { method: 'OPTIONS', headers: { origin: 'https://imasdk.googleapis.test', 'access-control-request-method': 'POST', 'access-control-request-headers': 'content-type' } });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get('access-control-allow-origin'), 'https://imasdk.googleapis.test');
  assert.match(preflight.headers.get('access-control-allow-methods'), /POST/);

  const mismatch = await fetch(`${instance.origin}/verification/event/impression/omid-inline-1`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ fixtureId: 'another-id', kind: 'impression', detail: {} }) });
  assert.equal(mismatch.status, 400);
  assert.equal((await fetch(`${instance.origin}/verification/omid-verification.js?id=${'x'.repeat(65)}`)).status, 400);
  const failure = await fetch(`${instance.origin}/verification/omid-failure.js?id=omid-failure-1`, { headers: { origin: 'https://imasdk.googleapis.test' } });
  assert.equal(failure.status, 404);
  assert.equal(failure.headers.get('access-control-allow-origin'), 'https://imasdk.googleapis.test');
  const inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.deepEqual(inspected.measurement.scriptRequests, [{ resource: 'omid-failure.js', id: 'omid-failure-1', method: 'GET', query: { id: 'omid-failure-1' } }]);
  assert.deepEqual(inspected.measurement.events, []);
});

test('static request and response fixture JSON parses', () => {
  for (const directory of ['requests', 'responses']) {
    for (const name of fs.readdirSync(path.join(fixtures, directory))) {
      if (name.endsWith('.json')) assert.doesNotThrow(() => JSON.parse(fs.readFileSync(path.join(fixtures, directory, name), 'utf8')), `${directory}/${name}`);
    }
  }
});

test('request fixtures carry standard OpenRTB 2.6 privacy fields and valid Native requests', () => {
  for (const name of fs.readdirSync(path.join(fixtures, 'requests'))) {
    if (!name.endsWith('.json')) continue;
    const request = JSON.parse(fs.readFileSync(path.join(fixtures, 'requests', name), 'utf8'));
    assert.equal(typeof request.device.lmt, 'number', name);
    assert.equal(typeof request.regs.gdpr, 'number', name);
    assert.equal(typeof request.regs.gpp, 'string', name);
    assert.ok(Array.isArray(request.regs.gpp_sid), name);
    assert.equal(typeof request.user.consent, 'string', name);
    if (request.imp[0].native) assert.doesNotThrow(() => JSON.parse(request.imp[0].native.request), name);
  }
});

test('parser edge-case response fixtures are directly addressable', async () => {
  const scenarios = ['no-fill-empty-seatbid', 'no-fill-empty-bid', 'malformed-seatbid-type', 'malformed-bid-type', 'unmatched-extra-bid', 'invalid-price-string', 'invalid-price-boolean', 'invalid-native-missing-required', 'native-id-zero', 'negative-price', 'missing-burl'];
  for (const scenario of scenarios) {
    const response = await postScenario(scenario);
    assert.equal(response.status, 200, scenario);
    const body = await response.json();
    assert.equal(body.id, `dynamic-${scenario}`, scenario);
  }
  const zero = await (await postScenario('native-id-zero')).json();
  assert.equal(JSON.parse(zero.seatbid[0].bid[0].adm).assets[0].id, 0);
  const dynamic = { id: 'runtime-request', imp: [{ id: 'runtime-imp' }] };
  const invalidPrice = await (await postScenario('invalid-price-string', dynamic)).json();
  assert.equal(invalidPrice.seatbid[0].bid[0].impid, 'runtime-imp');
  const unmatched = await (await postScenario('unmatched-extra-bid', dynamic)).json();
  assert.deepEqual(unmatched.seatbid[0].bid.map(bid => bid.impid), ['runtime-imp', 'other-imp']);
  const wrongId = await (await postScenario('wrong-request-id', dynamic)).json();
  assert.equal(wrongId.id, 'different-auction');
});

test('inspection captures parsed OpenRTB body and protocol headers only', async () => {
  await fetch(`${instance.origin}/_reset`, { method: 'POST' });
  await fetch(`${instance.origin}/openrtb/2.6/banner`, { method: 'POST', headers: { 'content-type': 'application/json', 'x-openrtb-version': '2.6', authorization: 'must-not-be-recorded' }, body: JSON.stringify({ id: 'inspect-request', imp: [{ id: 'inspect-imp', tagid: 'inspect-placement' }] }) });
  const inspected = await (await fetch(`${instance.origin}/_inspect`)).json();
  assert.deepEqual(inspected.requests[0], {
    scenario: 'banner', id: 'inspect-request', impid: 'inspect-imp',
    headers: { 'x-openrtb-version': '2.6', 'content-type': 'application/json' },
    body: { id: 'inspect-request', imp: [{ id: 'inspect-imp', tagid: 'inspect-placement' }] }
  });
});
