#!/usr/bin/env python3
"""Check conversation restoration across process death on emulator-5554.

Requires mock_daemon.py --events-file PATH and a saved connection to that fixture.
Does not clear app data or send messages. --events must match the fixture path.
"""
import argparse
import json
from pathlib import Path
import time
import android_smoke as ui


def requests(path):
    return [event['message'] for line in path.read_text().splitlines()
            if (event := json.loads(line)).get('type') == 'request']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--events', required=True, type=Path)
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path('app/build/restoration-smoke')
    ui.output.mkdir(parents=True, exist_ok=True)
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    ui.node('已连接')
    ui.click('Native Android')
    ui.click('切换代理或终端', 'content-desc')
    ui.click('Second agent')
    ui.node('Native client review')

    for source in ('conversation', 'settings'):
        if source == 'settings':
            ui.open_appearance()
            ui.node('主题')
        offset = len(requests(args.events))
        old_pid = ui.adb('shell', 'pidof', 'sh.paseo.android').strip()
        ui.adb('shell', 'am', 'force-stop', 'sh.paseo.android')
        # A cold-start UI dump can fail while leaving the previous XML on disk.
        ui.adb('shell', 'rm', '-f', '/sdcard/paseo-smoke.xml')
        ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if any(m['type'] == 'fetch_agent_timeline_request' for m in requests(args.events)[offset:]):
                break
            time.sleep(.2)
        ui.node('Native client review')
        assert ui.adb('shell', 'pidof', 'sh.paseo.android').strip() != old_pid
        current = requests(args.events)[offset:]
        histories = [m['agentId'] for m in current if m['type'] == 'fetch_agent_timeline_request']
        assert histories == ['fixture-second'], histories
        assert not any('create' in m['type'] or m['type'] == 'send_agent_message_request' for m in current)
        ui.screenshot('restored-from-' + source)
        print(f'{source}: new process restored the exact second conversation without creating or sending', flush=True)


if __name__ == '__main__':
    main()
