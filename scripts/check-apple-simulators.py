#!/usr/bin/env python3
"""Run each native package's tests on an available simulator from full Xcode."""
import json
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], text=True))['devices']
    package_scheme = 'EngageAdsSDK-Package'
    for platform, product, test_target in [
        ('iOS', 'EngageAdsMobile', 'EngageAdsMobileTests'),
        ('tvOS', 'EngageAdsTV', 'EngageAdsTVTests'),
    ]:
        candidates = [device for runtime, entries in devices.items() if f'.{platform}-' in runtime
                      for device in entries if device.get('isAvailable')]
        if not candidates:
            raise SystemExit(f'Install an {platform} simulator runtime in Xcode before validation')
        candidate = candidates[0]
        result = root / 'apple/DerivedData' / f'{product}.xcresult'
        if result.exists():
            raise SystemExit(f'Remove or archive the previous test result before rerunning: {result}')
        subprocess.run(['xcodebuild', '-scheme', package_scheme,
                        f'-only-testing:{test_target}',
                        '-destination', f'id={candidate["udid"]}',
                        '-derivedDataPath', str(root / 'apple/DerivedData' / product),
                        '-resultBundlePath', str(result), 'CODE_SIGNING_ALLOWED=NO', 'test'], cwd=root, check=True)


if __name__ == '__main__':
    main()
