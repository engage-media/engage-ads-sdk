'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const reporter = fs.readFileSync(path.join(__dirname, 'fixture-reporter.js'), 'utf8');

function execute(supported) {
  const posts = [];
  const listeners = {};
  let observer;
  let vendor;
  class VerificationClient {
    isSupported() { return supported; }
    registerSessionObserver(callback, value) { observer = callback; vendor = value; }
    addEventListener(type, callback) { listeners[type] = callback; }
  }
  const window = {
    OmidVerificationClient: { '1.6.10-fixture': VerificationClient },
    fetch(url, options) { posts.push({ url, options, body: JSON.parse(options.body) }); return Promise.resolve(); }
  };
  vm.runInNewContext(reporter, { window, globalThis: window });
  return { posts, listeners, observer: () => observer, vendor: () => vendor };
}

test('official verification-client reporter never synthesizes OMID lifecycle events', () => {
  const fixture = execute(true);
  assert.deepEqual(fixture.posts.map(item => item.body.kind), ['isSupported']);
  assert.equal(fixture.vendor(), 'engage-fixture');
  assert.ok(fixture.observer());
  assert.equal(typeof fixture.listeners.impression, 'function');

  fixture.observer()({ type: 'sessionStart', adSessionId: 'session-1', timestamp: 100, secret: 'discarded' });
  fixture.listeners.impression({ type: 'impression', adSessionId: 'session-1', timestamp: 101, data: { private: true } });
  assert.deepEqual(fixture.posts.map(item => item.body.kind), ['isSupported', 'sessionStart', 'impression']);
  assert.deepEqual(fixture.posts[2].body.detail, { type: 'impression', adSessionId: 'session-1', timestamp: 101 });
  assert.ok(fixture.posts.every(item => item.options.method === 'POST' && item.options.credentials === 'omit'));
  assert.ok(fixture.posts.every(item => item.options.body.length <= 2048));
});

test('unsupported OMID reports capability only and registers no event callbacks', () => {
  const fixture = execute(false);
  assert.deepEqual(fixture.posts.map(item => item.body), [{ fixtureId: '{{FIXTURE_ID}}', kind: 'isSupported', detail: { supported: false, clientVersion: '1.6.10-fixture' } }]);
  assert.equal(fixture.observer(), undefined);
  assert.deepEqual(Object.keys(fixture.listeners), []);
});

test('verification reporter bounds callback floods without inventing event types', () => {
  const fixture = execute(true);
  for (let index = 0; index < 100; index++) fixture.listeners.geometryChange({ type: 'geometryChange', adSessionId: `session-${index}`, timestamp: index });
  assert.equal(fixture.posts.length, 64);
  assert.equal(fixture.posts[0].body.kind, 'isSupported');
  assert.ok(fixture.posts.slice(1).every(item => item.body.kind === 'geometryChange'));
});

test('generated fixture pins the official package and preserves its license', () => {
  const fixture = fs.readFileSync(path.resolve(__dirname, '..', '..', 'contracts', 'fixtures', 'verification', 'omid-verification.js'), 'utf8');
  const license = fs.readFileSync(path.resolve(__dirname, '..', '..', 'contracts', 'fixtures', 'verification', 'LICENSE.open-measurement.txt'), 'utf8');
  assert.match(fixture, /@iabtechlab-omsdk\/open-measurement@1\.6\.10/);
  assert.match(fixture, /OmidVerificationClient/);
  assert.match(fixture, /registerSessionObserver/);
  assert.match(license, /Apache License\s+Version 2\.0/);
});

test('generated official client reports unsupported without an OMID service and invents no lifecycle', async () => {
  const posts = [];
  const context = {
    console,
    fetch(url, options) {
      posts.push({ url, body: JSON.parse(options.body) });
      return Promise.resolve({ ok: true });
    }
  };
  context.window = context;
  context.globalThis = context;
  context.top = context;
  context.parent = context;
  vm.createContext(context);
  const generated = fs.readFileSync(path.resolve(__dirname, '..', '..', 'contracts', 'fixtures', 'verification', 'omid-verification.js'), 'utf8')
    .replaceAll('{{BASE_URL}}', 'http://127.0.0.1:8787')
    .replaceAll('{{FIXTURE_ID}}', 'unsupported-runtime');
  vm.runInContext(generated, context);
  await Promise.resolve();
  assert.deepEqual(Object.keys(context.OmidVerificationClient), ['1.6.10-iab564']);
  assert.deepEqual(posts.map(item => item.body.kind), ['isSupported']);
  assert.equal(posts[0].body.detail.supported, false);
});
