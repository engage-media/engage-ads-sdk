#!/bin/sh
set -eu
cd "$(dirname "$0")"
xcodegen generate --spec project.yml
echo "Generated EngageAdsExamples.xcodeproj"
