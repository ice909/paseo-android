#!/usr/bin/env python3
"""Exercise the compact explorer on a disposable Android emulator and fixture daemon."""
import argparse
from pathlib import Path
import subprocess
import time
import android_smoke as ui
from android_chat_smoke import replace, text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', default='app/build/explorer-smoke')
    parser.add_argument('--reset', action='store_true')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        ui.adb('shell', 'pm', 'clear', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    if args.reset:
        ui.open_pairing()
        ui.fill(0, 'tcp://10.0.2.2:6768?password=test')
        ui.hide_keyboard()
        ui.submit_pairing()
    ui.node('已连接')
    ui.click('Native Android')
    ui.node('Native client review')
    replace('Explorer draft')
    ui.click('打开文件和改动', 'content-desc')
    ui.node('关闭文件面板', 'content-desc')
    assert b'mInputShown=true' not in ui.adb('shell', 'dumpsys', 'input_method')
    ui.node('改动')
    assert not any(n.get('content-desc') == '打开侧栏' for n in ui.tree().iter('node'))
    ui.screenshot('full-screen-changes')
    ui.click('文件')
    ui.node('README.md')
    ui.screenshot('full-screen-files')
    ui.click('README.md')
    ui.node('Paseo Native', contains=True)
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('README.md')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('消息输入框', 'content-desc')
    assert text() == 'Explorer draft', text()
    # Swiping left opens the full panel, short right swipes snap back,
    # vertical scrolling keeps it open, and a long right swipe closes it.
    drag = subprocess.Popen([ui.args.adb, '-s', 'emulator-5554', 'shell', 'input', 'swipe', '900', '500', '200', '500', '2000'])
    time.sleep(.7)
    ui.screenshot('opening-gesture')
    assert drag.wait() == 0
    ui.node('关闭文件面板', 'content-desc')
    ui.adb('shell', 'input', 'swipe', '300', '300', '500', '300', '1000')
    ui.node('关闭文件面板', 'content-desc')
    ui.adb('shell', 'input', 'swipe', '500', '1800', '500', '700', '600')
    ui.node('关闭文件面板', 'content-desc')
    ui.adb('shell', 'input', 'swipe', '200', '300', '900', '300', '700')
    ui.node('消息输入框', 'content-desc')
    assert text() == 'Explorer draft', text()
    ui.click('打开文件和改动', 'content-desc')
    ui.node('关闭文件面板', 'content-desc')
    ui.click('关闭文件面板', 'content-desc')
    ui.node('消息输入框', 'content-desc')
    print('Explorer overlay, tabs, file/back navigation, swipes and retained chat draft: passed', flush=True)


if __name__ == '__main__':
    main()
