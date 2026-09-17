#!/usr/bin/env python3
"""Observe actual IMA OMID events using the local IAB verification-client fixture.

Requires a booted emulator, mock server, and measure-android-footprint.py output.
Script fetches, SDK display callbacks, and VAST trackers do not substitute for OM
session/impression evidence. This is integration evidence, not IAB certification.
"""
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
    parser.add_argument('--footprint', default='dist/measurement/footprint')
    parser.add_argument('--output', default='dist/measurement/runtime')
    parser.add_argument('--origin', default='http://127.0.0.1:8787')
    parser.add_argument('--device-origin', default='http://10.0.2.2:8787')
    parser.add_argument('--case', help='Run only the named case, e.g. mobile-inline-ortb')
    parser.add_argument('--clear-data', action='store_true', help='Clear only the generated probe app data before each case')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / args.output
    output.mkdir(parents=True, exist_ok=True)
    sizes = json.loads((root / args.footprint / 'report.json').read_text())
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]

    def run(*arguments):
        return subprocess.check_output(adb + list(arguments), text=True, stderr=subprocess.STDOUT)

    def inspect():
        with urllib.request.urlopen(args.origin + '/_inspect', timeout=3) as response:
            return json.load(response)

    def logs():
        return run('logcat', '-d', '-s', 'EngageFootprint:I', 'AndroidRuntime:E')

    def load():
        run('shell', 'uiautomator', 'dump', '/sdcard/engage-measurement.xml')
        tree = ET.fromstring(run('shell', 'cat', '/sdcard/engage-measurement.xml'))
        for node in tree.iter('node'):
            if node.get('text', '').lower() == 'load ad':
                left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds', '')))
                run('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
                return
        raise RuntimeError('Load button missing')

    result = {'serial': args.serial, 'clear_probe_data': args.clear_data, 'scope': 'IMA on Android emulator, actual OMID verification client; not certification', 'cases': []}
    for facade in ('mobile', 'tv'):
        run('install', '-r', str(root / sizes['apps'][facade]['apk_path']))
    cases = [
        ('mobile', 'inline', False), ('mobile', 'wrapper', False),
        ('mobile', 'pod', False), ('mobile', 'inline', True),
        ('mobile', 'failure', False), ('tv', 'pod', False),
    ]
    for facade, scenario, direct in cases:
        name = f'{facade}-{scenario}-' + ('vast' if direct else 'ortb')
        if args.case and args.case != name:
            continue
        row = {'name': name, 'result': 'FAIL'}
        try:
            for target in ('mobile', 'tv'):
                run('shell', 'am', 'force-stop', 'example.footprint.' + target)
            if args.clear_data:
                run('shell', 'pm', 'clear', 'example.footprint.' + facade)
            with urllib.request.urlopen(urllib.request.Request(args.origin + '/_reset', data=b'', method='POST'), timeout=3):
                pass
            run('logcat', '-c')
            endpoint = args.device_origin + (f'/vast/omid-{scenario}.xml' if direct else f'/openrtb/2.6/omid-{scenario}')
            package = 'example.footprint.' + facade
            run('shell', 'am', 'start', '-W', '-n', package + '/example.footprint.MainActivity',
                '--es', 'format', 'INSTREAM', '--es', 'endpoint', endpoint,
                '--ez', 'direct_vast', str(direct).lower(), '--ei', 'display_delay_ms', '3000')
            load()
            deadline = time.monotonic() + 10
            while 'event=Loaded' not in logs():
                if time.monotonic() > deadline:
                    raise RuntimeError('Ad did not load')
                time.sleep(.1)
            before = inspect()
            premature = [notice for notice in before['notices'] if notice['kind'] in ('burl', 'impression', 'start')]
            if premature or before['measurement']['scriptRequests'] or before['measurement']['events']:
                raise RuntimeError('Preload unexpectedly dispatched tracking or measurement')
            row['no_preload_measurement_or_billing'] = True
            deadline = time.monotonic() + 40
            while True:
                log = logs()
                if 'FATAL EXCEPTION' in log or 'event=Error' in log:
                    raise RuntimeError('Playback failed; see log')
                if 'event=BreakCompleted' in log:
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError('Ad break did not complete')
                time.sleep(.2)
            # OM finishes asynchronously after renderer cleanup.
            time.sleep(3)
            state = inspect()
            events = state['measurement']['events']
            event_names = [event['event'] for event in events]
            row['event_counts'] = {event: event_names.count(event) for event in sorted(set(event_names))}
            row['script_fetches'] = len(state['measurement']['scriptRequests'])
            billing = [notice for notice in state['notices'] if notice['kind'] == 'burl']
            row['billing_attempts'] = len(billing)
            if len(billing) != (0 if direct else 1):
                raise RuntimeError('Incorrect billing count')
            if scenario == 'failure':
                failed_resource = any(request['resource'] == 'omid-failure.js'
                                      for request in state['measurement']['scriptRequests'])
                if not failed_resource and 'verificationNotExecuted' not in event_names:
                    raise RuntimeError('No evidence verification failure was exercised')
                row['verification_failure_did_not_break_playback'] = True
            else:
                for required in ('sessionStart', 'impression', 'sessionFinish'):
                    observed = [event for event in events if event['event'] == required
                                and event.get('method') == 'POST'
                                and event.get('payload', {}).get('kind') == required
                                and event.get('payload', {}).get('fixtureId') == event.get('id')
                                and event.get('payload', {}).get('detail', {}).get('adSessionId')]
                    if not observed:
                        raise RuntimeError('Missing actual OMID event: ' + required)
                if not any(event['event'] == 'isSupported' and event.get('method') == 'POST'
                           and event.get('payload', {}).get('detail', {}).get('supported') is True
                           for event in events):
                    raise RuntimeError('Official verification client did not confirm OMID support')
                sessions = {event.get('payload', {}).get('detail', {}).get('adSessionId')
                            for event in events if event['event'] == 'impression'} - {None, ''}
                row['impression_session_count'] = len(sessions)
                if scenario == 'pod' and len(sessions) < 2:
                    raise RuntimeError('Pod did not produce measurement for both creatives')
                row['actual_omid_session_and_impression_observed'] = True
            row['result'] = 'PASS'
        except Exception as error:
            row['error'] = str(error)
        finally:
            (output / (name + '.log')).write_text(logs())
            (output / (name + '-collector.json')).write_text(json.dumps(inspect(), indent=2) + '\n')
            result['cases'].append(row)
            (output / 'measurement.json').write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps(row), flush=True)
    return 0 if result['cases'] and all(row['result'] == 'PASS' for row in result['cases']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
