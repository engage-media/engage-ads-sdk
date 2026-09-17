import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const shared = path.dirname(here);
const root = path.dirname(shared);
const packageRoot = path.join(shared, 'node_modules', '@iabtechlab-omsdk', 'open-measurement');
const metadata = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));
if (metadata.name !== '@iabtechlab-omsdk/open-measurement' || metadata.version !== '1.6.10') {
  throw new Error('OMID verification fixture requires @iabtechlab-omsdk/open-measurement 1.6.10');
}

const clientPath = path.join(packageRoot, 'omsdk-js', 'Verification-Client', 'omid-verification-client-v1.js');
const licensePath = path.join(packageRoot, 'omsdk-js', 'Verification-Client', 'LICENSE');
const reporterPath = path.join(here, 'fixture-reporter.js');
const outputDirectory = path.join(root, 'contracts', 'fixtures', 'verification');
const outputPath = path.join(outputDirectory, 'omid-verification.js');
const outputLicense = path.join(outputDirectory, 'LICENSE.open-measurement.txt');
const banner = `/* Generated test fixture. Official OMID Verification Client from
 * @iabtechlab-omsdk/open-measurement@1.6.10, followed by Engage's local collector.
 * This observes real OMID callbacks; it does not simulate OMID events or certify integration.
 * See PROVENANCE.md and LICENSE.open-measurement.txt.
 */\n`;
const expected = banner + fs.readFileSync(clientPath, 'utf8') + '\n' + fs.readFileSync(reporterPath, 'utf8');
const expectedLicense = fs.readFileSync(licensePath, 'utf8');
const checking = process.argv.includes('--check');

if (checking) {
  if (!fs.existsSync(outputPath) || fs.readFileSync(outputPath, 'utf8') !== expected) throw new Error('generated OMID verification fixture is stale; run npm --prefix shared run build:omid-fixture');
  if (!fs.existsSync(outputLicense) || fs.readFileSync(outputLicense, 'utf8') !== expectedLicense) throw new Error('OMID verification fixture license is stale');
} else {
  fs.mkdirSync(outputDirectory, { recursive: true });
  fs.writeFileSync(outputPath, expected);
  fs.writeFileSync(outputLicense, expectedLicense);
}
