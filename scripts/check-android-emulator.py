#!/usr/bin/env python3
"""Validate a mobile/TV sample opportunity on a booted Android emulator using local ads."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--scenario', choices=['banner', 'mraid', 'video', 'rewarded', 'native', 'native-video', 'pod'], default='banner')
    parser.add_argument('--origin', default='http://127.0.0.1:8787')
    parser.add_argument('--direct-vast', action='store_true', help='Expect player tracking and no separate billing notice')
    parser.add_argument('--expect-no-fill', action='store_true')
    parser.add_argument('--expect-error', help='Expected typed error code instead of a displayed ad')
    parser.add_argument('--remote', action='store_true', help='Activate the TV sample with the remote D-pad and check restored focus')
    parser.add_argument('--tracker-id', action='append', default=[], help='Require an IMA completion tracker for this fixture ID')
    parser.add_argument('--click-native', action='store_true')
    parser.add_argument('--background', action='store_true', help='Pause a video opportunity in the background, then resume')
    parser.add_argument('--cancel-after-display', action='store_true')
    parser.add_argument('--skip-reward', action='store_true', help='Skip a long skippable rewarded fixture')
    parser.add_argument('--two-part', action='store_true', help='Exercise an expanded child WebView and return to the original creative')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    facade = 'tv' if args.scenario == 'pod' else 'mobile'
    application = 'com.engage.ads.sample.' + facade

    def run(*arguments):
        return subprocess.check_output(adb + list(arguments), text=True, stderr=subprocess.STDOUT)

    def inspect():
        with urllib.request.urlopen(args.origin + '/_inspect', timeout=3) as response:
            return json.load(response)

    # The fixture server must be started separately; the sample endpoint is a build setting.
    inspect()
    run('install', '-r', str(root / f'android/sample-{facade}/build/outputs/apk/debug/sample-{facade}-debug.apk'))
    run('shell', 'am', 'force-stop', 'com.engage.ads.sample.mobile')
    run('shell', 'am', 'force-stop', 'com.engage.ads.sample.tv')
    with urllib.request.urlopen(urllib.request.Request(args.origin + '/_reset', data=b'', method='POST'), timeout=3):
        pass
    run('logcat', '-c')
    run('shell', 'am', 'start', '-n', application + '/.MainActivity')
    def tap(label, attribute='text'):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            run('shell', 'uiautomator', 'dump', '/sdcard/engage-sdk-smoke.xml')
            document = ET.fromstring(run('shell', 'cat', '/sdcard/engage-sdk-smoke.xml'))
            button = next((node for node in document.iter('node') if node.get(attribute, '').lower() == label.lower()), None)
            if button is not None:
                coordinates = list(map(int, re.findall(r'\d+', button.get('bounds', ''))))
                if len(coordinates) != 4:
                    raise RuntimeError('Sample button bounds were unavailable')
                left, top, right, bottom = coordinates
                if args.remote and label == 'Load ad pod':
                    if button.get('focused') != 'true':
                        run('shell', 'input', 'keyevent', 'KEYCODE_DPAD_UP')
                        time.sleep(0.25)
                        continue
                    run('shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')
                    return
                run('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
                return
            time.sleep(0.25)
        raise RuntimeError(f'Sample did not expose {label!r}.\n' + run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E'))

    label = 'Load ad pod' if args.scenario == 'pod' else 'Load ' + ('banner' if args.scenario == 'mraid' else args.scenario.replace('-', ' '))
    tap(label)

    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        state = inspect()
        billing = [notice for notice in state['notices'] if notice['kind'] == 'burl']
        logs = run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E')
        expected_terminal = 'NoFill' if args.expect_no_fill else ('Error: ' + args.expect_error if args.expect_error else None)
        if expected_terminal and expected_terminal in logs:
            time.sleep(1)
            final_state = inspect()
            final_logs = run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E')
            if any(n['kind'] == 'burl' for n in final_state['notices']) or 'Displayed' in final_logs or 'RewardEarned' in final_logs:
                raise RuntimeError('Failed/no-fill opportunity displayed, billed, or earned a reward: ' + final_logs)
            print(f'PASS: Android emulator {args.serial}, {args.scenario}, {expected_terminal}, zero billing/display/reward')
            print(final_logs.strip())
            return
        if 'FATAL EXCEPTION' in logs or 'EngageSample: Error:' in logs:
            raise RuntimeError('Sample failed before successful display.\n' + logs)
        if not expected_terminal and (billing or (args.direct_vast and 'EngageSample: Displayed' in logs)):
            expected_billing = 0 if args.direct_vast else 1
            if len(billing) != expected_billing:
                raise RuntimeError('Duplicate billing during smoke test')
            if args.background:
                run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                time.sleep(3)
                paused_logs = run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E')
                if 'RewardEarned' in paused_logs or 'AdCompleted' in paused_logs:
                    raise RuntimeError('Video completed or rewarded while backgrounded: ' + paused_logs)
                run('shell', 'am', 'start', '-f', '0x00020000', '-n', application + '/.MainActivity')
            if args.cancel_after_display:
                run('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            if args.skip_reward:
                tap('Skip ad')
            if args.two_part:
                tap('Expand two-part')
                tap('Close')
                # A second expansion proves the original creative was restored.
                tap('Expand two-part')
                tap('Close ad', 'content-desc')
            elif args.scenario == 'mraid':
                for operation in ['Expand', 'Resize']:
                    tap(operation)
                    tap('Close ad', 'content-desc')
                tap('Close')
            # Give repeated callbacks and the local two-second creative time to finish.
            time.sleep(7 if args.scenario == 'pod' else 4)
            state = inspect()
            if len([n for n in state['notices'] if n['kind'] == 'burl']) != expected_billing:
                raise RuntimeError('Duplicate billing after display')
            logs = run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E')
            if 'FATAL EXCEPTION' in logs or 'EngageSample: Error:' in logs:
                raise RuntimeError(logs)
            if args.cancel_after_display or args.skip_reward:
                if 'RewardEarned' in logs or 'AdCompleted' in logs:
                    raise RuntimeError('Cancelled/skipped ad incorrectly completed or rewarded: ' + logs)
                if args.skip_reward and 'Dismissed' not in logs:
                    raise RuntimeError('Skipped rewarded ad did not dismiss: ' + logs)
                print(f'PASS: Android emulator {args.serial}, {args.scenario}, cancellation/skip, no reward or completion')
                print(logs.strip())
                return
            if args.scenario == 'rewarded' and logs.count('RewardEarned') != 1:
                raise RuntimeError('Rewarded ad displayed but did not report successful completion: ' + logs)
            if args.scenario in ('video', 'rewarded') and ('AdCompleted' not in logs or 'BreakCompleted' not in logs):
                raise RuntimeError('Video did not complete its creative and break: ' + logs)
            if args.scenario == 'native-video' and 'AdCompleted' not in logs:
                raise RuntimeError('Native video did not complete: ' + logs)
            if args.scenario == 'pod' and ('BreakCompleted' not in logs or logs.count('AdCompleted') != 2):
                raise RuntimeError('Pod did not complete both creatives and its break: ' + logs)
            if args.scenario == 'mraid' and not args.two_part and 'Dismissed' not in logs:
                raise RuntimeError('MRAID close did not dismiss the restored creative: ' + logs)
            if args.click_native:
                tap('Deterministic native fixture')
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    if any(n['kind'] == 'click' for n in inspect()['notices']):
                        break
                    time.sleep(0.1)
                else:
                    raise RuntimeError('Registered native click did not send its tracker')
                click_notices = [n for n in inspect()['notices'] if n['kind'] == 'click']
                logs = run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E')
                if len(click_notices) != 1 or logs.count('EngageSample: Clicked') != 1:
                    raise RuntimeError('Native asset tap must emit one click event and one tracker: ' + logs)
            for tracker_id in args.tracker_id:
                if not any(n['kind'] == 'complete' and n['id'] == tracker_id for n in state['notices']):
                    raise RuntimeError('IMA completion tracker missing: ' + tracker_id)
            if args.remote:
                run('shell', 'uiautomator', 'dump', '/sdcard/engage-sdk-smoke.xml')
                ui = ET.fromstring(run('shell', 'cat', '/sdcard/engage-sdk-smoke.xml'))
                if not any(n.get('text', '').lower() == 'load ad pod' and n.get('focused') == 'true' for n in ui.iter('node')):
                    raise RuntimeError('TV remote focus was not restored to the opportunity control')
            print(f'PASS: Android emulator {args.serial}, {args.scenario}, {expected_billing} display-time billing notices')
            print(logs.strip())
            return
        time.sleep(0.25)
    raise RuntimeError('Expected display or terminal event was not observed.\n' + run('logcat', '-d', '-s', 'EngageSample:I', 'AndroidRuntime:E'))


if __name__ == '__main__':
    main()
