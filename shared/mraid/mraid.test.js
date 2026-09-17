'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, 'mraid.js'), 'utf8');

function bridge(options = {}) {
  const messages = [];
  const window = {
    EngageMraidNative: {
      postMessage(value) {
        if (options.postMessage) return options.postMessage(value);
        messages.push(JSON.parse(value));
      }
    }
  };
  if (options.setTimeout) window.setTimeout = options.setTimeout;
  vm.runInNewContext(source, { window, globalThis: window });
  return { window, mraid: window.mraid, receive: window.__engageMraid.receive, messages };
}

function ready(receive, placementType = 'inline') {
  receive({
    type: 'ready', state: 'default', placementType,
    screenSize: { width: 390, height: 844 }, maxSize: { width: 390, height: 800 },
    currentPosition: { x: 0, y: 100, width: 320, height: 50 },
    defaultPosition: { x: 0, y: 100, width: 320, height: 50 },
    currentAppOrientation: { orientation: 'portrait', locked: false }, location: null,
    supports: { sms: false, tel: false, calendar: false, storePicture: false, inlineVideo: true, location: false }
  });
}

test('publishes a MRAID 3 object and accepts one authoritative ready message', () => {
  const b = bridge();
  let count = 0;
  b.mraid.addEventListener('ready', () => count++);
  assert.equal(b.mraid.getVersion(), '3.0');
  assert.equal(b.mraid.getState(), 'loading');
  ready(b.receive);
  ready(b.receive);
  assert.equal(count, 1);
  assert.equal(b.mraid.getState(), 'default');
  assert.equal(b.mraid.getPlacementType(), 'inline');
  assert.equal(b.mraid.supports('inlineVideo'), true);
  assert.equal(b.mraid.supports('calendar'), false);
  assert.equal(b.mraid.supports('location'), false);
  assert.deepEqual({ ...b.mraid.getCurrentAppOrientation() }, { orientation: 'portrait', locked: false });
  assert.equal(b.mraid.getLocation(), -1);
  assert.deepEqual({ ...b.mraid.getScreenSize() }, { width: 390, height: 844 });
  assert.deepEqual({ ...b.mraid.getExpandProperties() }, { width: 390, height: 844, useCustomClose: false, isModal: true });
});

test('dispatches only the contract envelope with increasing integer ids', () => {
  const b = bridge();
  ready(b.receive);
  b.mraid.open('https://example.test/click');
  b.mraid.setExpandProperties({ width: 350, height: 600, useCustomClose: true });
  b.mraid.expand();
  b.mraid.setOrientationProperties({ allowOrientationChange: false, forceOrientation: 'portrait' });
  b.mraid.playVideo('https://example.test/video.mp4');
  assert.deepEqual(b.messages.map(value => Object.keys(value)), [
    ['id', 'command', 'args'], ['id', 'command', 'args'], ['id', 'command', 'args'], ['id', 'command', 'args']
  ]);
  assert.deepEqual(b.messages.map(value => value.id), [1, 2, 3, 4]);
  assert.equal(b.messages[0].command, 'open');
  assert.deepEqual(b.messages[0].args, { url: 'https://example.test/click' });
  assert.equal(b.messages[1].command, 'expand');
  assert.equal(b.messages[1].args.properties.useCustomClose, true);
  assert.deepEqual(b.messages[2].args, { allowOrientationChange: false, forceOrientation: 'portrait' });
});

test('validates properties and misuse through the MRAID error event', () => {
  const b = bridge();
  const errors = [];
  b.mraid.addEventListener('error', (message, action) => errors.push({ message, action }));
  b.mraid.open('https://example.test');
  ready(b.receive);
  b.mraid.setResizeProperties({ width: 0, height: 50, offsetX: 0, offsetY: 0, customClosePosition: 'middle', allowOffscreen: true });
  b.mraid.open('javascript:alert(1)');
  b.mraid.setOrientationProperties({ allowOrientationChange: 'yes', forceOrientation: 'sideways' });
  assert.equal(b.messages.length, 0);
  assert.deepEqual(errors.map(error => error.action), ['open', 'setResizeProperties', 'open', 'setOrientationProperties']);
});

test('reports geometry, visibility, exposure, audio, state and native errors', () => {
  const b = bridge();
  const events = [];
  for (const name of ['sizeChange', 'viewableChange', 'exposureChange', 'audioVolumeChange', 'stateChange', 'error']) {
    b.mraid.addEventListener(name, (...args) => events.push([name, ...args]));
  }
  ready(b.receive);
  b.receive({ type: 'geometry', screenSize: { width: 900, height: 400 }, maxSize: { width: 850, height: 400 }, currentPosition: { x: 2, y: 3, width: 300, height: 250 }, defaultPosition: { x: 0, y: 100, width: 320, height: 50 }, currentAppOrientation: { orientation: 'landscape', locked: true } });
  b.receive({ type: 'visibility', viewable: true, exposedPercentage: 75, visibleRectangle: { x: 2, y: 3, width: 225, height: 250 }, occlusionRectangles: [{ x: 227, y: 3, width: 75, height: 250 }] });
  b.receive({ type: 'audio', volume: 50 });
  b.receive({ type: 'state', state: 'resized' });
  b.receive({ type: 'error', message: 'native refused', action: 'resize' });
  assert.deepEqual(events.map(event => event[0]), ['sizeChange', 'viewableChange', 'exposureChange', 'audioVolumeChange', 'stateChange', 'error']);
  assert.equal(b.mraid.isViewable(), true);
  assert.equal(b.mraid.getExposedPercentage(), 75);
  assert.deepEqual({ ...b.mraid.getCurrentAppOrientation() }, { orientation: 'landscape', locked: true });
  assert.equal(b.mraid.getState(), 'resized');
});

test('listener removal is precise and listener exceptions do not break dispatch', () => {
  const b = bridge();
  let calls = 0;
  const listener = () => calls++;
  const errors = [];
  b.mraid.addEventListener('stateChange', listener);
  b.mraid.addEventListener('stateChange', () => { throw new Error('creative bug'); });
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  ready(b.receive);
  b.receive({ type: 'state', state: 'expanded' });
  b.mraid.removeEventListener('stateChange', listener);
  b.receive({ type: 'state', state: 'default' });
  assert.equal(calls, 1);
  assert.deepEqual(errors, [['creative bug', 'stateChange'], ['creative bug', 'stateChange']]);
});

test('interstitial placements reject expand and resize', () => {
  const b = bridge();
  const actions = [];
  b.mraid.addEventListener('error', (_message, action) => actions.push(action));
  ready(b.receive, 'interstitial');
  b.mraid.expand();
  b.mraid.resize();
  assert.deepEqual(actions, ['expand', 'resize']);
  assert.equal(b.messages.length, 0);
});

test('resize requires properties, accepts optional defaults, and enforces state', () => {
  const b = bridge();
  const errors = [];
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  ready(b.receive);
  b.mraid.resize();
  b.mraid.setResizeProperties({ width: 300, height: 250, offsetX: -10, offsetY: 20 });
  assert.deepEqual({ ...b.mraid.getResizeProperties() }, { width: 300, height: 250, offsetX: -10, offsetY: 20, customClosePosition: 'top-right', allowOffscreen: true });
  b.mraid.resize();
  b.receive({ type: 'state', state: 'expanded' });
  b.mraid.resize();
  assert.equal(b.messages.length, 1);
  assert.equal(b.messages[0].command, 'resize');
  assert.deepEqual(errors.map(item => item[1]), ['resize', 'resize']);
});

test('hidden and loading states reject operations while unload remains available', () => {
  const b = bridge();
  const actions = [];
  b.mraid.addEventListener('error', (_message, action) => actions.push(action));
  b.mraid.open('https://example.test');
  b.mraid.unload();
  ready(b.receive);
  b.receive({ type: 'state', state: 'hidden' });
  b.mraid.open('https://example.test');
  b.mraid.unload();
  assert.deepEqual(actions, ['open', 'open']);
  assert.deepEqual(b.messages.map(message => message.command), ['unload', 'unload']);
});

test('inline creatives can close from default state', () => {
  const b = bridge();
  ready(b.receive);
  b.mraid.close();
  assert.deepEqual(b.messages.map(message => [message.command, message.args]), [['close', {}]]);
});

test('MRAID 3 audio uses percent units, repeats host events, and rejects out-of-range input', () => {
  const b = bridge();
  const volumes = [];
  const errors = [];
  b.mraid.addEventListener('audioVolumeChange', volume => volumes.push(volume));
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  ready(b.receive);
  b.receive({ type: 'audio', volume: 75.5 });
  b.receive({ type: 'audio', volume: 75.5 });
  b.receive({ type: 'audio', volume: null });
  b.receive({ type: 'audio', volume: 101 });
  assert.deepEqual(volumes, [75.5, 75.5, null]);
  assert.equal(errors.at(-1)[1], 'receive');
  assert.equal('getAudioVolume' in b.mraid, false);
});

test('deprecated useCustomClose remains source-compatible without native dispatch', () => {
  const b = bridge();
  ready(b.receive);
  b.mraid.useCustomClose(true);
  assert.equal(b.mraid.getExpandProperties().useCustomClose, true);
  assert.equal(b.messages.length, 0);
});

test('MRAID dimension and offset properties require integers', () => {
  const b = bridge();
  const actions = [];
  b.mraid.addEventListener('error', (_message, action) => actions.push(action));
  ready(b.receive);
  b.mraid.setExpandProperties({ width: 320.5 });
  b.mraid.setResizeProperties({ width: 300, height: 250, offsetX: 0.5, offsetY: 0 });
  assert.deepEqual(actions, ['setExpandProperties', 'setResizeProperties']);
});

test('late exposure and audio listeners receive cached initial values', () => {
  const b = bridge();
  ready(b.receive);
  b.receive({ type: 'visibility', viewable: true, exposedPercentage: 40, visibleRectangle: { x: 0, y: 0, width: 128, height: 50 }, occlusionRectangles: [] });
  b.receive({ type: 'audio', volume: 25 });
  const observed = [];
  b.mraid.addEventListener('exposureChange', percent => observed.push(['exposure', percent]));
  b.mraid.addEventListener('audioVolumeChange', volume => observed.push(['audio', volume]));
  assert.deepEqual(observed, [['exposure', 40], ['audio', 25]]);
});

test('all optional-feature and lifecycle commands preserve their contract argument shape', () => {
  const b = bridge();
  ready(b.receive, 'interstitial');
  b.mraid.open('engage-fixture://landing/item');
  b.mraid.close();
  b.mraid.storePicture('https://example.test/image.png');
  b.mraid.createCalendarEvent({ description: 'Fixture', start: '2026-09-17T10:00:00Z' });
  b.mraid.unload();
  assert.deepEqual(b.messages.map(message => [message.command, message.args]), [
    ['open', { url: 'engage-fixture://landing/item' }],
    ['close', {}],
    ['storePicture', { url: 'https://example.test/image.png' }],
    ['createCalendarEvent', { event: { description: 'Fixture', start: '2026-09-17T10:00:00Z' } }],
    ['unload', {}]
  ]);
});

test('malformed authoritative messages do not transition out of loading', () => {
  const b = bridge();
  const errors = [];
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  b.receive({
    type: 'ready', state: 'default', placementType: 'inline',
    screenSize: { width: 390, height: 844 }, maxSize: { width: 390, height: 800 },
    currentPosition: { x: 0, y: 0, width: 320, height: 50 }, defaultPosition: { x: 0, y: 0, width: 320, height: 50 },
    location: null, supports: { sms: false, tel: false, calendar: false, storePicture: false, inlineVideo: true, location: false }
  });
  assert.equal(b.mraid.getState(), 'loading');
  assert.equal(errors.at(-1)[1], 'receive');
});

test('getters return defensive copies and orientation properties allow partial updates', () => {
  const b = bridge();
  ready(b.receive);
  const position = b.mraid.getCurrentPosition();
  position.width = 999;
  assert.equal(b.mraid.getCurrentPosition().width, 320);
  b.mraid.setOrientationProperties({ allowOrientationChange: false });
  assert.deepEqual({ ...b.mraid.getOrientationProperties() }, { allowOrientationChange: false, forceOrientation: 'none' });
  assert.deepEqual(b.messages[0].args, { allowOrientationChange: false, forceOrientation: 'none' });
});

test('rejects oversized, cyclic, deep, and prototype-bearing native messages without corrupting state', () => {
  const b = bridge();
  const errors = [];
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  const cyclic = { type: 'state', state: 'default' };
  cyclic.loop = cyclic;
  let deep = { value: true };
  for (let index = 0; index < 12; index++) deep = { child: deep };

  assert.doesNotThrow(() => b.receive('x'.repeat(65537)));
  assert.doesNotThrow(() => b.receive(cyclic));
  assert.doesNotThrow(() => b.receive({ type: 'state', state: 'default', deep }));
  assert.doesNotThrow(() => b.receive('{"type":"state","state":"default","__proto__":{"polluted":true}}'));
  assert.equal(b.mraid.getState(), 'loading');
  assert.equal(errors.length, 4);
  assert.ok(errors.every(([, action]) => action === 'receive'));
  assert.equal({}.polluted, undefined);
});

test('deterministic malformed-message fuzzing remains contained and preserves lifecycle state', () => {
  const b = bridge();
  let errors = 0;
  b.mraid.addEventListener('error', (_message, action) => { if (action === 'receive') errors++; });
  let seed = 0x5eed1234;
  const random = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed;
  };
  for (let index = 0; index < 400; index++) {
    const value = random();
    const malformed = [
      '{"type":',
      { type: value },
      { type: 'visibility', viewable: true, exposedPercentage: -(value % 100 + 1), visibleRectangle: {}, occlusionRectangles: [] },
      [value, 'not-a-message'],
      { payload: 'x'.repeat(value % 64) }
    ][value % 5];
    assert.doesNotThrow(() => b.receive(malformed));
  }
  assert.equal(errors, 400);
  assert.equal(b.mraid.getState(), 'loading');
});

test('bounds receive and command floods while preserving one-second normal-operation capacity', () => {
  const inbound = bridge();
  const inboundErrors = [];
  let stateEvents = 0;
  inbound.mraid.addEventListener('error', (message, action) => inboundErrors.push([message, action]));
  inbound.mraid.addEventListener('stateChange', () => stateEvents++);
  ready(inbound.receive);
  for (let index = 0; index < 700; index++) {
    inbound.receive({ type: 'state', state: index % 2 ? 'default' : 'expanded' });
  }
  assert.equal(stateEvents, 511);
  assert.equal(inboundErrors.filter(([, action]) => action === 'receive').length, 1);

  const outbound = bridge();
  const outboundErrors = [];
  outbound.mraid.addEventListener('error', (message, action) => outboundErrors.push([message, action]));
  ready(outbound.receive);
  for (let index = 0; index < 200; index++) outbound.mraid.open(`https://example.test/${index}`);
  assert.equal(outbound.messages.length, 64);
  assert.equal(outboundErrors.filter(([message, action]) => action === 'open' && message.includes('rate limit')).length, 1);
});

test('queues reentrant listener events, bounds recursion, and isolates hostile error values', () => {
  const b = bridge();
  const errors = [];
  let calls = 0;
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  b.mraid.addEventListener('stateChange', state => {
    calls++;
    b.receive({ type: 'state', state: state === 'expanded' ? 'default' : 'expanded' });
    if (calls === 1) {
      const hostile = {};
      Object.defineProperty(hostile, 'message', { get() { throw new Error('getter trap'); } });
      throw hostile;
    }
  });
  ready(b.receive);
  assert.doesNotThrow(() => b.receive({ type: 'state', state: 'expanded' }));
  assert.equal(calls, 255);
  assert.ok(errors.some(([, action]) => action === 'stateChange'));
  assert.ok(errors.some(([message, action]) => action === 'event' && message.includes('queue limit')));
});

test('caps listeners and coalesces cached-value callbacks into one removable timer queue', () => {
  const timers = [];
  const b = bridge({ setTimeout(callback) { timers.push(callback); } });
  const errors = [];
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  ready(b.receive);
  b.receive({ type: 'audio', volume: 25 });
  let calls = 0;
  const initial = () => calls++;
  b.mraid.addEventListener('audioVolumeChange', initial);
  for (let index = 1; index < 64; index++) b.mraid.addEventListener('audioVolumeChange', () => calls++);
  b.mraid.addEventListener('audioVolumeChange', () => calls++);
  b.mraid.removeEventListener('audioVolumeChange', initial);
  assert.equal(timers.length, 1);
  timers[0]();
  assert.equal(calls, 63);
  assert.ok(errors.some(([message, action]) => action === 'addEventListener' && message.includes('listener limit')));
});

test('bounds arbitrary calendar payloads and contains native bridge exceptions', () => {
  const nativeFailure = bridge({ postMessage() { throw new Error('native bridge failure'); } });
  const errors = [];
  nativeFailure.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  ready(nativeFailure.receive);
  const cyclic = { description: 'fixture' };
  cyclic.self = cyclic;
  assert.doesNotThrow(() => nativeFailure.mraid.createCalendarEvent(cyclic));
  const excessiveText = {};
  for (let index = 0; index < 10; index++) excessiveText[`field${index}`] = 'x'.repeat(8000);
  assert.doesNotThrow(() => nativeFailure.mraid.createCalendarEvent(excessiveText));
  assert.doesNotThrow(() => nativeFailure.mraid.open('https://example.test'));
  assert.deepEqual(errors.map(([, action]) => action), ['createCalendarEvent', 'createCalendarEvent', 'open']);

  const stable = bridge();
  ready(stable.receive);
  let reads = 0;
  const event = {};
  Object.defineProperty(event, 'description', { enumerable: true, get() { reads++; return 'one read'; } });
  stable.mraid.createCalendarEvent(event);
  assert.equal(reads, 1);
  assert.equal(stable.messages[0].args.event.description, 'one read');
});

test('failed timer scheduling removes the pending callback and listener', () => {
  const b = bridge({ setTimeout() { throw new Error('timer unavailable'); } });
  const errors = [];
  let calls = 0;
  b.mraid.addEventListener('error', (message, action) => errors.push([message, action]));
  ready(b.receive);
  b.receive({ type: 'audio', volume: 50 });
  b.mraid.addEventListener('audioVolumeChange', () => calls++);
  b.receive({ type: 'audio', volume: 60 });
  assert.equal(calls, 0);
  assert.deepEqual(errors.map(([, action]) => action), ['addEventListener']);
});
