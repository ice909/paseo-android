#!/usr/bin/env python3
"""Verify app icon surfaces after android_smoke.py, using mock_daemon.py --icon-gallery.

Only emulator-5554 is targeted. The app must already have the local mock host.
"""
import argparse
from pathlib import Path
import time
import android_smoke as ui


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', default='app/build/icon-smoke')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    ui.node('已连接')
    ui.screenshot('sidebar')
    ui.click('Native Android')
    ui.node('Native client review')
    ui.screenshot('chat-provider-and-header')
    ui.click('打开文件和改动', 'content-desc')
    ui.click('改动')
    ui.node('println("Paseo")')
    ui.screenshot('diff')
    ui.click('文件')
    ui.click('icon-gallery')
    ui.node('Main.kt')
    ui.node('App.tsx')
    ui.screenshot('file-types-and-kotlin-gradient')
    ui.adb('shell', 'input', 'swipe', '900', '1800', '900', '900', '350')
    ui.node('unknown.custom')
    ui.screenshot('file-type-fallback')
    ui.click('关闭文件面板', 'content-desc')
    ui.open_appearance()
    trigger = next(n for n in ui.tree().iter('node') if n.get('content-desc', '').startswith('主题：'))
    ui.tap(trigger)
    ui.screenshot('theme-menu')
    ui.tap(ui.node('选择主题：Dark', 'content-desc', contains=True))
    ui.click('返回', 'content-desc')
    ui.click('切换代理或终端', 'content-desc')
    ui.screenshot('provider-picker')
    ui.click('终端')
    nodes = list(ui.tree().iter('node'))
    if any(n.get('text') == '还没有终端' for n in nodes):
        ui.screenshot('empty-terminal')
        ui.click('新建终端', 'content-desc')
    ui.node('/home/dev/paseo', contains=True)
    ui.screenshot('terminal')
    errors = ui.adb('logcat', '-d', '-s', 'AndroidRuntime:E').decode()
    assert 'FATAL EXCEPTION' not in errors, errors[-2000:]
    print('App icon surfaces and colored vector rendering: passed', flush=True)


if __name__ == '__main__':
    main()
