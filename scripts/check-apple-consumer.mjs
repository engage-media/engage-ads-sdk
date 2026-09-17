#!/usr/bin/env node
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { listen } = require('../contracts/mock-server/server.js');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fixture = await listen();
try {
  const result = await new Promise((done, fail) => {
    const child = spawn('swift', ['run', '--package-path', 'integration/AppleCoreConsumer'], {
      cwd: root, env: { ...process.env, ENGAGE_FIXTURE_ORIGIN: fixture.origin }, stdio: 'inherit'
    });
    child.on('error', fail);
    child.on('exit', (code, signal) => done(code ?? (signal ? 1 : 0)));
  });
  process.exitCode = result;
} finally {
  await fixture.close();
}
