#!/usr/bin/env python3
"""Exercise R8-shrunk footprint consumers and repeated destroy cycles on an emulator."""
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
    parser.add_argument('--output', default='dist/hardening/footprint')
    parser.add_argument('--origin', default='http://127.0.0.1:8787')
    parser.add_argument('--cycles', type=int, default=20)
    args = parser.parse_args()
    if not 1 <= args.cycles <= 50:
        parser.error('--cycles must be between 1 and 50')
    root = Path(__file__).resolve().parents[1]
    output = (root / args.output).resolve()
    sizes = json.loads((output / 'report.json').read_text())
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]

    def run(*arguments):
        return subprocess.check_output(adb + list(arguments), text=True, stderr=subprocess.STDOUT)

    def inspect():
        with urllib.request.urlopen(args.origin + '/_inspect', timeout=3) as response:
            return json.load(response)

    def reset():
        with urllib.request.urlopen(urllib.request.Request(args.origin + '/_reset', data=b'', method='POST'), timeout=3):
            pass

    def logs():
        return run('logcat', '-d', '-s', 'EngageFootprint:I', 'AndroidRuntime:E')

    def tap(label, attribute='text'):
        for _ in range(8):
            run('shell', 'uiautomator', 'dump', '/sdcard/engage-release.xml')
            ui = ET.fromstring(run('shell', 'cat', '/sdcard/engage-release.xml'))
            for node in ui.iter('node'):
                if node.get(attribute, '').lower() == label.lower():
                    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds', '')))
                    run('shell', 'input', 'tap', str((left + right)//2), str((top + bottom)//2))
                    return
        raise RuntimeError('Control not found: ' + label)

    result = {'serial': args.serial, 'cases': [], 'scope': 'R8-shrunk release artifacts; all cases use local mock ads'}
    for name in ('mobile', 'tv'):
        run('install', '-r', str(root / sizes['apps'][name]['apk_path']))
    cases = [('mobile', 'BANNER'), ('mobile', 'INTERSTITIAL'), ('mobile', 'REWARDED'), ('mobile', 'NATIVE'), ('tv', 'INSTREAM')]
    for facade, format_name in cases:
        package = 'example.footprint.' + facade
        run('shell', 'am', 'force-stop', 'example.footprint.mobile')
        run('shell', 'am', 'force-stop', 'example.footprint.tv')
        reset(); run('logcat', '-c')
        run('shell', 'am', 'start', '-n', package + '/example.footprint.MainActivity', '--es', 'format', format_name)
        tap('Load ad')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            events = logs()
            if 'FATAL EXCEPTION' in events or 'event=Error' in events:
                raise RuntimeError(events)
            complete = 'event=Displayed' in events if format_name == 'BANNER' else 'event=AdCompleted' in events
            if complete:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError('No successful completion: ' + events)
        if format_name == 'BANNER':
            tap('Expand'); tap('Close ad', 'content-desc')
            tap('Resize'); tap('Close ad', 'content-desc')
        time.sleep(0.5)
        state = inspect(); events = logs()
        billing = [n for n in state['notices'] if n['kind'] == 'burl']
        if len(billing) != 1 or events.count('event=Displayed') != 1:
            raise RuntimeError('Billing/display must occur once: ' + events)
        if format_name == 'REWARDED' and events.count('event=RewardEarned') != 1:
            raise RuntimeError('Reward must occur once: ' + events)
        row = {'facade': facade, 'format': format_name, 'result': 'PASS', 'billing': len(billing)}
        result['cases'].append(row)
        (output / f'release-{facade}-{format_name.lower()}.log').write_text(events)
        print(json.dumps(row), flush=True)
    package = 'example.footprint.mobile'
    run('shell', 'am', 'force-stop', package); run('shell', 'am', 'force-stop', 'example.footprint.tv')
    reset(); run('logcat', '-c')
    run('shell', 'am', 'start', '-n', package + '/example.footprint.MainActivity', '--ez', 'terminate_renderer', 'true')
    tap('Load ad')
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        events = logs()
        if 'FATAL EXCEPTION' in events:
            raise RuntimeError('WebView termination crashed host: ' + events)
        if 'event=Error:RENDER' in events:
            break
        time.sleep(0.25)
    else:
        raise RuntimeError('Renderer termination was not contained: ' + events)
    if 'renderer_termination_requested=true' not in events or not run('shell', 'pidof', package).strip():
        raise RuntimeError('Renderer termination did not leave the host alive: ' + events)
    result['renderer_termination'] = {'result': 'PASS', 'host_alive': True, 'event': 'RENDER'}
    (output / 'release-renderer-termination.log').write_text(events)
    print('PASS: WebView renderer terminated; host remained alive with a typed error', flush=True)
    run('shell', 'am', 'force-stop', package); run('shell', 'am', 'force-stop', 'example.footprint.tv')
    reset(); run('logcat', '-c')
    run('shell', 'am', 'start', '-n', package + '/example.footprint.MainActivity', '--ei', 'load_cycles', str(args.cycles))
    samples = []
    deadline = time.monotonic() + args.cycles * 8 + 20
    while time.monotonic() < deadline:
        events = logs()
        if 'FATAL EXCEPTION' in events or 'event=Error' in events:
            raise RuntimeError(events)
        memory = run('shell', 'dumpsys', 'meminfo', package)
        match = re.search(r'TOTAL PSS:\s*(\d+)', memory)
        if match:
            samples.append({'completed_cycles': events.count('cycle='), 'process_pss_kib': int(match.group(1))})
        if f'stress_done={args.cycles}' in events:
            break
        time.sleep(1)
    else:
        raise RuntimeError('Destroy-cycle stress timed out: ' + events)
    release_deadline = time.monotonic() + 8
    while time.monotonic() < release_deadline:
        if 'retained_ads=' in logs():
            break
        time.sleep(0.25)
    events = logs(); state = inspect()
    released = re.search(r'retained_ads=(\d+) retained_views=(\d+)', events)
    if not released or any(int(v) for v in released.groups()):
        raise RuntimeError('Destroyed SDK ads/views remain reachable after explicit test GC: ' + events)
    count = len([n for n in state['notices'] if n['kind'] == 'burl'])
    if count != args.cycles or events.count('event=Displayed') != args.cycles:
        raise RuntimeError('Unexpected repeated-cycle notice/event counts: ' + events)
    result['destroy_stress'] = {'result': 'PASS', 'cycles': args.cycles, 'billing_notices': count,
        'memory_samples': samples, 'retained_ads_after_test_gc': int(released.group(1)),
        'retained_views_after_test_gc': int(released.group(2)),
        'post_cleanup_meminfo': run('shell', 'dumpsys', 'meminfo', package),
        'note': 'Explicit GC is used only by the test probe; weak references cover ad/container views, not every allocation. PSS includes WebView/runtime caches.'}
    (output / 'release-destroy-stress.log').write_text(events)
    (output / 'release-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'PASS: {args.cycles} load/display/destroy cycles with one billing notice each')
    run('shell', 'am', 'force-stop', package)


if __name__ == '__main__':
    main()
