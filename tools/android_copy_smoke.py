#!/usr/bin/env python3
"""Check per-response copy placement and clipboard contents on emulator-5554.

Requires mock_daemon.py --copy-actions --tool-presentation. No real agent runs.
"""
import argparse
from pathlib import Path
import re
import android_smoke as ui
from android_chat_smoke import replace, composer, text


def nodes(description):
    return [n for n in ui.tree().iter('node') if n.get('content-desc') == description]


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))


def paste_expected(expected):
    replace('')
    ui.tap(composer())
    ui.adb('shell', 'input', 'keyevent', '279')
    assert text() == expected, repr(text())
    ui.hide_keyboard()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--reset', action='store_true')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path('app/build/copy-smoke')
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
    ui.node('Final answer.')
    timing = next(n for n in ui.tree().iter('node') if n.get('content-desc', '').startswith('工作了'))
    ui.tap(timing)
    ui.screenshot('completion-timestamp')
    ui.node('工作了 0s')
    copies = nodes('复制回复')
    assert len(copies) == 1, len(copies)
    assert bounds(copies[0])[1] > bounds(ui.node('fixture-cleanup'))[3]
    assert bounds(copies[0])[0] <= bounds(ui.node('Final answer.'))[0] + 4
    ui.tap(copies[0])
    ui.screenshot('completed-copy-feedback')
    paste_expected('First **answer**.\n\nFinal answer.')
    ui.click('复制消息', 'content-desc')
    paste_expected('Copy fixture prompt')
    print('single completed footer, trailing tools, raw Markdown clipboard, user copy: passed', flush=True)

    # Expanding the work adds no extra copy buttons and keeps the hidden intro in the payload.
    summary = next(n for n in ui.tree().iter('node') if '运行了' in n.get('text', ''))
    ui.tap(summary)
    assert len(nodes('复制回复')) == 1
    ui.click('复制回复', 'content-desc')
    paste_expected('First **answer**.\n\nFinal answer.')
    ui.screenshot('expanded-response-copy')
    replace('tools-running')
    ui.hide_keyboard()
    ui.click('发送消息', 'content-desc')
    ui.node('正在工作', 'content-desc')
    prompt = bounds(ui.node('tools-running'))
    assert all(bounds(n)[3] < prompt[1] for n in nodes('复制回复'))
    ui.screenshot('running-no-copy')
    ui.click('停止生成', 'content-desc')
    ui.node('发送消息', 'content-desc')
    copies = nodes('复制回复')
    latest = max(copies, key=lambda n: bounds(n)[1])
    assert bounds(latest)[1] > bounds(ui.node('tools-running'))[3]
    ui.tap(latest)
    paste_expected('Tool presentation fixture')
    ui.screenshot('stopped-response-copy')
    print('expanded response, running footer, stop restores response copy: passed', flush=True)


if __name__ == '__main__':
    main()
