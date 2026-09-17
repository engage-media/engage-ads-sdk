# OMID verification fixture provenance

`omid-verification.js` is generated for conformance testing by `shared/omid/build-fixture.mjs`. It bundles the unmodified stable verification client from the official IAB Tech Lab package `@iabtechlab-omsdk/open-measurement` version `1.6.10`, followed by the repository's bounded test reporter.

- Upstream: https://github.com/InteractiveAdvertisingBureau/Open-Measurement-JSClients
- npm package: https://www.npmjs.com/package/@iabtechlab-omsdk
- Package integrity: `sha512-+BzpRwQ46WCASeXrMOI3uTSomoRKhcYizTA7B2xLIBWIWMcRFElkiM4pt6M5gh/pA+TrtVj79BsyBcuvljo4XQ==`
- Bundled source: `omsdk-js/Verification-Client/omid-verification-client-v1.js`

The npm metadata declares ISC. The distributed Verification-Client source ships the Apache License 2.0 text copied beside the generated fixture as `LICENSE.open-measurement.txt`. This is test-only material and does not establish IAB certification.
