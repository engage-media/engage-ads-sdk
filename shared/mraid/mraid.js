(function (global) {
  'use strict';

  var VERSION = '3.0';
  var STATES = ['loading', 'default', 'expanded', 'resized', 'hidden'];
  var PLACEMENTS = ['inline', 'interstitial'];
  var EVENTS = ['audioVolumeChange', 'error', 'exposureChange', 'ready', 'sizeChange', 'stateChange', 'viewableChange'];
  var CLOSE_POSITIONS = ['top-left', 'top-right', 'center', 'bottom-left', 'bottom-right', 'top-center', 'bottom-center'];
  var ORIENTATIONS = ['portrait', 'landscape', 'none'];
  var MAX_INPUT_CHARS = 65536;
  var MAX_STRING_CHARS = 8192;
  var MAX_URL_CHARS = 8192;
  var MAX_DEPTH = 8;
  var MAX_NODES = 1024;
  var MAX_ARRAY_ITEMS = 256;
  var MAX_OBJECT_KEYS = 64;
  var MAX_LISTENERS_PER_EVENT = 64;
  var MAX_EVENT_QUEUE = 256;
  var MAX_COMMANDS_PER_SECOND = 64;
  var MAX_RECEIVES_PER_SECOND = 512;
  var clock = Date.now;
  var listeners = Object.create(null);
  var eventQueue = [];
  var emitting = false;
  var deferredCallbacks = [];
  var deferredScheduled = false;
  var commandWindow = 0;
  var commandCount = 0;
  var commandLimitReported = false;
  var receiveWindow = 0;
  var receiveCount = 0;
  var receiveLimitReported = false;
  var nextId = 1;
  var ready = false;
  var state = 'loading';
  var placementType = 'inline';
  var viewable = false;
  var exposedPercentage = 0;
  var audioVolume = null;
  var hasVisibility = false;
  var hasAudio = false;
  var screenSize = size(0, 0);
  var maxSize = size(0, 0);
  var currentPosition = rect(0, 0, 0, 0);
  var defaultPosition = rect(0, 0, 0, 0);
  var visibleRectangle = rect(0, 0, 0, 0);
  var occlusionRectangles = [];
  var supports = defaultSupports();
  var currentAppOrientation = { orientation: 'portrait', locked: false };
  var location = null;
  var expandProperties = { width: -1, height: -1, useCustomClose: false, isModal: true };
  var expandWidthSet = false;
  var expandHeightSet = false;
  var resizeProperties = { width: 0, height: 0, offsetX: 0, offsetY: 0, customClosePosition: 'top-right', allowOffscreen: true };
  var resizePropertiesSet = false;
  var orientationProperties = { allowOrientationChange: true, forceOrientation: 'none' };

  function size(width, height) { return { width: width, height: height }; }
  function rect(x, y, width, height) { return { x: x, y: y, width: width, height: height }; }
  function defaultSupports() {
    return { sms: false, tel: false, calendar: false, storePicture: false, inlineVideo: false, location: false, vpaid: false };
  }
  function safeErrorMessage(error) {
    var message = 'Unknown error';
    try {
      if (error && typeof error.message === 'string') message = error.message;
      else message = String(error);
    } catch (_ignored) {}
    return message.length > 1024 ? message.slice(0, 1024) : message;
  }
  function boundedCopy(value, name) {
    var seen = [];
    var nodes = 0;
    var totalChars = 0;
    function clone(current, depth) {
      nodes += 1;
      if (nodes > MAX_NODES) throw new Error(name + ' is too complex');
      if (current === null || typeof current === 'boolean') return current;
      if (typeof current === 'string') {
        if (current.length > MAX_STRING_CHARS) throw new Error(name + ' contains an oversized string');
        totalChars += current.length;
        if (totalChars > MAX_INPUT_CHARS) throw new Error(name + ' contains too much text');
        return current;
      }
      if (typeof current === 'number') {
        if (!finite(current)) throw new Error(name + ' contains a non-finite number');
        return current;
      }
      if (!current || typeof current !== 'object') throw new Error(name + ' contains an unsupported value');
      if (depth >= MAX_DEPTH) throw new Error(name + ' is nested too deeply');
      if (seen.indexOf(current) >= 0) throw new Error(name + ' must not contain cycles');
      seen.push(current);
      var keys = Object.keys(current);
      var result;
      if (Array.isArray(current)) {
        if (current.length > MAX_ARRAY_ITEMS || keys.length > MAX_ARRAY_ITEMS) throw new Error(name + ' contains an oversized array');
        result = [];
        result.length = current.length;
      } else if (keys.length > MAX_OBJECT_KEYS) {
        throw new Error(name + ' contains too many object fields');
      } else result = {};
      keys.forEach(function (key) {
        if (key.length > 128) throw new Error(name + ' contains an oversized field name');
        if (key === '__proto__' || key === 'prototype' || key === 'constructor') throw new Error(name + ' contains a prohibited field name');
        totalChars += key.length;
        if (totalChars > MAX_INPUT_CHARS) throw new Error(name + ' contains too much text');
        result[key] = clone(current[key], depth + 1);
      });
      seen.pop();
      return result;
    }
    return clone(value, 0);
  }
  function copy(value) {
    if (Array.isArray(value)) return value.map(copy);
    if (value && typeof value === 'object') {
      var result = {};
      Object.keys(value).forEach(function (key) { result[key] = copy(value[key]); });
      return result;
    }
    return value;
  }
  function finite(value) { return typeof value === 'number' && isFinite(value); }
  function requiredObject(value, name) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(name + ' must be an object');
  }
  function requiredBoolean(value, name) {
    if (typeof value !== 'boolean') throw new Error(name + ' must be a boolean');
  }
  function positive(value, name) {
    if (!finite(value) || value <= 0) throw new Error(name + ' must be a positive number');
    if (value > 1000000) throw new Error(name + ' is outside the supported range');
  }
  function integer(value, name) {
    if (!Number.isInteger(value)) throw new Error(name + ' must be an integer');
  }
  function coordinate(value, name) {
    if (!finite(value)) throw new Error(name + ' must be a finite number');
    if (Math.abs(value) > 1000000) throw new Error(name + ' is outside the supported range');
  }
  function validateUrl(value, name, optional) {
    if (optional && (value === undefined || value === null || value === '')) return undefined;
    if (typeof value !== 'string' || value.trim() === '') throw new Error(name + ' must be a non-empty URL');
    if (value.length > MAX_URL_CHARS) throw new Error(name + ' is too long');
    if (/^(?:javascript|data|file):/i.test(value.trim())) throw new Error(name + ' uses a prohibited URL scheme');
    return value;
  }
  function normalizeRect(value, name) {
    requiredObject(value, name);
    ['x', 'y', 'width', 'height'].forEach(function (key) { coordinate(value[key], name + '.' + key); });
    if (value.width < 0 || value.height < 0) throw new Error(name + ' dimensions must be nonnegative');
    return rect(value.x, value.y, value.width, value.height);
  }
  function normalizeSize(value, name) {
    requiredObject(value, name);
    coordinate(value.width, name + '.width');
    coordinate(value.height, name + '.height');
    if (value.width < 0 || value.height < 0) throw new Error(name + ' dimensions must be nonnegative');
    return size(value.width, value.height);
  }
  function invoke(action, operation) {
    try { return operation(); }
    catch (error) {
      if (!error || error.engageMraidSilent !== true) emit('error', safeErrorMessage(error), action);
      return undefined;
    }
  }
  function withinRateLimit(kind) {
    var now = clock();
    if (kind === 'command') {
      if (now - commandWindow >= 1000 || now < commandWindow) { commandWindow = now; commandCount = 0; commandLimitReported = false; }
      commandCount += 1;
      return commandCount <= MAX_COMMANDS_PER_SECOND;
    }
    if (now - receiveWindow >= 1000 || now < receiveWindow) { receiveWindow = now; receiveCount = 0; receiveLimitReported = false; }
    receiveCount += 1;
    return receiveCount <= MAX_RECEIVES_PER_SECOND;
  }
  function dispatch(command, args, allowBeforeReady) {
    if (!ready && !allowBeforeReady) throw new Error('mraid is not ready');
    if (state === 'hidden' && command !== 'unload') throw new Error('mraid is hidden');
    var nativeBridge = global.EngageMraidNative;
    if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') throw new Error('native MRAID bridge is unavailable');
    if (!withinRateLimit('command')) {
      var limitError = new Error('command rate limit exceeded');
      if (commandLimitReported) limitError.engageMraidSilent = true;
      commandLimitReported = true;
      throw limitError;
    }
    if (nextId > 9007199254740991) throw new Error('command id range exhausted');
    var message = { id: nextId++, command: command, args: args || {} };
    var serialized = JSON.stringify(message);
    if (serialized.length > MAX_INPUT_CHARS) throw new Error('command payload is too large');
    nativeBridge.postMessage(serialized);
  }
  function emit(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (eventQueue.length >= MAX_EVENT_QUEUE) return;
    eventQueue.push({ name: name, args: args });
    if (emitting) return;
    emitting = true;
    var processed = 0;
    try {
      while (eventQueue.length && processed < MAX_EVENT_QUEUE) {
        var event = eventQueue.shift();
        var callbacks = (listeners[event.name] || []).slice();
        processed += 1;
        callbacks.forEach(function (callback) {
          try { callback.apply(mraid, event.args); }
          catch (error) {
            if (event.name !== 'error' && eventQueue.length < MAX_EVENT_QUEUE) {
              eventQueue.push({ name: 'error', args: [safeErrorMessage(error), event.name] });
            }
          }
        });
      }
      if (eventQueue.length) {
        eventQueue.length = 0;
        (listeners.error || []).slice().forEach(function (callback) {
          try { callback.apply(mraid, ['event queue limit exceeded', 'event']); } catch (_ignored) {}
        });
      }
    } finally {
      eventQueue.length = 0;
      emitting = false;
    }
  }
  function defer(callback) {
    if (typeof global.setTimeout !== 'function') { callback(); return true; }
    if (deferredCallbacks.length >= MAX_EVENT_QUEUE) return false;
    deferredCallbacks.push(callback);
    if (!deferredScheduled) {
      deferredScheduled = true;
      try {
        global.setTimeout(function () {
          var callbacks = deferredCallbacks.slice();
          deferredCallbacks.length = 0;
          deferredScheduled = false;
          callbacks.forEach(function (candidate) { try { candidate(); } catch (_ignored) {} });
        }, 0);
      } catch (error) {
        deferredCallbacks.length = 0;
        deferredScheduled = false;
        throw error;
      }
    }
    return true;
  }
  function normalizeExpandProperties(value) {
    requiredObject(value, 'expandProperties');
    var result = copy(expandProperties);
    if (value.width !== undefined) { positive(value.width, 'width'); integer(value.width, 'width'); result.width = value.width; }
    if (value.height !== undefined) { positive(value.height, 'height'); integer(value.height, 'height'); result.height = value.height; }
    if (value.useCustomClose !== undefined) { requiredBoolean(value.useCustomClose, 'useCustomClose'); result.useCustomClose = value.useCustomClose; }
    result.isModal = true;
    return result;
  }
  function normalizeResizeProperties(value) {
    requiredObject(value, 'resizeProperties');
    positive(value.width, 'width');
    positive(value.height, 'height');
    integer(value.width, 'width');
    integer(value.height, 'height');
    if (value.width < 50 || value.height < 50) throw new Error('width and height must be at least 50');
    coordinate(value.offsetX, 'offsetX');
    coordinate(value.offsetY, 'offsetY');
    integer(value.offsetX, 'offsetX');
    integer(value.offsetY, 'offsetY');
    var customClosePosition = value.customClosePosition === undefined ? 'top-right' : value.customClosePosition;
    var allowOffscreen = value.allowOffscreen === undefined ? true : value.allowOffscreen;
    if (CLOSE_POSITIONS.indexOf(customClosePosition) < 0) throw new Error('customClosePosition is invalid');
    requiredBoolean(allowOffscreen, 'allowOffscreen');
    return {
      width: value.width, height: value.height, offsetX: value.offsetX, offsetY: value.offsetY,
      customClosePosition: customClosePosition, allowOffscreen: allowOffscreen
    };
  }
  function normalizeOrientationProperties(value) {
    requiredObject(value, 'orientationProperties');
    var result = copy(orientationProperties);
    if (value.allowOrientationChange !== undefined) { requiredBoolean(value.allowOrientationChange, 'allowOrientationChange'); result.allowOrientationChange = value.allowOrientationChange; }
    if (value.forceOrientation !== undefined) {
      if (ORIENTATIONS.indexOf(value.forceOrientation) < 0) throw new Error('forceOrientation is invalid');
      result.forceOrientation = value.forceOrientation;
    }
    return result;
  }
  function normalizeAppOrientation(value) {
    requiredObject(value, 'currentAppOrientation');
    if (value.orientation !== 'portrait' && value.orientation !== 'landscape') throw new Error('currentAppOrientation.orientation is invalid');
    requiredBoolean(value.locked, 'currentAppOrientation.locked');
    return { orientation: value.orientation, locked: value.locked };
  }

  var mraid = {
    getVersion: function () { return VERSION; },
    getState: function () { return state; },
    getPlacementType: function () { return placementType; },
    isViewable: function () { return viewable; },
    getScreenSize: function () { return copy(screenSize); },
    getMaxSize: function () { return copy(maxSize); },
    getCurrentPosition: function () { return copy(currentPosition); },
    getDefaultPosition: function () { return copy(defaultPosition); },
    getExpandProperties: function () { return copy(expandProperties); },
    getResizeProperties: function () { return copy(resizeProperties); },
    getOrientationProperties: function () { return copy(orientationProperties); },
    getCurrentAppOrientation: function () { return copy(currentAppOrientation); },
    getLocation: function () { return supports.location && location ? copy(location) : -1; },
    getExposedPercentage: function () { return exposedPercentage; },
    supports: function (feature) { return typeof feature === 'string' && supports[feature] === true; },
    addEventListener: function (event, listener) {
      return invoke('addEventListener', function () {
        if (EVENTS.indexOf(event) < 0) throw new Error('unsupported event: ' + event);
        if (typeof listener !== 'function') throw new Error('listener must be a function');
        if (!listeners[event]) listeners[event] = [];
        var isNewListener = listeners[event].indexOf(listener) < 0;
        if (isNewListener) {
          if (listeners[event].length >= MAX_LISTENERS_PER_EVENT) throw new Error('listener limit exceeded for ' + event);
          listeners[event].push(listener);
        }
        function sendInitial(args) {
          if (!defer(function () {
            if ((listeners[event] || []).indexOf(listener) < 0) return;
            try { listener.apply(mraid, args); }
            catch (error) { emit('error', safeErrorMessage(error), event); }
          })) throw new Error('deferred callback limit exceeded');
        }
        try {
          if (isNewListener && event === 'exposureChange' && hasVisibility) sendInitial([exposedPercentage, copy(visibleRectangle), copy(occlusionRectangles)]);
          if (isNewListener && event === 'audioVolumeChange' && hasAudio) sendInitial([audioVolume]);
        } catch (error) {
          if (isNewListener) listeners[event] = listeners[event].filter(function (candidate) { return candidate !== listener; });
          throw error;
        }
      });
    },
    removeEventListener: function (event, listener) {
      return invoke('removeEventListener', function () {
        if (EVENTS.indexOf(event) < 0) throw new Error('unsupported event: ' + event);
        if (listener !== undefined && typeof listener !== 'function') throw new Error('listener must be a function');
        if (!listeners[event]) return;
        if (listener === undefined) listeners[event] = [];
        else listeners[event] = listeners[event].filter(function (candidate) { return candidate !== listener; });
      });
    },
    open: function (url) { return invoke('open', function () { dispatch('open', { url: validateUrl(url, 'url', false) }); }); },
    close: function () {
      return invoke('close', function () {
        if (state !== 'default' && state !== 'expanded' && state !== 'resized') throw new Error('close is unavailable in the current state');
        dispatch('close', {});
      });
    },
    expand: function (url) {
      return invoke('expand', function () {
        if (placementType === 'interstitial') throw new Error('expand is unavailable for interstitial placements');
        if (state === 'expanded') return;
        if (state !== 'default' && state !== 'resized') throw new Error('expand is unavailable in the current state');
        var args = { properties: copy(expandProperties) };
        var normalizedUrl = validateUrl(url, 'url', true);
        if (normalizedUrl !== undefined) args.url = normalizedUrl;
        dispatch('expand', args);
      });
    },
    resize: function () {
      return invoke('resize', function () {
        if (placementType === 'interstitial') throw new Error('resize is unavailable for interstitial placements');
        if (!resizePropertiesSet) throw new Error('setResizeProperties must be called before resize');
        if (state !== 'default' && state !== 'resized') throw new Error('resize is unavailable in the current state');
        dispatch('resize', { properties: copy(resizeProperties) });
      });
    },
    setExpandProperties: function (properties) {
      return invoke('setExpandProperties', function () {
        var nextProperties = normalizeExpandProperties(properties);
        expandProperties = nextProperties;
        if (properties.width !== undefined) expandWidthSet = true;
        if (properties.height !== undefined) expandHeightSet = true;
      });
    },
    setResizeProperties: function (properties) {
      return invoke('setResizeProperties', function () { resizeProperties = normalizeResizeProperties(properties); resizePropertiesSet = true; });
    },
    useCustomClose: function (useCustomClose) {
      return invoke('useCustomClose', function () { requiredBoolean(useCustomClose, 'useCustomClose'); expandProperties.useCustomClose = useCustomClose; });
    },
    setOrientationProperties: function (properties) {
      return invoke('setOrientationProperties', function () {
        var nextProperties = normalizeOrientationProperties(properties);
        dispatch('setOrientationProperties', copy(nextProperties));
        orientationProperties = nextProperties;
      });
    },
    playVideo: function (url) { return invoke('playVideo', function () { dispatch('playVideo', { url: validateUrl(url, 'url', false) }); }); },
    storePicture: function (url) { return invoke('storePicture', function () { dispatch('storePicture', { url: validateUrl(url, 'url', false) }); }); },
    createCalendarEvent: function (event) {
      return invoke('createCalendarEvent', function () { requiredObject(event, 'event'); dispatch('createCalendarEvent', { event: boundedCopy(event, 'event') }); });
    },
    unload: function () { return invoke('unload', function () { dispatch('unload', {}, true); }); }
  };

  function receive(input) {
    var message;
    if (!withinRateLimit('receive')) {
      if (!receiveLimitReported) { receiveLimitReported = true; emit('error', 'message rate limit exceeded', 'receive'); }
      return;
    }
    try {
      if (typeof input === 'string' && input.length > MAX_INPUT_CHARS) throw new Error('message is too large');
      message = boundedCopy(typeof input === 'string' ? JSON.parse(input) : input, 'message');
      requiredObject(message, 'message');
      if (typeof message.type !== 'string') throw new Error('message.type must be a string');
      if (message.type === 'ready') {
        if (ready) return;
        if (STATES.indexOf(message.state) < 0 || message.state === 'loading') throw new Error('ready state is invalid');
        if (PLACEMENTS.indexOf(message.placementType) < 0) throw new Error('placementType is invalid');
        var readyScreenSize = normalizeSize(message.screenSize, 'screenSize');
        var readyMaxSize = normalizeSize(message.maxSize, 'maxSize');
        var readyCurrentPosition = normalizeRect(message.currentPosition, 'currentPosition');
        var readyDefaultPosition = normalizeRect(message.defaultPosition, 'defaultPosition');
        var readyOrientation = normalizeAppOrientation(message.currentAppOrientation);
        if (message.location !== null) throw new Error('location must be null');
        requiredObject(message.supports, 'supports');
        var readySupports = defaultSupports();
        ['sms', 'tel', 'calendar', 'storePicture', 'inlineVideo', 'location'].forEach(function (feature) {
          requiredBoolean(message.supports[feature], 'supports.' + feature);
          readySupports[feature] = message.supports[feature];
        });
        state = message.state;
        placementType = message.placementType;
        screenSize = readyScreenSize;
        maxSize = readyMaxSize;
        currentPosition = readyCurrentPosition;
        defaultPosition = readyDefaultPosition;
        currentAppOrientation = readyOrientation;
        location = null;
        supports = readySupports;
        if (!expandWidthSet) expandProperties.width = screenSize.width;
        if (!expandHeightSet) expandProperties.height = screenSize.height;
        ready = true;
        emit('ready');
      } else if (message.type === 'state') {
        if (STATES.indexOf(message.state) < 0) throw new Error('state is invalid');
        if (state !== message.state) { state = message.state; emit('stateChange', state); }
      } else if (message.type === 'geometry') {
        var previous = currentPosition;
        var geometryScreenSize = normalizeSize(message.screenSize, 'screenSize');
        var geometryMaxSize = normalizeSize(message.maxSize, 'maxSize');
        var geometryCurrentPosition = normalizeRect(message.currentPosition, 'currentPosition');
        var geometryDefaultPosition = normalizeRect(message.defaultPosition, 'defaultPosition');
        var geometryOrientation = normalizeAppOrientation(message.currentAppOrientation);
        screenSize = geometryScreenSize;
        maxSize = geometryMaxSize;
        currentPosition = geometryCurrentPosition;
        defaultPosition = geometryDefaultPosition;
        currentAppOrientation = geometryOrientation;
        if (!expandWidthSet) expandProperties.width = screenSize.width;
        if (!expandHeightSet) expandProperties.height = screenSize.height;
        if (previous.width !== currentPosition.width || previous.height !== currentPosition.height) emit('sizeChange', currentPosition.width, currentPosition.height);
      } else if (message.type === 'visibility') {
        requiredBoolean(message.viewable, 'viewable');
        if (!finite(message.exposedPercentage) || message.exposedPercentage < 0 || message.exposedPercentage > 100) throw new Error('exposedPercentage must be between 0 and 100');
        var nextVisibleRectangle = normalizeRect(message.visibleRectangle, 'visibleRectangle');
        if (!Array.isArray(message.occlusionRectangles)) throw new Error('occlusionRectangles must be an array');
        var nextOcclusions = message.occlusionRectangles.map(function (item) { return normalizeRect(item, 'occlusionRectangle'); });
        var visibilityChanged = viewable !== message.viewable;
        viewable = message.viewable;
        exposedPercentage = message.exposedPercentage;
        visibleRectangle = nextVisibleRectangle;
        occlusionRectangles = nextOcclusions;
        hasVisibility = true;
        if (visibilityChanged) emit('viewableChange', viewable);
        emit('exposureChange', exposedPercentage, copy(visibleRectangle), copy(occlusionRectangles));
      } else if (message.type === 'audio') {
        if (message.volume !== null && (!finite(message.volume) || message.volume < 0 || message.volume > 100)) throw new Error('volume must be null or between 0 and 100');
        audioVolume = message.volume;
        hasAudio = true;
        emit('audioVolumeChange', audioVolume);
      } else if (message.type === 'error') {
        if (typeof message.message !== 'string' || typeof message.action !== 'string') throw new Error('error message and action must be strings');
        emit('error', message.message, message.action);
      } else {
        throw new Error('unsupported native message type: ' + message.type);
      }
    } catch (error) {
      emit('error', safeErrorMessage(error), 'receive');
    }
  }

  Object.defineProperty(global, 'mraid', { configurable: false, enumerable: true, writable: false, value: mraid });
  Object.defineProperty(global, '__engageMraid', { configurable: false, enumerable: false, writable: false, value: Object.freeze({ receive: receive }) });
})(typeof window !== 'undefined' ? window : globalThis);
