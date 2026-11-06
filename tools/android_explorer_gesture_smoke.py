#!/usr/bin/env python3
"""Focused explorer settle checks on emulator-5554 with an open fixture conversation.

Run mock_daemon.py --sidebar first. This does not clear app data.
"""
import argparse
from pathlib import Path
import re
import time
import android_smoke as ui


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path('app/build/explorer-gesture-smoke')
    ui.output.mkdir(parents=True, exist_ok=True)
    width, height = map(int, re.findall(r'(\d+)x(\d+)', ui.adb('shell', 'wm', 'size').decode())[0])
    y = int(height * .4)

    def settled(opened):
        time.sleep(.35)
        nodes = list(ui.tree().iter('node'))
        close = next((n for n in nodes if n.get('content-desc') == '关闭文件面板'), None)
        if opened:
            assert close is not None, 'Explorer did not finish opening'
            tab = next(n for n in nodes if n.get('text') == '改动')
            left = int(re.findall(r'\d+', tab.get('bounds'))[0])
            assert left < width * .2, f'Explorer is stuck partially open: {left}'
        else:
            assert close is None, 'Explorer did not finish closing'
            assert not any(n.get('text') == '改动' for n in nodes), 'Partial panel is still visible'
            assert any(n.get('content-desc') == '打开文件和改动' for n in nodes)

    def swipe(start, end, duration=800):
        ui.adb('shell', 'input', 'swipe', str(int(width * start)), str(y),
               str(int(width * end)), str(y), str(duration))

    def cancel_drag(start, end):
        ui.adb('shell', 'input', 'motionevent', 'DOWN', str(int(width * start)), str(y))
        ui.adb('shell', 'input', 'motionevent', 'MOVE', str(int(width * end)), str(y))
        # Cancel the pointer stream after horizontal dragging has started.
        ui.adb('shell', 'input', 'motionevent', 'CANCEL', str(int(width * end)), str(y))

    ui.node('打开文件和改动', 'content-desc')
    for _ in range(3):
        swipe(.8, .65)
        settled(False)
    cancel_drag(.8, .6)
    settled(False)
    swipe(.85, .2)
    settled(True)
    ui.screenshot('opened')
    for _ in range(3):
        swipe(.2, .35)
        settled(True)
    cancel_drag(.2, .4)
    settled(True)
    swipe(.2, .85)
    settled(False)
    ui.click('打开文件和改动', 'content-desc')
    settled(True)
    ui.click('关闭文件面板', 'content-desc')
    settled(False)
    ui.screenshot('closed')
    print('Partial drags, full swipes, ACTION_CANCEL in both directions and buttons: passed', flush=True)


if __name__ == '__main__':
    main()
