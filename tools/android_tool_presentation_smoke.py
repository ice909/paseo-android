#!/usr/bin/env python3
"""Check tool groups, working footer and explorer contrast on a disposable emulator.

Requires mock_daemon.py --tool-presentation on port 6768. No commands are executed.
"""
import argparse
from pathlib import Path
import re
import struct
import time
import android_smoke as ui
from android_chat_smoke import replace
from android_theme_smoke import select_theme


def assert_foreground(node, expected):
    raw = ui.adb('exec-out', 'screencap')
    width, height, fmt = struct.unpack_from('<III', raw)
    assert fmt in (1, 2)
    offset = len(raw) - width * height * 4
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    color = bytes.fromhex(expected)
    count = sum(raw[offset + (y * width + x) * 4:offset + (y * width + x) * 4 + 3] == color
                for y in range(y1, min(y2, height)) for x in range(x1, min(x2, width)))
    assert count > 8, f'Foreground #{expected} missing in {node.attrib}; found {count} pixels'


def elapsed_seconds():
    labels = [n.get('text', '') for n in ui.tree().iter('node')]
    for value in labels:
        if re.fullmatch(r'\d+s|\d+m(?: \d+s)?|\d+h(?: \d+m)?', value):
            return sum(int(count) * {'s': 1, 'm': 60, 'h': 3600}[unit] for count, unit in re.findall(r'(\d+)([smh])', value))
    raise AssertionError('Running elapsed time missing')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', default='app/build/tool-presentation-smoke')
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
    ui.open_appearance()
    select_theme('Ghostty')
    ui.click('返回', 'content-desc')
    ui.node('消息输入框', 'content-desc')
    ui.click('打开文件和改动', 'content-desc')
    assert_foreground(ui.node('未提交的改动 · 1 个文件'), 'FAFAFA')
    assert_foreground(ui.node('关闭文件面板', 'content-desc'), 'FAFAFA')
    ui.screenshot('ghostty-explorer-contrast')
    ui.click('文件')
    assert_foreground(ui.node('README.md'), 'FAFAFA')
    ui.click('关闭文件面板', 'content-desc')

    replace('tools-running')
    ui.hide_keyboard()
    ui.click('发送消息', 'content-desc')
    label = '运行了 3 个命令 并 读取了 1 个文件'
    ui.node(label)
    ui.node('正在工作', 'content-desc')
    assert not any(n.get('text') in ('代理正在工作…', '失败') for n in ui.tree().iter('node'))
    before = elapsed_seconds()
    assert before < 120, before
    ui.screenshot('group-and-working-footer')
    ui.click(label)
    ui.node('关闭工具详情', 'content-desc')
    handle = ui.node('调整工具详情高度', 'content-desc')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', handle.get('bounds')))
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    ui.adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(max(100, y - 500)), '500')
    expanded = ui.node('调整工具详情高度', 'content-desc')
    assert int(re.findall(r'\d+', expanded.get('bounds'))[1]) < y1 - 100
    ui.node('git status --short')
    ui.node('app/Main.kt')
    ui.click('cat private.txt')
    ui.node('Permission denied: fixture')
    ui.screenshot('group-failed-command-details')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('正在工作', 'content-desc')
    assert elapsed_seconds() > before, 'Elapsed clock reset after closing tool details'
    ui.click(label)
    ui.node('Permission denied: fixture')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('正在工作', 'content-desc')
    ui.click('停止生成', 'content-desc')
    ui.node('发送消息', 'content-desc')
    assert not any(n.get('content-desc') == '正在工作' for n in ui.tree().iter('node'))

    replace('tools-complete')
    ui.hide_keyboard()
    ui.click('发送消息', 'content-desc')
    ui.node('All fixture tools finished.')
    ui.node(label)
    ui.screenshot('completed-turn-summary')
    summaries = [n for n in ui.tree().iter('node') if n.get('text') == label]
    ui.tap(max(summaries, key=lambda n: int(re.findall(r'\d+', n.get('bounds'))[1])))
    ui.node('git status --short')
    ui.node('app/Main.kt')
    ui.screenshot('expanded-turn-individual-tools')
    # Disable the default turn collapse to check a standalone mobile detail sheet.
    ui.click('添加附件和发送选项', 'content-desc')
    ui.click('折叠已完成的对话过程')
    assert not any(n.get('content-desc') == '关闭工具详情' for n in ui.tree().iter('node')), 'Display mode change opened a stale detail sheet'
    target = ui.node('sleep 30')
    ui.tap(target)
    ui.node('关闭工具详情', 'content-desc')
    ui.node('$ sleep 30\n\ndone')
    ui.screenshot('standalone-shell-sheet')
    ui.click('关闭工具详情', 'content-desc')
    ui.open_appearance()
    select_theme('Light')
    ui.click('返回', 'content-desc')
    ui.click('打开文件和改动', 'content-desc')
    assert_foreground(ui.node('README.md'), '1A1A1E')
    ui.click('改动')
    assert_foreground(ui.node('未提交的改动 · 1 个文件'), '1A1A1E')
    ui.screenshot('light-explorer-contrast')
    ui.click('关闭文件面板', 'content-desc')
    print('Dark/light contrast, tool grouping, command/path summaries, failed details, mobile sheets, running/stopped footer: passed', flush=True)


if __name__ == '__main__':
    main()
