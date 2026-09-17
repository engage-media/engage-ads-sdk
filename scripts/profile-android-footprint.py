#!/usr/bin/env python3
"""Collect repeatable emulator startup/idle observations from footprint consumers.

These are descriptive host/emulator measurements, not physical-device performance
qualification. Does not claim renderer-process/GPU memory is included in app PSS.
"""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', default='dist/hardening/footprint')
    parser.add_argument('--runs', type=int, default=5)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be positive')
    root = Path(__file__).resolve().parents[1]
    output = (root / args.output).resolve()
    report = json.loads((output / 'report.json').read_text())
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]

    def run(*arguments):
        return subprocess.check_output(adb + list(arguments), text=True, stderr=subprocess.STDOUT)

    result = {'serial': args.serial, 'android_release': run('shell', 'getprop', 'ro.build.version.release').strip(),
              'model': run('shell', 'getprop', 'ro.product.model').strip(), 'runs': args.runs,
              'scope': 'Process-cold starts on one emulator; app process PSS only; no ad loaded', 'apps': {}}
    for name, info in report['apps'].items():
        package = 'example.footprint.' + name
        component = package + '/example.footprint.MainActivity'
        run('install', '-r', str(root / info['apk_path']))
        values = []
        raw = []
        for iteration in range(args.runs):
            run('shell', 'am', 'force-stop', package)
            run('logcat', '-c')
            launch = run('shell', 'am', 'start', '-W', '-n', component)
            time.sleep(1)
            logs = run('logcat', '-d', '-s', 'EngageFootprint:I', 'AndroidRuntime:E', 'StrictMode:D')
            if 'FATAL EXCEPTION' in logs or 'Status: ok' not in launch:
                raise RuntimeError(launch + logs)
            memory = run('shell', 'dumpsys', 'meminfo', package)
            row = {}
            for key, text, pattern in [
                ('launch_total_ms', launch, r'TotalTime:\s*(\d+)'),
                ('process_pss_kib', memory, r'TOTAL PSS:\s*(\d+)'),
                ('cold_init_us', logs, r'cold_init_us=(\d+)'),
                ('cold_init_cpu_us', logs, r'cpu_us=(\d+)'),
            ]:
                match = re.search(pattern, text)
                if match:
                    row[key] = int(match.group(1))
            if name != 'baseline' and 'cold_init_us' not in row:
                raise RuntimeError('SDK initialization was not reached: ' + logs)
            values.append(row)
            raw.append({'iteration': iteration, 'launch': launch, 'logs': logs, 'memory': memory})
        warm = None
        if name != 'baseline':
            run('shell', 'am', 'force-stop', package)
            run('logcat', '-c')
            run('shell', 'am', 'start', '-W', '-n', component, '--ez', 'init_stress', 'true')
            time.sleep(1)
            logs = run('logcat', '-d', '-s', 'EngageFootprint:I', 'AndroidRuntime:E')
            match = re.search(r'warm_init_destroy_p50_us=(\d+) p95_us=(\d+)', logs)
            if not match or 'FATAL EXCEPTION' in logs:
                raise RuntimeError('Initialization stress failed: ' + logs)
            warm = {'iterations': 50, 'p50_us': int(match.group(1)), 'p95_us': int(match.group(2))}
        cpu_samples = []
        for _ in range(2):
            pid = run('shell', 'pidof', package).strip().split()[0]
            stat = run('shell', 'cat', '/proc/' + pid + '/stat')
            fields = stat[stat.rfind(')') + 2:].split()
            cpu_samples.append(int(fields[11]) + int(fields[12]))
            if len(cpu_samples) == 1:
                time.sleep(2)
        result['apps'][name] = {'cold_runs': values,
            'medians': {key: statistics.median(row[key] for row in values if key in row) for key in values[0]},
            'warm_init_destroy': warm, 'idle_process_cpu_ticks_over_2_seconds': cpu_samples[1] - cpu_samples[0]}
        (output / (name + '-profile-raw.json')).write_text(json.dumps(raw, indent=2) + '\n')
        run('shell', 'am', 'force-stop', package)
    (output / 'profile.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
